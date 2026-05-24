package com.example.haptictester.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.haptictester.haptic.AudioHapticController
import com.example.haptictester.haptic.AudioHapticController.HapticPulseDebug
import com.example.haptictester.haptic.HapticController
import com.example.haptictester.haptic.VideoHapticController
import com.example.haptictester.haptic.ThrottleGate

data class AudioDebugPulse(
    val sampleIndex: Int,
    val level: Int,
    val onMs: Long,
    val offMs: Long,
    val amplitude: Int,
    val isBass: Boolean,
    val isDrum: Boolean,
    val isSustained: Boolean,
)

data class AudioDebugFrame(
    val level: Int,
    val pulse: AudioDebugPulse? = null,
)

class HapticViewModel(application: Application) : AndroidViewModel(application) {
    private val haptic = HapticController(application.applicationContext)
    private val audioHaptic = AudioHapticController(application.applicationContext, haptic)
    private val videoHaptic = VideoHapticController(application.applicationContext, haptic)
    private val gate = ThrottleGate(100L)
    private var liveUpdateJob: Job? = null
    private var analysisJob: Job? = null
    private var sensitivityReanalyzeJob: Job? = null
    private var loadedAudioUri: Uri? = null
    private var loadedVideoUri: Uri? = null
    private var loadedVideoMapUri: Uri? = null
    private var analysisToken: Long = 0L

    private val _amplitude = MutableStateFlow(128)
    val amplitude = _amplitude.asStateFlow()

    private val _duty = MutableStateFlow(50)
    val duty = _duty.asStateFlow()

    private val _periodMs = MutableStateFlow(200)
    val periodMs = _periodMs.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()

    private val _hasAmplitude = MutableStateFlow(haptic.hasAmplitudeControl())
    val hasAmplitude = _hasAmplitude.asStateFlow()

    private val _hasVibrator = MutableStateFlow(haptic.hasVibrator())
    val hasVibrator = _hasVibrator.asStateFlow()

    private val _selectedAudioName = MutableStateFlow<String?>(null)
    val selectedAudioName = _selectedAudioName.asStateFlow()

    private val _audioPlaying = MutableStateFlow(false)
    val audioPlaying = _audioPlaying.asStateFlow()

    private val _audioVibrateEnabled = MutableStateFlow(true)
    val audioVibrateEnabled = _audioVibrateEnabled.asStateFlow()

    private val _audioLevel = MutableStateFlow(0)
    val audioLevel = _audioLevel.asStateFlow()

    private val _audioError = MutableStateFlow<String?>(null)
    val audioError = _audioError.asStateFlow()

    private val _audioAnalyzing = MutableStateFlow(false)
    val audioAnalyzing = _audioAnalyzing.asStateFlow()

    private val _audioAnalysisReady = MutableStateFlow(false)
    val audioAnalysisReady = _audioAnalysisReady.asStateFlow()

    private val _audioSensitivityLevel = MutableStateFlow(7)
    val audioSensitivityLevel = _audioSensitivityLevel.asStateFlow()

    private val _audioDebugFrames = MutableStateFlow<List<AudioDebugFrame>>(emptyList())
    val audioDebugFrames = _audioDebugFrames.asStateFlow()

    private val _selectedVideoName = MutableStateFlow<String?>(null)
    val selectedVideoName = _selectedVideoName.asStateFlow()

    private val _selectedVideoMapName = MutableStateFlow<String?>(null)
    val selectedVideoMapName = _selectedVideoMapName.asStateFlow()

    private val _videoPlaying = MutableStateFlow(false)
    val videoPlaying = _videoPlaying.asStateFlow()

    private val _videoError = MutableStateFlow<String?>(null)
    val videoError = _videoError.asStateFlow()

    private val _videoMapWindows = MutableStateFlow(0)
    val videoMapWindows = _videoMapWindows.asStateFlow()

    private val _videoMapWindowSizeMs = MutableStateFlow(0L)
    val videoMapWindowSizeMs = _videoMapWindowSizeMs.asStateFlow()

    fun setAmplitude(v: Int) {
        _amplitude.value = v.coerceIn(0, 255)
        // clear any queued vibrations for safety
        requestLiveUpdate()
    }

    fun setDuty(percent: Int) {
        _duty.value = percent.coerceIn(0, 100)
        requestLiveUpdate()
    }

    fun setPeriodMs(value: Int) {
        _periodMs.value = value.coerceIn(60, 1000)
        requestLiveUpdate()
    }

    fun startTest() {
        if (!_hasVibrator.value) return
        if (_audioPlaying.value) return
        if (!gate.canExecute()) return
        _isTesting.value = true
        requestLiveUpdate()
    }

    fun stopTest() {
        liveUpdateJob?.cancel()
        liveUpdateJob = null
        haptic.cancel()
        _isTesting.value = false
    }

    fun setAudioVibrateEnabled(enabled: Boolean) {
        _audioVibrateEnabled.value = enabled
        audioHaptic.setVibrateFromAudio(enabled)
    }

    fun setAudioSensitivityLevel(value: Int) {
        _audioSensitivityLevel.value = value.coerceIn(1, 10)
        pushAudioTuning()
        scheduleReanalyzeLoadedAudio()
    }

    fun loadAudio(uri: Uri) {
        val app = getApplication<Application>()
        loadedAudioUri = uri
        _audioDebugFrames.value = emptyList()
        _audioLevel.value = 0
        _audioAnalyzing.value = true
        _audioAnalysisReady.value = false
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // temporary permission is enough for this session
        }

        val displayName = audioHaptic.load(
            uri = uri,
            vibrateFromAudio = _audioVibrateEnabled.value,
            onLevel = { level ->
                _audioLevel.value = level
                appendAudioHistory(level)
                handleAudioLevel(level)
            },
            onPulse = { pulse ->
                appendPulseHistory(pulse)
            },
            onEnded = {
                _audioPlaying.value = false
            },
            onError = { message ->
                _audioError.value = message
            }
        )
        _selectedAudioName.value = displayName
        _audioError.value = null
        _audioPlaying.value = false

        startAnalysis(
            uri = uri,
            emptyMessage = "Pre-scan did not find a strong bass/drum pattern; live fallback remains available.",
        )
    }

    fun playAudio() {
        if (_audioAnalyzing.value) return
        stopTest()
        audioHaptic.play()
        _audioPlaying.value = true
    }

    fun pauseAudio() {
        audioHaptic.pause()
        _audioPlaying.value = false
        haptic.cancel()
    }

    fun stopAudio() {
        audioHaptic.stop()
        _audioPlaying.value = false
        _audioLevel.value = 0
        haptic.cancel()
    }

    fun loadVideo(uri: Uri) {
        val app = getApplication<Application>()
        loadedVideoUri = uri
        _selectedVideoName.value = resolveDisplayName(app, uri)
        _videoError.value = null
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // temporary permission is enough for this session
        }
    }

    fun loadVideoHapticMap(uri: Uri) {
        val app = getApplication<Application>()
        loadedVideoMapUri = uri
        _videoError.value = null
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // temporary permission is enough for this session
        }

        try {
            val label = videoHaptic.loadMap(uri)
            _selectedVideoMapName.value = label
            _videoMapWindows.value = videoHaptic.getWindowCount()
            _videoMapWindowSizeMs.value = videoHaptic.getWindowSizeMs()
        } catch (throwable: Throwable) {
            _selectedVideoMapName.value = null
            _videoMapWindows.value = 0
            _videoMapWindowSizeMs.value = 0L
            _videoError.value = throwable.message ?: throwable.toString()
        }
    }

    fun playVideo() {
        if (loadedVideoUri == null || loadedVideoMapUri == null) {
            _videoError.value = "Load both a video file and a haptic JSON map before playing."
            return
        }

        stopTest()
        stopAudio()
        _videoPlaying.value = true
        _videoError.value = null
    }

    fun pauseVideo() {
        _videoPlaying.value = false
        videoHaptic.stop()
    }

    fun stopVideo() {
        _videoPlaying.value = false
        videoHaptic.stop()
    }

    fun onVideoPlaybackPosition(positionMs: Int) {
        if (!_videoPlaying.value) return
        videoHaptic.updatePlaybackPosition(positionMs.toLong(), _amplitude.value)
    }

    private fun appendAudioHistory(level: Int) {
        val next = (_audioDebugFrames.value + AudioDebugFrame(level = level)).takeLast(120)
        _audioDebugFrames.value = next
    }

    private fun appendPulseHistory(pulse: HapticPulseDebug) {
        val framePulse = AudioDebugPulse(
            sampleIndex = _audioDebugFrames.value.size,
            level = pulse.level,
            onMs = pulse.onMs,
            offMs = pulse.offMs,
            amplitude = pulse.amplitude,
            isBass = pulse.isBassOnset,
            isDrum = pulse.isDrumHit,
            isSustained = pulse.isSustained,
        )
        _audioDebugFrames.value = (_audioDebugFrames.value + AudioDebugFrame(level = pulse.level, pulse = framePulse)).takeLast(120)
    }

    private fun requestLiveUpdate() {
        if (!_isTesting.value || !_hasVibrator.value) {
            return
        }

        liveUpdateJob?.cancel()
        liveUpdateJob = viewModelScope.launch {
            val waitMs = gate.timeUntilNextMs()
            if (waitMs > 0) {
                delay(waitMs)
            }
            if (!_isTesting.value) return@launch

            haptic.cancel()

            val amp = _amplitude.value
            val dutyPct = _duty.value
            val period = _periodMs.value.toLong()
            val onMs = (period * dutyPct / 100.0).toLong().coerceIn(10L, period)
            val offMs = (period - onMs).coerceAtLeast(10L)
            val timings = longArrayOf(onMs, offMs)
            val amplitudes = intArrayOf(amp, 0)
            haptic.vibrateWaveform(timings, amplitudes, -1)
        }
    }

    private fun handleAudioLevel(@Suppress("UNUSED_PARAMETER") level: Int) {
        // This is now handled entirely by AudioHapticController
        // so we don't double-vibrate or interfere with audio tracking
    }

    private fun pushAudioTuning() {
        val level = _audioSensitivityLevel.value.coerceIn(1, 10)

        val profile = when (level) {
            in 1..2 -> 0.0
            in 3..4 -> 0.25
            in 5..6 -> 0.5
            in 7..8 -> 0.75
            else -> 1.0
        }

        val noiseGate = (42.0 - profile * 36.0)
        val onsetThreshold = (60.0 - profile * 44.0).toInt()
        val sustainedThreshold = (118.0 - profile * 92.0).toInt()
        val smoothingAlpha = 0.06 + profile * 0.34
        val peakDecay = 0.996 - profile * 0.035
        val beatHoldoffMs = (320.0 - profile * 240.0).toLong()
        val bassThreshold = 520.0 - profile * 450.0
        val drumThreshold = 320.0 - profile * 270.0

        audioHaptic.updateTuning(
            noiseGate = noiseGate,
            onsetThreshold = onsetThreshold,
            sustainedThreshold = sustainedThreshold,
            smoothingAlpha = smoothingAlpha,
            peakDecay = peakDecay,
            beatHoldoffMs = beatHoldoffMs,
            useBassOnly = false,
            useDrumOnly = false,
            bassThreshold = bassThreshold,
            drumThreshold = drumThreshold,
        )
    }

    private fun reanalyzeLoadedAudio() {
        val uri = loadedAudioUri ?: return
        // Sensitivity changes should restart playback from the beginning
        // after analysis, not continue from the previous position.
        audioHaptic.stop()
        _audioPlaying.value = false
        _audioLevel.value = 0
        haptic.cancel()

        startAnalysis(
            uri = uri,
            emptyMessage = "Sensitivity update did not find a stronger bass/drum pattern; live fallback remains available.",
        )
    }

    private fun scheduleReanalyzeLoadedAudio() {
        val uri = loadedAudioUri ?: return

        sensitivityReanalyzeJob?.cancel()
        sensitivityReanalyzeJob = viewModelScope.launch {
            delay(250)
            if (loadedAudioUri != uri) return@launch
            reanalyzeLoadedAudio()
        }
    }

    private fun startAnalysis(
        uri: Uri,
        emptyMessage: String,
    ) {
        val token = ++analysisToken
        analysisJob?.cancel()
        _audioAnalyzing.value = true
        _audioAnalysisReady.value = false
        _audioError.value = null

        analysisJob = viewModelScope.launch {
            try {
                val events = audioHaptic.analyze(uri)
                if (token != analysisToken || loadedAudioUri != uri) return@launch
                audioHaptic.setPrecomputedAnalysis(events)
                _audioAnalysisReady.value = events.isNotEmpty()
                if (events.isEmpty()) {
                    _audioError.value = emptyMessage
                }
            } catch (throwable: Throwable) {
                if (token == analysisToken) {
                    _audioError.value = throwable.message ?: throwable.toString()
                }
            } finally {
                if (token == analysisToken) {
                    _audioAnalyzing.value = false
                }
            }
        }
    }

    override fun onCleared() {
        audioHaptic.release()
        videoHaptic.release()
        stopTest()
        super.onCleared()
    }

    private fun resolveDisplayName(app: Application, uri: Uri): String? {
        return try {
            app.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }
}

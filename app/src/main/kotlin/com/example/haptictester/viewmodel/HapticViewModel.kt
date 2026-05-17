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
    private val gate = ThrottleGate(100L)
    private var liveUpdateJob: Job? = null
    private var analysisJob: Job? = null
    private var loadedAudioUri: Uri? = null

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
        reanalyzeLoadedAudio()
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

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            try {
                val events = audioHaptic.analyze(uri)
                audioHaptic.setPrecomputedAnalysis(events)
                _audioAnalysisReady.value = events.isNotEmpty()
                if (events.isEmpty()) {
                    _audioError.value = "Pre-scan did not find a strong bass/drum pattern; live fallback remains available."
                }
            } catch (throwable: Throwable) {
                _audioError.value = throwable.message ?: throwable.toString()
            } finally {
                _audioAnalyzing.value = false
            }
        }
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

    private fun appendAudioHistory(level: Int) {
        val next = (_audioDebugFrames.value + AudioDebugFrame(level = level)).takeLast(120)
        _audioDebugFrames.value = next
    }

    private fun appendPulseHistory(pulse: HapticPulseDebug) {
        val framePulse = AudioDebugPulse(
            sampleIndex = 0,
            level = pulse.level,
            onMs = pulse.onMs,
            offMs = pulse.offMs,
            amplitude = pulse.amplitude,
            isBass = pulse.isBassOnset,
            isDrum = pulse.isDrumHit,
            isSustained = pulse.isSustained,
        )
        val current = _audioDebugFrames.value
        val next = if (current.isEmpty()) {
            listOf(AudioDebugFrame(level = pulse.level, pulse = framePulse))
        } else {
            current.dropLast(1) + current.last().copy(pulse = framePulse)
        }.takeLast(120)
        _audioDebugFrames.value = next
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
        val normalized = (level - 1) / 9.0

        val noiseGate = (28.0 - normalized * 22.0)
        val onsetThreshold = (40.0 - normalized * 30.0).toInt()
        val sustainedThreshold = (84.0 - normalized * 62.0).toInt()
        val smoothingAlpha = 0.08 + normalized * 0.26
        val peakDecay = 0.994 - normalized * 0.024
        val beatHoldoffMs = (220.0 - normalized * 150.0).toLong()
        val bassThreshold = 300.0 - normalized * 190.0
        val drumThreshold = 180.0 - normalized * 130.0

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
        if (_audioAnalyzing.value) return

        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            try {
                val events = audioHaptic.analyze(uri)
                audioHaptic.setPrecomputedAnalysis(events)
                _audioAnalysisReady.value = events.isNotEmpty()
                if (events.isEmpty()) {
                    _audioError.value = "Sensitivity update did not find a stronger bass/drum pattern; live fallback remains available."
                }
            } catch (throwable: Throwable) {
                _audioError.value = throwable.message ?: throwable.toString()
            }
        }
    }

    override fun onCleared() {
        audioHaptic.release()
        stopTest()
        super.onCleared()
    }
}

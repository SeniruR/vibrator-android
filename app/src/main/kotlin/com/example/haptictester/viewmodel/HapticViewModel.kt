package com.example.haptictester.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.haptictester.haptic.AudioHapticController
import com.example.haptictester.haptic.AudioHapticController.HapticPulseDebug
import com.example.haptictester.haptic.CompareAlgorithm
import com.example.haptictester.haptic.CompareSlotState
import com.example.haptictester.haptic.HapticFolderMatcher
import com.example.haptictester.haptic.HapticController
import com.example.haptictester.haptic.HapticTrackFormat
import com.example.haptictester.haptic.VideoHapticController
import com.example.haptictester.haptic.WavHapticParser
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
    private var hapticTrackLoadJob: Job? = null
    private var loadedAudioUri: Uri? = null
    private var loadedVideoUri: Uri? = null
    private var loadedHapticTrackUri: Uri? = null
    private var analysisToken: Long = 0L
    private var hapticTrackToken: Long = 0L
    private val compareLoadJobs = mutableMapOf<CompareAlgorithm, Job>()
    private val compareLoadTokens = mutableMapOf<CompareAlgorithm, Long>()
    private val compareSlotUris = mutableMapOf<CompareAlgorithm, Uri>()

    private val _compareSlots = MutableStateFlow(defaultCompareSlots())
    val compareSlots = _compareSlots.asStateFlow()

    private val _activeCompareAlgorithm = MutableStateFlow<CompareAlgorithm?>(null)
    val activeCompareAlgorithm = _activeCompareAlgorithm.asStateFlow()

    private val _amplitude = MutableStateFlow(255)
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

    private val _hasPrimitives = MutableStateFlow(haptic.hasScalablePrimitives())
    val hasPrimitives = _hasPrimitives.asStateFlow()

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

    private val _selectedHapticTrackName = MutableStateFlow<String?>(null)
    val selectedHapticTrackName = _selectedHapticTrackName.asStateFlow()

    private val _hapticTrackFormat = MutableStateFlow<HapticTrackFormat?>(null)
    val hapticTrackFormat = _hapticTrackFormat.asStateFlow()

    private val _hapticTrackLoading = MutableStateFlow(false)
    val hapticTrackLoading = _hapticTrackLoading.asStateFlow()

    private val _videoPlaying = MutableStateFlow(false)
    val videoPlaying = _videoPlaying.asStateFlow()

    private val _videoError = MutableStateFlow<String?>(null)
    val videoError = _videoError.asStateFlow()

    private val _videoMapWindows = MutableStateFlow(0)
    val videoMapWindows = _videoMapWindows.asStateFlow()

    private val _videoMapWindowSizeMs = MutableStateFlow(0L)
    val videoMapWindowSizeMs = _videoMapWindowSizeMs.asStateFlow()

    private val _hapticTrackDurationMs = MutableStateFlow(0L)
    val hapticTrackDurationMs = _hapticTrackDurationMs.asStateFlow()

    private val _eventTriggerMode = MutableStateFlow(false)
    val eventTriggerMode = _eventTriggerMode.asStateFlow()

    private val _pipelineEventsName = MutableStateFlow<String?>(null)
    val pipelineEventsName = _pipelineEventsName.asStateFlow()

    private val _pipelineEventCount = MutableStateFlow(0)
    val pipelineEventCount = _pipelineEventCount.asStateFlow()

    private val _demoVideoUri = MutableStateFlow<Uri?>(null)
    val demoVideoUri = _demoVideoUri.asStateFlow()

    private val _folderSummary = MutableStateFlow<String?>(null)
    val folderSummary = _folderSummary.asStateFlow()

    init {
        pushAudioTuning()
    }

    fun setAmplitude(v: Int) {
        _amplitude.value = v.coerceIn(0, 255)
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

    fun setEventTriggerMode(enabled: Boolean) {
        _eventTriggerMode.value = enabled
        videoHaptic.setEventTriggerMode(enabled)

        loadedHapticTrackUri?.let { uri ->
            if (_hapticTrackFormat.value == HapticTrackFormat.WAV) {
                loadHapticTrack(uri, HapticTrackFormat.WAV)
            }
        }
        compareSlotUris.forEach { (algorithm, uri) ->
            loadCompareSlot(algorithm, uri)
        }
    }

    fun loadPipelineEvents(uri: Uri) {
        val app = getApplication<Application>()
        takePersistableReadPermission(app, uri)
        _videoError.value = null
        viewModelScope.launch {
            try {
                val label = withContext(Dispatchers.IO) {
                    videoHaptic.loadPipelineEvents(uri)
                }
                _pipelineEventsName.value = label
                _pipelineEventCount.value = videoHaptic.getPipelineEventCount()
                if (!_eventTriggerMode.value && !videoHaptic.hasLoadedTrack()) {
                    setEventTriggerMode(true)
                }
            } catch (throwable: Throwable) {
                _pipelineEventsName.value = null
                _pipelineEventCount.value = 0
                videoHaptic.clearPipelineEvents()
                _videoError.value = throwable.message ?: throwable.toString()
            }
        }
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
        takePersistableReadPermission(app, uri)

        val displayName = audioHaptic.load(
            uri = uri,
            vibrateFromAudio = _audioVibrateEnabled.value,
            onLevel = { level ->
                _audioLevel.value = level
                appendAudioHistory(level)
            },
            onPulse = { pulse ->
                appendPulseHistory(pulse)
            },
            onEnded = {
                _audioPlaying.value = false
            },
            onError = { message ->
                _audioError.value = message
            },
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
        stopVideo()
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

    fun loadBundledDemo() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) {
                    unpackDemoAssets(app)
                } ?: return@launch
                val video = File(dir, "video.mp4")
                if (!video.exists()) return@launch

                setEventTriggerMode(true)
                val videoUri = Uri.fromFile(video)
                loadVideo(videoUri)
                _demoVideoUri.value = videoUri

                val slots = listOf(
                    CompareAlgorithm.A to "algorithm_a_perception_mapping.wav",
                    CompareAlgorithm.B to "algorithm_b_frequency_shifting.wav",
                    CompareAlgorithm.C to "algorithm_c_pitch_matching.wav",
                    CompareAlgorithm.D to "algorithm_d_haptic_gen.wav",
                    CompareAlgorithm.E to "algorithm_e_rule_based.wav",
                )
                for ((algorithm, name) in slots) {
                    val wav = File(dir, name)
                    if (wav.exists()) {
                        loadCompareSlot(algorithm, Uri.fromFile(wav))
                    }
                }
                // Read events from APK assets directly (avoid stale file:// / SAF copies).
                val eventsJson = withContext(Dispatchers.IO) {
                    runCatching {
                        app.assets.open("demo/events.json").bufferedReader().use { it.readText() }
                    }.getOrNull()
                }
                if (!eventsJson.isNullOrBlank()) {
                    loadPipelineEventsJson(eventsJson, label = "demo/events.json")
                } else {
                    val events = File(dir, "events.json")
                    if (events.exists()) {
                        loadPipelineEvents(Uri.fromFile(events))
                    }
                }
            } catch (throwable: Throwable) {
                _videoError.value = throwable.message ?: throwable.toString()
            }
        }
    }

    fun loadPipelineEventsJson(jsonText: String, label: String = "events.json") {
        _videoError.value = null
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    videoHaptic.loadPipelineEventsJson(jsonText, label)
                }
                _pipelineEventsName.value = loaded
                _pipelineEventCount.value = videoHaptic.getPipelineEventCount()
                if (!_eventTriggerMode.value && !videoHaptic.hasLoadedTrack()) {
                    setEventTriggerMode(true)
                }
            } catch (throwable: Throwable) {
                _pipelineEventsName.value = null
                _pipelineEventCount.value = 0
                videoHaptic.clearPipelineEvents()
                _videoError.value = throwable.message ?: throwable.toString()
            }
        }
    }

    fun loadFolder(treeUri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            try {
                takePersistableTreePermission(app, treeUri)
                val children = withContext(Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(app, treeUri)
                        ?: throw IllegalArgumentException("Unable to open the selected folder")
                    root.listFiles().filter { it.isFile && !it.name.isNullOrBlank() }
                }
                val byName = children.associateBy { it.name!! }
                val match = HapticFolderMatcher.match(byName.keys.toList())
                if (match.isEmpty) {
                    _folderSummary.value = null
                    _videoError.value = "No video, A–E WAVs, or events.json found in that folder."
                    return@launch
                }

                setEventTriggerMode(true)
                _folderSummary.value = match.summary()
                _videoError.value = null

                match.videoName?.let { name ->
                    val uri = byName[name]?.uri ?: return@let
                    loadVideo(uri)
                    _demoVideoUri.value = uri
                }
                for ((algorithm, name) in match.slotNames) {
                    val uri = byName[name]?.uri ?: continue
                    loadCompareSlot(algorithm, uri)
                }
                match.eventsName?.let { name ->
                    byName[name]?.uri?.let { loadPipelineEvents(it) }
                }
                if (match.slotNames.isEmpty()) {
                    match.hapticJsonName?.let { name ->
                        byName[name]?.uri?.let { loadVideoHapticMap(it) }
                    }
                }
            } catch (throwable: Throwable) {
                _folderSummary.value = null
                _videoError.value = throwable.message ?: throwable.toString()
            }
        }
    }

    private fun takePersistableTreePermission(app: Application, uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: Exception) {
            try {
                app.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // one-shot tree grants still allow listing for this session
            }
        }
    }

    private fun unpackDemoAssets(app: Application): File? {
        val names = app.assets.list("demo") ?: return null
        if (names.isEmpty()) return null
        val dir = File(app.cacheDir, "demo")
        dir.mkdirs()
        for (name in names) {
            app.assets.open("demo/$name").use { input ->
                File(dir, name).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return dir
    }

    fun loadVideo(uri: Uri) {
        val app = getApplication<Application>()
        loadedVideoUri = uri
        _selectedVideoName.value = resolveDisplayName(app, uri)
        _videoError.value = null
        takePersistableReadPermission(app, uri)
    }

    fun loadVideoHapticMap(uri: Uri) {
        loadHapticTrack(uri, HapticTrackFormat.JSON)
    }

    fun loadVideoHapticWav(uri: Uri) {
        loadHapticTrack(uri, HapticTrackFormat.WAV)
    }

    fun loadCompareSlot(algorithm: CompareAlgorithm, uri: Uri) {
        val app = getApplication<Application>()
        compareSlotUris[algorithm] = uri
        takePersistableReadPermission(app, uri)
        val fileName = resolveDisplayName(app, uri) ?: algorithm.shortLabel

        val token = (compareLoadTokens[algorithm] ?: 0L) + 1L
        compareLoadTokens[algorithm] = token
        compareLoadJobs[algorithm]?.cancel()

        updateCompareSlot(algorithm) {
            it.copy(loading = true, error = null, fileName = fileName)
        }

        compareLoadJobs[algorithm] = viewModelScope.launch {
            try {
                val map = withContext(Dispatchers.IO) {
                    WavHapticParser.parse(app, uri, eventTriggerMode = _eventTriggerMode.value)
                }
                if (compareLoadTokens[algorithm] != token) return@launch

                updateCompareSlot(algorithm) {
                    it.copy(
                        loading = false,
                        fileName = fileName,
                        map = map,
                        error = null,
                    )
                }

                if (_activeCompareAlgorithm.value == null) {
                    _activeCompareAlgorithm.value = algorithm
                }
                if (_activeCompareAlgorithm.value == algorithm) {
                    applyCompareSlot(algorithm, seekPositionMs = null)
                }
            } catch (throwable: Throwable) {
                if (compareLoadTokens[algorithm] == token) {
                    updateCompareSlot(algorithm) {
                        it.copy(
                            loading = false,
                            map = null,
                            error = throwable.message ?: throwable.toString(),
                        )
                    }
                }
            }
        }
    }

    fun switchCompareSlot(algorithm: CompareAlgorithm, positionMs: Long) {
        val slot = _compareSlots.value[algorithm] ?: return
        if (slot.map == null) {
            _videoError.value = "Load algorithm ${algorithm.shortLabel} WAV first."
            return
        }
        _activeCompareAlgorithm.value = algorithm
        applyCompareSlot(algorithm, seekPositionMs = positionMs)
        _videoError.value = null
    }

    fun hasAnyCompareSlotReady(): Boolean {
        return _compareSlots.value.values.any { it.isReady }
    }

    private fun applyCompareSlot(algorithm: CompareAlgorithm, seekPositionMs: Long?) {
        val slot = _compareSlots.value[algorithm] ?: return
        val map = slot.map ?: return
        val label = "${algorithm.shortLabel} · ${slot.fileName ?: algorithm.description}"
        videoHaptic.loadMapData(map, label)
        _selectedHapticTrackName.value = label
        _hapticTrackFormat.value = HapticTrackFormat.WAV
        _videoMapWindows.value = map.track.size
        _videoMapWindowSizeMs.value = map.windowSizeMs
        _hapticTrackDurationMs.value = map.durationMs
        if (seekPositionMs != null && _videoPlaying.value) {
            videoHaptic.forceSyncAt(seekPositionMs, _amplitude.value)
        }
    }

    private fun updateCompareSlot(
        algorithm: CompareAlgorithm,
        transform: (CompareSlotState) -> CompareSlotState,
    ) {
        _compareSlots.value = _compareSlots.value.toMutableMap().apply {
            val current = get(algorithm) ?: CompareSlotState(algorithm)
            put(algorithm, transform(current))
        }
    }

    private fun defaultCompareSlots(): Map<CompareAlgorithm, CompareSlotState> {
        return CompareAlgorithm.all.associateWith { CompareSlotState(it) }
    }

    private fun loadHapticTrack(uri: Uri, format: HapticTrackFormat) {
        val app = getApplication<Application>()
        loadedHapticTrackUri = uri
        _videoError.value = null
        takePersistableReadPermission(app, uri)

        val token = ++hapticTrackToken
        hapticTrackLoadJob?.cancel()
        _hapticTrackLoading.value = true
        clearHapticTrackState()

        hapticTrackLoadJob = viewModelScope.launch {
            try {
                val label = withContext(Dispatchers.IO) {
                    when (format) {
                        HapticTrackFormat.JSON -> videoHaptic.loadMap(uri)
                        HapticTrackFormat.WAV -> videoHaptic.loadWav(uri)
                    }
                }
                if (token != hapticTrackToken || loadedHapticTrackUri != uri) return@launch

                _selectedHapticTrackName.value = label
                _hapticTrackFormat.value = videoHaptic.getTrackFormat()
                _videoMapWindows.value = videoHaptic.getWindowCount()
                _videoMapWindowSizeMs.value = videoHaptic.getWindowSizeMs()
                _hapticTrackDurationMs.value = videoHaptic.getDurationMs()
            } catch (throwable: Throwable) {
                if (token == hapticTrackToken) {
                    clearHapticTrackState()
                    _videoError.value = throwable.message ?: throwable.toString()
                }
            } finally {
                if (token == hapticTrackToken) {
                    _hapticTrackLoading.value = false
                }
            }
        }
    }

    fun playVideo() {
        if (loadedVideoUri == null) {
            _videoError.value = "Load a video file before playing."
            return
        }
        val hasTrack = videoHaptic.hasLoadedTrack() ||
            hasAnyCompareSlotReady() ||
            (_eventTriggerMode.value && videoHaptic.hasPipelineEvents())
        if (!hasTrack) {
            _videoError.value = "Load a haptic track (JSON, WAV, or A–D compare slots) before playing."
            return
        }
        if (_hapticTrackLoading.value) {
            _videoError.value = "Haptic track is still loading. Please wait."
            return
        }

        if (!videoHaptic.hasLoadedTrack()) {
            val active = _activeCompareAlgorithm.value
                ?: _compareSlots.value.entries.firstOrNull { it.value.isReady }?.key
            if (active != null) {
                applyCompareSlot(active, seekPositionMs = null)
            }
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

    fun onVideoPlaybackPosition(positionMs: Int, @Suppress("UNUSED_PARAMETER") videoDurationMs: Long = 0L) {
        if (!_videoPlaying.value) return
        if (!videoHaptic.hasLoadedTrack() && !videoHaptic.hasPipelineEvents()) return
        videoHaptic.updatePlaybackPosition(positionMs.toLong(), _amplitude.value)
    }

    fun hasLoadedHapticTrack(): Boolean = videoHaptic.hasLoadedTrack()

    private fun clearHapticTrackState() {
        _selectedHapticTrackName.value = null
        _hapticTrackFormat.value = null
        _videoMapWindows.value = 0
        _videoMapWindowSizeMs.value = 0L
        _hapticTrackDurationMs.value = 0L
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

    private fun takePersistableReadPermission(app: Application, uri: Uri) {
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: Exception) {
            // file:// demo URIs and one-shot picker grants do not persist
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

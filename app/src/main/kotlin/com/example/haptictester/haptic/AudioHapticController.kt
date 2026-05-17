package com.example.haptictester.haptic

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.Manifest
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max

class AudioHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    enum class AnalysisKind {
        Bass,
        Drum,
        Combined,
    }

    data class AnalysisEvent(
        val positionMs: Long,
        val kind: AnalysisKind,
        val intensity: Int,
    )

    data class HapticPulseDebug(
        val level: Int,
        val onMs: Long,
        val offMs: Long,
        val amplitude: Int,
        val isBassOnset: Boolean,
        val isDrumHit: Boolean,
        val isSustained: Boolean,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    private val TAG = "AudioHapticController"
    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var onLevelCallback: ((Int) -> Unit)? = null
    private var onPulseCallback: ((HapticPulseDebug) -> Unit)? = null
    private var onEndedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((Throwable) -> Unit)? = null
    private var vibrateGate = ThrottleGate(10L)  // Minimal throttle; polling is already frequent
    private var currentLabel: String = ""
    private var vibrateFromAudio = true
    private val fftSeen = AtomicBoolean(false)
    private var lastLevel = 0
    private var lastPulseTime = 0L
    private var recentPeak = 0  // Track recent peak for better onset detection
    // Smoothing / noise-gating to avoid false triggers from background noise
    private var smoothedLevel = 0.0
    private var noiseFloor = 0.0
    private var bassFloor = 0.0
    private var drumFloor = 0.0
    private var prevBassEnergy = 0.0
    private var prevDrumEnergy = 0.0
    private var lastBassPulseTime = 0L
    private var lastDrumPulseTime = 0L
    private var lastAnalysisEvents: List<AnalysisEvent> = emptyList()
    private var playbackJob: Job? = null
    private var playbackEventIndex = 0
    @Volatile private var noiseGate = 12.0
    @Volatile private var onsetThreshold = 20
    @Volatile private var sustainedThreshold = 50
    @Volatile private var smoothingAlpha = 0.2
    @Volatile private var peakDecay = 0.98
    @Volatile private var beatHoldoffMs = 110L
    // Spectral detection (FFT) tuning
    @Volatile private var useBassOnly = false
    @Volatile private var useDrumOnly = false
    @Volatile private var bassThreshold = 120.0
    @Volatile private var drumThreshold = 60.0
    private var prevSpectrum: DoubleArray? = null
    private val BASS_BINS = 4
    private val SPECTRUM_BINS = 32

    fun updateTuning(
        noiseGate: Double,
        onsetThreshold: Int,
        sustainedThreshold: Int,
        smoothingAlpha: Double,
        peakDecay: Double,
        beatHoldoffMs: Long,
        useBassOnly: Boolean = false,
        useDrumOnly: Boolean = false,
        bassThreshold: Double = 120.0,
        drumThreshold: Double = 60.0,
    ) {
        this.noiseGate = noiseGate.coerceIn(0.0, 40.0)
        this.onsetThreshold = onsetThreshold.coerceIn(1, 50)
        this.sustainedThreshold = sustainedThreshold.coerceIn(1, 100)
        this.smoothingAlpha = smoothingAlpha.coerceIn(0.05, 0.8)
        this.peakDecay = peakDecay.coerceIn(0.80, 0.999)
        this.beatHoldoffMs = beatHoldoffMs.coerceIn(60L, 250L)
        this.useBassOnly = useBassOnly
        this.useDrumOnly = useDrumOnly
        this.bassThreshold = bassThreshold
        this.drumThreshold = drumThreshold
        Log.d(
            TAG,
            "updateTuning noiseGate=${this.noiseGate} onset=${this.onsetThreshold} sustained=${this.sustainedThreshold} smoothing=${this.smoothingAlpha} peakDecay=${this.peakDecay} holdoff=${this.beatHoldoffMs} bassThreshold=${this.bassThreshold} drumThreshold=${this.drumThreshold}",
        )
    }
    
    private var pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private val callbackSeen = AtomicBoolean(false)

    fun setVibrateFromAudio(enabled: Boolean) {
        vibrateFromAudio = enabled
    }

    fun load(
        uri: Uri,
        vibrateFromAudio: Boolean,
        onLevel: (Int) -> Unit,
        onPulse: (HapticPulseDebug) -> Unit,
        onEnded: () -> Unit,
        onError: (String) -> Unit,
    ): String {
        release()
        this.vibrateFromAudio = vibrateFromAudio
        onLevelCallback = onLevel
        onPulseCallback = onPulse
        onEndedCallback = onEnded
        onErrorCallback = { ex -> onError(ex.toString()) }
        currentLabel = resolveDisplayName(uri)
        resetAnalysisState()
        clearPrecomputedAnalysis()

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(context, uri)
                setOnCompletionListener {
                    disableVisualizer()
                    onEndedCallback?.invoke()
                }
                setOnErrorListener { _, what, extra ->
                    disableVisualizer()
                    onErrorCallback?.invoke(Exception("MediaPlayer error: what=$what extra=$extra"))
                    true
                }
                prepare()
            }
            mediaPlayer = player
            Log.d(TAG, "MediaPlayer prepared; sessionId=${player.audioSessionId}")
            setupVisualizer(player.audioSessionId)
            return currentLabel
        } catch (throwable: Throwable) {
            Log.e(TAG, "load() failed", throwable)
            onErrorCallback?.invoke(throwable)
            release()
            return currentLabel.ifBlank { "Audio file" }
        }
    }

    suspend fun analyze(uri: Uri): List<AnalysisEvent> = withContext(Dispatchers.Default) {
        try {
            val events = analyzeStream(uri)
            Log.d(TAG, "analyze: detected ${events.size} events")
            events
        } catch (throwable: Throwable) {
            Log.e(TAG, "analyze failed", throwable)
            emptyList()
        }
    }

    fun setPrecomputedAnalysis(events: List<AnalysisEvent>) {
        lastAnalysisEvents = mergeNearbyEvents(events)
        playbackEventIndex = 0
        Log.d(TAG, "setPrecomputedAnalysis events=${lastAnalysisEvents.size}")
    }

    fun play() {
        mediaPlayer?.start()
        if (lastAnalysisEvents.isNotEmpty()) {
            disableVisualizer()
            startPlaybackScheduler()
        } else {
            enableVisualizer()
        }
    }

    fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            }
        }
        disableVisualizer()
    }

    fun stop() {
        mediaPlayer?.run {
            try {
                if (isPlaying) {
                    pause()
                }
                seekTo(0)
            } catch (_: Throwable) {
                // ignore reset errors
            }
        }
        disableVisualizer()
        stopPlaybackScheduler()
        hapticController.cancel()
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    fun getLabel(): String = currentLabel

    fun release() {
        disableVisualizer()
        stopPolling()
        stopPlaybackScheduler()
        pollingScope.cancel()
        pollingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        visualizer?.release()
        visualizer = null
        mediaPlayer?.release()
        mediaPlayer = null
        hapticController.cancel()
    }

    private fun startPlaybackScheduler() {
        stopPlaybackScheduler()
        val player = mediaPlayer ?: return
        val events = lastAnalysisEvents
        if (events.isEmpty()) return

        playbackEventIndex = events.indexOfFirst { it.positionMs >= player.currentPosition }
            .let { if (it < 0) events.size else it }

        playbackJob = pollingScope.launch {
            while (true) {
                val currentPlayer = mediaPlayer ?: break
                if (!currentPlayer.isPlaying) {
                    delay(30)
                    continue
                }

                val positionMs = currentPlayer.currentPosition.toLong()
                while (playbackEventIndex < events.size && events[playbackEventIndex].positionMs <= positionMs + 15L) {
                    fireAnalysisEvent(events[playbackEventIndex])
                    playbackEventIndex++
                }

                if (playbackEventIndex >= events.size) {
                    break
                }

                delay(12)
            }
        }
    }

    private fun stopPlaybackScheduler() {
        playbackJob?.cancel()
        playbackJob = null
        playbackEventIndex = 0
    }

    private fun fireAnalysisEvent(event: AnalysisEvent) {
        if (!vibrateGate.canExecute()) return

        val now = System.currentTimeMillis()
        val amplitude = if (hapticController.hasAmplitudeControl()) {
            when (event.kind) {
                AnalysisKind.Bass -> (110 + event.intensity * 1.4).roundToInt().coerceIn(1, 255)
                AnalysisKind.Drum -> (70 + event.intensity * 1.7).roundToInt().coerceIn(1, 255)
                AnalysisKind.Combined -> (140 + event.intensity * 1.2).roundToInt().coerceIn(1, 255)
            }
        } else {
            255
        }

        when (event.kind) {
            AnalysisKind.Bass -> {
                hapticController.cancel()
                hapticController.vibrateOneShot(260L, amplitude)
            }
            AnalysisKind.Drum -> {
                hapticController.cancel()
                hapticController.vibrateWaveform(
                    timings = longArrayOf(12L, 18L, 10L),
                    amplitudes = intArrayOf(amplitude, 0, (amplitude * 4 / 5).coerceAtLeast(1)),
                    repeat = -1,
                )
            }
            AnalysisKind.Combined -> {
                hapticController.cancel()
                hapticController.vibrateWaveform(
                    timings = longArrayOf(48L, 16L, 12L, 24L),
                    amplitudes = intArrayOf(amplitude, 0, (amplitude * 3 / 5).coerceAtLeast(1), 0),
                    repeat = -1,
                )
            }
        }

        onPulseCallback?.invoke(
            HapticPulseDebug(
                level = event.intensity.coerceIn(0, 100),
                onMs = when (event.kind) {
                    AnalysisKind.Bass -> 260L
                    AnalysisKind.Drum -> 12L
                    AnalysisKind.Combined -> 48L
                },
                offMs = 0L,
                amplitude = amplitude,
                isBassOnset = event.kind != AnalysisKind.Drum,
                isDrumHit = event.kind != AnalysisKind.Bass,
                isSustained = event.kind == AnalysisKind.Bass && event.intensity >= 65,
                timestampMs = now,
            ),
        )
    }

    private fun setupVisualizer(sessionId: Int) {
        try {
            val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            Log.d(TAG, "RECORD_AUDIO permission=$perm (0=GRANTED)")
            if (perm != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "RECORD_AUDIO not granted; Visualizer will not capture audio")
                onErrorCallback?.invoke(SecurityException("RECORD_AUDIO not granted"))
                return
            }
            val maxRate = Visualizer.getMaxCaptureRate()
            Log.d(TAG, "Visualizer.getMaxCaptureRate()=$maxRate")
            val captureRange = Visualizer.getCaptureSizeRange()
            val captureSize = chooseCaptureSize(captureRange[0], captureRange[1])
            visualizer = Visualizer(sessionId).apply {
                setCaptureSize(captureSize)
                    setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer,
                            waveform: ByteArray,
                            samplingRate: Int,
                        ) {
                            try {
                                val playing = mediaPlayer?.isPlaying == true
                                Log.v(TAG, "onWaveFormDataCapture received len=${waveform.size} isPlaying=$playing samplingRate=$samplingRate")
                                // Keep waveform data for the live level meter.
                                if (playing) {
                                    val level = calculateLevel(waveform)
                                    callbackSeen.set(true)
                                    onLevelCallback?.invoke(level)
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "onWaveFormDataCapture error", t)
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer,
                            fft: ByteArray,
                            samplingRate: Int,
                        ) {
                            try {
                                fftSeen.set(true)
                                // Compute magnitudes for first few bins
                                val bins = fft.size / 2
                                val useBins = minOf(SPECTRUM_BINS, bins - 1)
                                val mags = DoubleArray(useBins + 1)
                                for (k in 1..useBins) {
                                    val re = fft.getOrNull(2 * k)?.toInt() ?: 0
                                    val im = fft.getOrNull(2 * k + 1)?.toInt() ?: 0
                                    mags[k] = kotlin.math.sqrt((re * re + im * im).toDouble())
                                }

                                // bass energy = sum of first BASS_BINS
                                val bassEnergy = (1..minOf(BASS_BINS, useBins)).sumOf { mags[it] }

                                // spectral flux over selected bins
                                val prev = prevSpectrum
                                var flux = 0.0
                                if (prev != null) {
                                    for (k in 1..useBins) {
                                        val delta = mags[k] - (prev.getOrNull(k) ?: 0.0)
                                        if (delta > 0) flux += delta
                                    }
                                }
                                prevSpectrum = mags

                                Log.v(TAG, "FFT bins=$useBins bassEnergy=${bassEnergy.roundToInt()} flux=${flux.roundToInt()} samplingRate=$samplingRate")

                                val playing = mediaPlayer?.isPlaying == true
                                if (vibrateFromAudio && playing) {
                                    driveStructuredPattern(
                                        bassEnergy = bassEnergy,
                                        drumEnergy = flux,
                                        waveformLevel = lastLevel,
                                    )
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "onFftDataCapture error", t)
                            }
                        }
                        }, Visualizer.getMaxCaptureRate(), true, true)
            }
            Log.d(TAG, "Visualizer set up; captureSize=$captureSize maxRate=$maxRate")
            callbackSeen.set(false)
            fftSeen.set(false)
            pollingScope.launch {
                delay(1000)
                if (!callbackSeen.get()) {
                    Log.w(TAG, "No Visualizer callbacks after 1s for session=$sessionId; keeping the current session and polling waveform data")
                } else {
                    Log.d(TAG, "Visualizer callbacks active for session=$sessionId")
                }
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "setupVisualizer failed", throwable)
            onErrorCallback?.invoke(throwable)
            visualizer?.release()
            visualizer = null
        }
    }

    private fun enableVisualizer() {
        try {
            visualizer?.enabled = true
            Log.d(TAG, "enableVisualizer enabled=${visualizer?.enabled}")
            startPolling()
        } catch (throwable: Throwable) {
            Log.e(TAG, "enableVisualizer error", throwable)
            onErrorCallback?.invoke(throwable)
        }
    }

    private fun disableVisualizer() {
        try {
            stopPolling()
            visualizer?.enabled = false
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun startPolling() {
        stopPolling()  // Ensure no duplicate jobs
        pollingJob = pollingScope.launch {
            val waveformBuffer = ByteArray(256)
            while (true) {
                try {
                    delay(40)  // Poll every 40ms (25Hz) — much faster than callback (~3Hz)
                    if (!vibrateFromAudio) continue
                    
                    val viz = visualizer ?: continue
                    if (!viz.enabled) continue

                    if (fftSeen.get()) {
                        // FFT-driven analysis owns haptic output when available.
                        continue
                    }
                    
                    // getWaveForm populates the buffer with captured waveform data
                    val bytesRead = viz.getWaveForm(waveformBuffer)
                    if (bytesRead > 0) {
                        val waveform = waveformBuffer.copyOfRange(0, bytesRead)
                        val level = calculateLevel(waveform)
                        Log.d(TAG, "polling bytesRead=$bytesRead level=$level")
                        onLevelCallback?.invoke(level)
                        if (level > 0) {
                            driveLevel(level)
                        } else {
                            hapticController.cancel()
                        }
                    } else {
                        Log.v(TAG, "polling bytesRead=0")
                    }
                } catch (_: Throwable) {
                    // Ignore polling errors (e.g., visualizer released)
                }
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun driveLevel(level: Int) {
        Log.d(TAG, "driveLevel called level=$level lastLevel=$lastLevel recentPeak=$recentPeak")

        if (!vibrateGate.canExecute()) return

        val now = System.currentTimeMillis()
        val sinceLastPulse = now - lastPulseTime

        // smoothing to reduce jitter (simple low-pass)
        smoothedLevel = smoothedLevel * (1.0 - smoothingAlpha) + level * smoothingAlpha

        // update noise floor slowly (tracks ambient background level)
        noiseFloor = noiseFloor * 0.995 + smoothedLevel * 0.005

        val effective = (smoothedLevel - noiseFloor).coerceAtLeast(0.0)

        // gate tiny background noise
        if (effective < noiseGate) {
            hapticController.cancel()
            lastLevel = smoothedLevel.toInt()
            recentPeak = (recentPeak * 0.95).toInt()
            return
        }

        val clamped = smoothedLevel.roundToInt().coerceIn(0, 100)
        val levelChange = clamped - lastLevel
        val peakDrop = recentPeak - clamped

        // Beat-first behavior: if pulses are too close together, ignore them unless they are very strong.
        val isWithinHoldoff = sinceLastPulse in 0 until beatHoldoffMs

        // reduced sensitivity: require larger onsets for bass hits
        val isBassOnset = levelChange > onsetThreshold && (peakDrop > 12 || recentPeak < 45)
        val isStrongTransient = levelChange > onsetThreshold + 8 && effective > (noiseGate + 8)
        val isSustained = effective > sustainedThreshold && lastLevel > 35

        if (isWithinHoldoff && !isStrongTransient) {
            lastLevel = clamped
            recentPeak = (recentPeak * peakDecay).toInt()
            return
        }

        // Update peak tracking (decays slowly)
        if (clamped > recentPeak) recentPeak = clamped else recentPeak = (recentPeak * peakDecay).toInt()

        val amplitude = if (hapticController.hasAmplitudeControl()) (20 + clamped * 235 / 100).coerceIn(1, 255) else 255

        hapticController.cancel()

        val onMs = when {
            isStrongTransient -> 16L
            isBassOnset -> 18L
            isSustained -> 80L
            clamped > 65 -> 45L
            clamped > 45 -> 30L
            else -> 20L
        }
        val offMs = maxOf(20L, beatHoldoffMs - onMs)

        // For sustained audio, use a continuous one-shot vibration instead
        // of a repeating short waveform. This prevents the motor from
        // sounding like sparks and gives a steadier rumble.
        if (isSustained) {
            val continuousMs = maxOf(300L, onMs * 4)
            hapticController.vibrateOneShot(continuousMs, amplitude)
        } else {
            hapticController.vibrateWaveform(
                timings = longArrayOf(onMs, offMs),
                amplitudes = intArrayOf(amplitude, 0),
                repeat = -1,
            )
        }
        onPulseCallback?.invoke(
            HapticPulseDebug(
                level = clamped,
                onMs = onMs,
                offMs = offMs,
                amplitude = amplitude,
                isBassOnset = isBassOnset || isStrongTransient,
                isDrumHit = false,
                isSustained = isSustained,
                timestampMs = now,
            )
        )

        lastLevel = clamped
        lastPulseTime = now
    }

    private fun driveStructuredPattern(bassEnergy: Double, drumEnergy: Double, waveformLevel: Int) {
        if (!vibrateGate.canExecute()) return
        val now = System.currentTimeMillis()

        bassFloor = bassFloor * 0.985 + bassEnergy * 0.015
        drumFloor = drumFloor * 0.985 + drumEnergy * 0.015

        val bassAboveFloor = (bassEnergy - bassFloor).coerceAtLeast(0.0)
        val drumAboveFloor = (drumEnergy - drumFloor).coerceAtLeast(0.0)
        val bassRise = (bassEnergy - prevBassEnergy).coerceAtLeast(0.0)
        val drumRise = (drumEnergy - prevDrumEnergy).coerceAtLeast(0.0)

        prevBassEnergy = bassEnergy
        prevDrumEnergy = drumEnergy

        val bassHit = bassAboveFloor > maxOf(bassThreshold * 0.30, 18.0) && bassRise > maxOf(12.0, bassThreshold * 0.08)
        val drumHit = drumAboveFloor > maxOf(drumThreshold * 0.35, 14.0) && drumRise > maxOf(18.0, drumThreshold * 0.12)
        val sustainedBass = bassAboveFloor > maxOf(bassThreshold * 0.70, 45.0) && bassEnergy > maxOf(bassThreshold * 1.50, bassFloor * 1.22)
        val combinedHit = bassHit && drumHit

        val bassCooldownMs = 140L
        val drumCooldownMs = 90L

        if (useBassOnly && !bassHit) return
        if (useDrumOnly && !drumHit) return

        if (!bassHit && !drumHit) {
            if (now - lastPulseTime > beatHoldoffMs) {
                hapticController.cancel()
            }
            return
        }

        val intensityScore = when {
            combinedHit -> maxOf(bassAboveFloor / maxOf(1.0, bassThreshold), drumAboveFloor / maxOf(1.0, drumThreshold))
            bassHit -> bassAboveFloor / maxOf(1.0, bassThreshold)
            else -> drumAboveFloor / maxOf(1.0, drumThreshold)
        }
        val amplitude = if (hapticController.hasAmplitudeControl()) {
            (32 + (intensityScore.coerceIn(0.0, 2.5) / 2.5 * 200.0).roundToInt()).coerceIn(1, 255)
        } else {
            255
        }

        val signalLevel = maxOf(waveformLevel, intensityScore.roundToInt().coerceIn(0, 100))
        val onMs = when {
            sustainedBass -> 260L
            combinedHit -> 70L
            bassHit -> 56L
            else -> 14L
        }
        val drumSparkMs = when {
            drumRise > drumThreshold * 0.35 -> 18L
            drumHit -> 12L
            else -> 10L
        }
        val offMs = maxOf(20L, beatHoldoffMs - onMs)

        val canBassFire = now - lastBassPulseTime >= bassCooldownMs
        val canDrumFire = now - lastDrumPulseTime >= drumCooldownMs

        if (sustainedBass && canBassFire) {
            hapticController.cancel()
            hapticController.vibrateOneShot(maxOf(320L, onMs * 2), amplitude)
            lastBassPulseTime = now
        } else if (drumHit && canDrumFire) {
            hapticController.cancel()
            hapticController.vibrateWaveform(
                timings = longArrayOf(drumSparkMs, 22L, drumSparkMs, maxOf(18L, offMs / 2)),
                amplitudes = intArrayOf(amplitude, 0, (amplitude * 4 / 5).coerceAtLeast(1), 0),
                repeat = -1,
            )
            lastDrumPulseTime = now
        } else if (bassHit && canBassFire) {
            hapticController.cancel()
            hapticController.vibrateWaveform(
                timings = longArrayOf(onMs, maxOf(24L, offMs)),
                amplitudes = intArrayOf(amplitude, 0),
                repeat = -1,
            )
            lastBassPulseTime = now
        } else if (combinedHit && canBassFire && canDrumFire) {
            hapticController.cancel()
            hapticController.vibrateWaveform(
                timings = longArrayOf(onMs, 18L, drumSparkMs, maxOf(22L, offMs / 2)),
                amplitudes = intArrayOf(amplitude, 0, (amplitude * 3 / 5).coerceAtLeast(1), 0),
                repeat = -1,
            )
            lastBassPulseTime = now
            lastDrumPulseTime = now
        } else {
            return
        }

        onPulseCallback?.invoke(
            HapticPulseDebug(
                level = signalLevel,
                onMs = onMs,
                offMs = offMs,
                amplitude = amplitude,
                isBassOnset = bassHit,
                isDrumHit = drumHit,
                isSustained = sustainedBass,
                timestampMs = now,
            ),
        )

        lastPulseTime = now
    }

    private fun calculateLevel(waveform: ByteArray): Int {
        if (waveform.isEmpty()) return 0
        var sumSquares = 0.0
        for (sample in waveform) {
            val centered = sample.toInt()
            sumSquares += (centered * centered).toDouble()
        }
        val rms = sqrt(sumSquares / waveform.size)
        return ((rms / 128.0) * 100.0).roundToInt().coerceIn(0, 100)
    }

    private fun chooseCaptureSize(minSize: Int, maxSize: Int): Int {
        val preferred = 256
        return when {
            preferred < minSize -> minSize
            preferred > maxSize -> maxSize
            else -> preferred
        }
    }

    private fun analyzeStream(uri: Uri): List<AnalysisEvent> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = selectAudioTrack(extractor)
            if (trackIndex < 0) return emptyList()

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val detector = StreamingEventDetector(sampleRate)

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                        detector.consume(outputBuffer, bufferInfo, format)
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // output format is handled on demand in appendPcmSamples
                    }
                }
            }

            return detector.finish()
        } finally {
            try {
                codec?.stop()
            } catch (_: Throwable) {
            }
            try {
                codec?.release()
            } catch (_: Throwable) {
            }
            try {
                extractor.release()
            } catch (_: Throwable) {
            }
        }
    }

    private inner class StreamingEventDetector(
        private val sampleRate: Int,
    ) {
        private val windowSize = 1024
        private val hopSize = 256
        private val window = FloatArray(windowSize)
        private var windowPos = 0
        private var samplesSeen = 0L
        private var bassBaseline = 0.0
        private var drumBaseline = 0.0
        private var prevBassScore = 0.0
        private var prevDrumScore = 0.0
        private var lastBassMs = -10_000L
        private var lastDrumMs = -10_000L
        private val events = mutableListOf<AnalysisEvent>()

        fun consume(outputBuffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo, format: MediaFormat) {
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }

            val sampleBytes = bufferInfo.size
            if (sampleBytes <= 0) return

            val data = ByteArray(sampleBytes)
            outputBuffer.position(bufferInfo.offset)
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
            outputBuffer.get(data)

            var byteIndex = 0
            while (byteIndex + 1 < data.size) {
                val sample = ((data[byteIndex + 1].toInt() shl 8) or (data[byteIndex].toInt() and 0xFF)).toShort()
                if (channelCount <= 1) {
                    pushSample(sample.toInt() / 32768f)
                    byteIndex += 2
                } else {
                    var sum = sample.toInt() / 32768f
                    var ch = 1
                    byteIndex += 2
                    while (ch < channelCount && byteIndex + 1 < data.size) {
                        val next = ((data[byteIndex + 1].toInt() shl 8) or (data[byteIndex].toInt() and 0xFF)).toShort()
                        sum += next.toInt() / 32768f
                        byteIndex += 2
                        ch++
                    }
                    pushSample((sum / channelCount).coerceIn(-1f, 1f))
                }
            }
        }

        fun finish(): List<AnalysisEvent> = mergeNearbyEvents(events)

        private fun pushSample(value: Float) {
            window[windowPos++] = value
            samplesSeen++
            if (windowPos < windowSize) return

            val bassScore = lowBandEnergy(window, sampleRate)
            val drumScore = highBandEnergy(window, sampleRate)

            bassBaseline = if (bassBaseline == 0.0) bassScore else bassBaseline * 0.97 + bassScore * 0.03
            drumBaseline = if (drumBaseline == 0.0) drumScore else drumBaseline * 0.97 + drumScore * 0.03

            val bassDelta = bassScore - prevBassScore
            val drumDelta = drumScore - prevDrumScore
            val currentMs = ((samplesSeen - windowSize / 2L) * 1000L / sampleRate).coerceAtLeast(0L)

            val bassHit = bassScore > maxOf(bassBaseline * 1.28, bassThreshold) &&
                bassDelta > maxOf(bassBaseline * 0.08, bassThreshold * 0.12) &&
                currentMs - lastBassMs > 140L
            val drumHit = drumScore > maxOf(drumBaseline * 1.32, drumThreshold) &&
                drumDelta > maxOf(drumBaseline * 0.12, drumThreshold * 0.15) &&
                currentMs - lastDrumMs > 90L

            if (bassHit && drumHit) {
                events += AnalysisEvent(currentMs, AnalysisKind.Combined, scoreToIntensity(max(bassScore - bassBaseline, drumScore - drumBaseline)))
                lastBassMs = currentMs
                lastDrumMs = currentMs
            } else if (bassHit) {
                events += AnalysisEvent(currentMs, AnalysisKind.Bass, scoreToIntensity(bassScore - bassBaseline))
                lastBassMs = currentMs
            } else if (drumHit) {
                events += AnalysisEvent(currentMs, AnalysisKind.Drum, scoreToIntensity(drumScore - drumBaseline))
                lastDrumMs = currentMs
            }

            prevBassScore = bassScore
            prevDrumScore = drumScore

            System.arraycopy(window, hopSize, window, 0, windowSize - hopSize)
            windowPos = windowSize - hopSize
        }
    }

    private fun appendPcmSamples(
        outputBuffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        format: MediaFormat,
        output: MutableList<Float>,
    ) {
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else {
            1
        }

        val sampleBytes = bufferInfo.size
        if (sampleBytes <= 0) return

        val data = ByteArray(sampleBytes)
        outputBuffer.position(bufferInfo.offset)
        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
        outputBuffer.get(data)

        val shorts = ShortArray(sampleBytes / 2)
        var shortIndex = 0
        var byteIndex = 0
        while (byteIndex + 1 < data.size && shortIndex < shorts.size) {
            shorts[shortIndex++] = ((data[byteIndex + 1].toInt() shl 8) or (data[byteIndex].toInt() and 0xFF)).toShort()
            byteIndex += 2
        }

        if (channelCount <= 1) {
            for (value in shorts) {
                output.add((value.toInt() / 32768f).coerceIn(-1f, 1f))
            }
        } else {
            var index = 0
            while (index + channelCount - 1 < shorts.size) {
                var sum = 0f
                for (ch in 0 until channelCount) {
                    sum += shorts[index + ch].toInt() / 32768f
                }
                output.add((sum / channelCount).coerceIn(-1f, 1f))
                index += channelCount
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                return index
            }
        }
        return -1
    }

    private fun detectEvents(samples: FloatArray, sampleRate: Int): List<AnalysisEvent> {
        val windowSize = 1024
        val hopSize = 256
        if (samples.size < windowSize) return emptyList()

        val window = FloatArray(windowSize)
        var windowPos = 0
        var sampleCursor = 0
        var bassBaseline = 0.0
        var drumBaseline = 0.0
        var prevBassScore = 0.0
        var prevDrumScore = 0.0
        var lastBassMs = -10_000L
        var lastDrumMs = -10_000L
        val events = mutableListOf<AnalysisEvent>()

        while (sampleCursor < samples.size) {
            window[windowPos++] = samples[sampleCursor++]
            if (windowPos < windowSize) continue

            val bassScore = lowBandEnergy(window, sampleRate)
            val drumScore = highBandEnergy(window, sampleRate)

            bassBaseline = if (bassBaseline == 0.0) bassScore else bassBaseline * 0.97 + bassScore * 0.03
            drumBaseline = if (drumBaseline == 0.0) drumScore else drumBaseline * 0.97 + drumScore * 0.03

            val bassDelta = bassScore - prevBassScore
            val drumDelta = drumScore - prevDrumScore
            val currentMs = ((sampleCursor - windowSize / 2L) * 1000L / sampleRate).coerceAtLeast(0L)

            val bassHit = bassScore > maxOf(bassBaseline * 1.28, bassThreshold) &&
                bassDelta > maxOf(bassBaseline * 0.08, bassThreshold * 0.12) &&
                currentMs - lastBassMs > 140L
            val drumHit = drumScore > maxOf(drumBaseline * 1.32, drumThreshold) &&
                drumDelta > maxOf(drumBaseline * 0.12, drumThreshold * 0.15) &&
                currentMs - lastDrumMs > 90L

            if (bassHit && drumHit) {
                events += AnalysisEvent(currentMs, AnalysisKind.Combined, scoreToIntensity(max(bassScore - bassBaseline, drumScore - drumBaseline)))
                lastBassMs = currentMs
                lastDrumMs = currentMs
            } else if (bassHit) {
                events += AnalysisEvent(currentMs, AnalysisKind.Bass, scoreToIntensity(bassScore - bassBaseline))
                lastBassMs = currentMs
            } else if (drumHit) {
                events += AnalysisEvent(currentMs, AnalysisKind.Drum, scoreToIntensity(drumScore - drumBaseline))
                lastDrumMs = currentMs
            }

            prevBassScore = bassScore
            prevDrumScore = drumScore

            System.arraycopy(window, hopSize, window, 0, windowSize - hopSize)
            windowPos = windowSize - hopSize
        }

        return mergeNearbyEvents(events)
    }

    private fun mergeNearbyEvents(events: List<AnalysisEvent>): List<AnalysisEvent> {
        if (events.isEmpty()) return emptyList()
        val merged = mutableListOf<AnalysisEvent>()
        var current = events.first()

        for (next in events.drop(1)) {
            if (next.positionMs - current.positionMs <= 90L) {
                val kind = when {
                    current.kind == next.kind -> current.kind
                    current.kind == AnalysisKind.Combined || next.kind == AnalysisKind.Combined -> AnalysisKind.Combined
                    else -> AnalysisKind.Combined
                }
                current = AnalysisEvent(
                    positionMs = minOf(current.positionMs, next.positionMs),
                    kind = kind,
                    intensity = max(current.intensity, next.intensity),
                )
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun scoreToIntensity(score: Double): Int {
        val compressed = kotlin.math.ln1p(score.coerceAtLeast(0.0)) * 28.0
        return compressed.roundToInt().coerceIn(0, 100)
    }

    private fun lowBandEnergy(window: FloatArray, sampleRate: Int): Double {
        val frequencies = intArrayOf(60, 90, 120, 150)
        return frequencies.sumOf { goertzelEnergy(window, sampleRate, it) } / frequencies.size
    }

    private fun highBandEnergy(window: FloatArray, sampleRate: Int): Double {
        val frequencies = intArrayOf(300, 600, 900, 1400, 2200)
        return frequencies.sumOf { goertzelEnergy(window, sampleRate, it) } / frequencies.size
    }

    private fun goertzelEnergy(samples: FloatArray, sampleRate: Int, frequencyHz: Int): Double {
        val n = samples.size
        val k = (0.5 + (n * frequencyHz.toDouble() / sampleRate)).toInt().coerceAtLeast(1)
        val omega = 2.0 * Math.PI * k / n
        val coeff = 2.0 * cos(omega)
        var q0 = 0.0
        var q1 = 0.0
        var q2 = 0.0
        for (sample in samples) {
            q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        return q1 * q1 + q2 * q2 - coeff * q1 * q2
    }

    private fun clearPrecomputedAnalysis() {
        lastAnalysisEvents = emptyList()
        stopPlaybackScheduler()
    }

    private fun resetAnalysisState() {
        fftSeen.set(false)
        lastLevel = 0
        lastPulseTime = 0L
        recentPeak = 0
        smoothedLevel = 0.0
        noiseFloor = 0.0
        bassFloor = 0.0
        drumFloor = 0.0
        prevBassEnergy = 0.0
        prevDrumEnergy = 0.0
        lastBassPulseTime = 0L
        lastDrumPulseTime = 0L
        prevSpectrum = null
    }

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex) ?: "Audio file"
                } else {
                    "Audio file"
                }
            } ?: "Audio file"
        } catch (_: Throwable) {
            "Audio file"
        }
    }
}

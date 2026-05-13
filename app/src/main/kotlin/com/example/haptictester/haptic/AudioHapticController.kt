package com.example.haptictester.haptic

import android.content.Context
import android.media.AudioAttributes
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AudioHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    private val TAG = "AudioHapticController"
    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var onLevelCallback: ((Int) -> Unit)? = null
    private var onEndedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((Throwable) -> Unit)? = null
    private var vibrateGate = ThrottleGate(10L)  // Minimal throttle; polling is already frequent
    private var currentLabel: String = ""
    private var vibrateFromAudio = true
    private var lastLevel = 0
    private var lastPulseTime = 0L
    private var recentPeak = 0  // Track recent peak for better onset detection
    // Smoothing / noise-gating to avoid false triggers from background noise
    private var smoothedLevel = 0.0
    private var noiseFloor = 0.0
    @Volatile private var noiseGate = 12.0
    @Volatile private var onsetThreshold = 20
    @Volatile private var sustainedThreshold = 50
    @Volatile private var smoothingAlpha = 0.2
    @Volatile private var peakDecay = 0.98
    @Volatile private var beatHoldoffMs = 110L

    fun updateTuning(
        noiseGate: Double,
        onsetThreshold: Int,
        sustainedThreshold: Int,
        smoothingAlpha: Double,
        peakDecay: Double,
        beatHoldoffMs: Long,
    ) {
        this.noiseGate = noiseGate.coerceIn(0.0, 40.0)
        this.onsetThreshold = onsetThreshold.coerceIn(1, 50)
        this.sustainedThreshold = sustainedThreshold.coerceIn(1, 100)
        this.smoothingAlpha = smoothingAlpha.coerceIn(0.05, 0.8)
        this.peakDecay = peakDecay.coerceIn(0.80, 0.999)
        this.beatHoldoffMs = beatHoldoffMs.coerceIn(60L, 250L)
        Log.d(
            TAG,
            "updateTuning noiseGate=${this.noiseGate} onset=${this.onsetThreshold} sustained=${this.sustainedThreshold} smoothing=${this.smoothingAlpha} peakDecay=${this.peakDecay} holdoff=${this.beatHoldoffMs}",
        )
    }
    
    private val pollingScope = CoroutineScope(Dispatchers.Default)
    private var pollingJob: Job? = null
    private val callbackSeen = AtomicBoolean(false)

    fun setVibrateFromAudio(enabled: Boolean) {
        vibrateFromAudio = enabled
    }

    fun load(
        uri: Uri,
        vibrateFromAudio: Boolean,
        onLevel: (Int) -> Unit,
        onEnded: () -> Unit,
        onError: (String) -> Unit,
    ): String {
        release()
        this.vibrateFromAudio = vibrateFromAudio
        onLevelCallback = onLevel
        onEndedCallback = onEnded
        onErrorCallback = { ex -> onError(ex.toString()) }
        currentLabel = resolveDisplayName(uri)

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

    fun play() {
        mediaPlayer?.start()
        enableVisualizer()
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
        hapticController.cancel()
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying ?: false

    fun getLabel(): String = currentLabel

    fun release() {
        disableVisualizer()
        stopPolling()
        pollingScope.cancel()
        visualizer?.release()
        visualizer = null
        mediaPlayer?.release()
        mediaPlayer = null
        hapticController.cancel()
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
                                // Always log that callback fired; then decide whether to process
                                if (vibrateFromAudio && playing) {
                                    val level = calculateLevel(waveform)
                                    Log.d(TAG, "onWaveFormDataCapture level=$level")
                                    callbackSeen.set(true)
                                    onLevelCallback?.invoke(level)
                                    if (level > 0) {
                                        driveLevel(level)
                                    } else {
                                        hapticController.cancel()
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e(TAG, "onWaveFormDataCapture error", t)
                            }
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer,
                            fft: ByteArray,
                            samplingRate: Int,
                        ) = Unit
                    }, Visualizer.getMaxCaptureRate(), true, false)
            }
            Log.d(TAG, "Visualizer set up; captureSize=$captureSize maxRate=$maxRate")
            // If we don't see any callbacks within 1s, try global session (0)
            callbackSeen.set(false)
            pollingScope.launch {
                delay(1000)
                if (!callbackSeen.get()) {
                    Log.w(TAG, "No Visualizer callbacks after 1s for session=$sessionId, falling back to global session 0")
                    try {
                        visualizer?.release()
                        visualizer = null
                        // try global session id (0)
                        setupVisualizer(0)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Fallback to global Visualizer failed", t)
                    }
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

        hapticController.vibrateWaveform(
            timings = longArrayOf(onMs, offMs),
            amplitudes = intArrayOf(amplitude, 0),
            repeat = -1,
        )

        lastLevel = clamped
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

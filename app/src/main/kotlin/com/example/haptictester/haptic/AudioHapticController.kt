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
        if (!vibrateGate.canExecute()) {
            return
        }

        val clamped = level.coerceIn(0, 100)
        if (clamped <= 5) {
            hapticController.cancel()
            lastLevel = 0
            recentPeak = (recentPeak * 0.95).toInt()  // Decay peak slowly
            return
        }

        val now = System.currentTimeMillis()
        val levelChange = clamped - lastLevel
        val peakDrop = recentPeak - clamped
        
        // Detect sudden onset (bass hit): level jumps up quickly AND previous peak has decayed
        val isBassOnset = levelChange > 12 && (peakDrop > 10 || recentPeak < 40)
        
        // Detect sustained high tone: level is high and steady
        val isSustained = clamped > 45 && lastLevel > 35

        // Update peak tracking
        if (clamped > recentPeak) {
            recentPeak = clamped
        } else {
            recentPeak = (recentPeak * 0.98).toInt()  // Slow decay to smooth out peaks
        }

        val amplitude = if (hapticController.hasAmplitudeControl()) {
            (20 + clamped * 235 / 100).coerceIn(1, 255)
        } else {
            255
        }

        hapticController.cancel()

        // Adaptive pulse strategy for pop music:
        // - Bass onset (kick drums) = short snappy pulse
        // - Sustained tones (pads, strings) = longer rumble
        // - Mid-range steady = balanced pulse
        val onMs = when {
            isBassOnset -> 15L      // Very snappy for bass hits
            isSustained -> 70L      // Longer rumble for sustained notes
            clamped > 60 -> 45L     // High levels = moderate pulse
            clamped > 40 -> 30L     // Mid levels = medium pulse
            else -> 20L             // Low levels = light ticks
        }
        val offMs = maxOf(8L, 120L - onMs)

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

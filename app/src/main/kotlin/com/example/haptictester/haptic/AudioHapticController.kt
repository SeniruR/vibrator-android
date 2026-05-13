package com.example.haptictester.haptic

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.provider.OpenableColumns
import kotlin.math.roundToInt
import kotlin.math.sqrt

class AudioHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    private var mediaPlayer: MediaPlayer? = null
    private var visualizer: Visualizer? = null
    private var onLevelCallback: ((Int) -> Unit)? = null
    private var onEndedCallback: (() -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var vibrateGate = ThrottleGate(100L)
    private var currentLabel: String = ""
    private var vibrateFromAudio = true

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
        onErrorCallback = onError
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
                    onErrorCallback?.invoke("MediaPlayer error: what=$what extra=$extra")
                    true
                }
                prepare()
            }
            mediaPlayer = player
            setupVisualizer(player.audioSessionId)
            return currentLabel
        } catch (throwable: Throwable) {
            onErrorCallback?.invoke(throwable.message ?: "Failed to load audio")
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
        visualizer?.release()
        visualizer = null
        mediaPlayer?.release()
        mediaPlayer = null
        hapticController.cancel()
    }

    private fun setupVisualizer(sessionId: Int) {
        try {
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
                        if (!vibrateFromAudio) return
                        val level = calculateLevel(waveform)
                        onLevelCallback?.invoke(level)
                        if (level > 0) {
                            driveLevel(level)
                        } else {
                            hapticController.cancel()
                        }
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int,
                    ) = Unit
                }, Visualizer.getMaxCaptureRate() / 10, true, false)
            }
        } catch (throwable: Throwable) {
            onErrorCallback?.invoke(throwable.message ?: "Visualizer not available")
            visualizer?.release()
            visualizer = null
        }
    }

    private fun enableVisualizer() {
        try {
            visualizer?.enabled = true
        } catch (throwable: Throwable) {
            onErrorCallback?.invoke(throwable.message ?: "Could not enable audio visualizer")
        }
    }

    private fun disableVisualizer() {
        try {
            visualizer?.enabled = false
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun driveLevel(level: Int) {
        if (!vibrateGate.canExecute()) {
            return
        }

        val clamped = level.coerceIn(0, 100)
        val onMs = (15L + (clamped * 85L / 100L)).coerceIn(10L, 100L)
        val offMs = (100L - onMs).coerceAtLeast(10L)
        val amplitude = if (hapticController.hasAmplitudeControl()) {
            (20 + clamped * 235 / 100).coerceIn(1, 255)
        } else {
            255
        }
        hapticController.cancel()
        hapticController.vibrateWaveform(
            timings = longArrayOf(onMs, offMs),
            amplitudes = intArrayOf(amplitude, 0),
            repeat = -1,
        )
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

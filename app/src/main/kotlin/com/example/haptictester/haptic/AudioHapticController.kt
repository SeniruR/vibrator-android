package com.example.haptictester.haptic

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class AudioHapticEvent(
    val timeMs: Long,
    val pulse: AudioHapticController.HapticPulseDebug,
)

class AudioHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    data class HapticPulseDebug(
        val level: Int,
        val onMs: Long,
        val offMs: Long,
        val amplitude: Int,
        val isBassOnset: Boolean,
        val isDrumHit: Boolean,
        val isSustained: Boolean,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var pendingPlay = false
    private var vibrateFromAudio = true
    private var events: List<AudioHapticEvent> = emptyList()
    private var nextEventIndex = 0
    private var onLevel: (Int) -> Unit = {}
    private var onPulse: (HapticPulseDebug) -> Unit = {}
    private var onEnded: () -> Unit = {}
    private var onError: (String) -> Unit = {}
    private var noiseGate = 24.0
    private var onsetThreshold = 38
    private var sustainedThreshold = 72
    private var smoothingAlpha = 0.2
    private var peakDecay = 0.98
    private var beatHoldoffMs = 120L
    private var bassThreshold = 180.0
    private var drumThreshold = 120.0

    private val playbackTick = object : Runnable {
        override fun run() {
            val mediaPlayer = player ?: return
            if (!mediaPlayer.isPlaying) return
            val positionMs = mediaPlayer.currentPosition.toLong()
            var level = 0
            while (nextEventIndex < events.size && events[nextEventIndex].timeMs <= positionMs) {
                val pulse = events[nextEventIndex++].pulse
                level = pulse.level
                onPulse(pulse)
                if (vibrateFromAudio) {
                    hapticController.vibrateWaveform(
                        longArrayOf(pulse.onMs, pulse.offMs),
                        intArrayOf(pulse.amplitude, 0),
                        -1,
                    )
                }
            }
            onLevel(level)
            handler.postDelayed(this, PLAYBACK_TICK_MS)
        }
    }

    fun load(
        uri: Uri,
        vibrateFromAudio: Boolean,
        onLevel: (Int) -> Unit,
        onPulse: (HapticPulseDebug) -> Unit,
        onEnded: () -> Unit,
        onError: (String) -> Unit,
    ): String {
        stop()
        this.vibrateFromAudio = vibrateFromAudio
        this.onLevel = onLevel
        this.onPulse = onPulse
        this.onEnded = onEnded
        this.onError = onError
        events = emptyList()
        nextEventIndex = 0
        val name = displayName(uri) ?: uri.lastPathSegment ?: "Audio"
        try {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                setOnPreparedListener {
                    if (pendingPlay) startPlayback()
                }
                setOnCompletionListener {
                    handler.removeCallbacks(playbackTick)
                    hapticController.cancel()
                    onLevel(0)
                    onEnded()
                }
                setOnErrorListener { _, what, extra ->
                    handler.removeCallbacks(playbackTick)
                    hapticController.cancel()
                    onError("Audio playback failed ($what/$extra)")
                    true
                }
                prepareAsync()
            }
        } catch (throwable: Throwable) {
            player?.release()
            player = null
            onError(throwable.message ?: "Unable to load audio")
        }
        return name
    }

    fun play() {
        val mediaPlayer = player ?: return
        if (mediaPlayer.isPlaying) return
        try {
            pendingPlay = true
            mediaPlayer.start()
            startPlayback()
        } catch (_: IllegalStateException) {
            pendingPlay = true
        }
    }

    fun pause() {
        pendingPlay = false
        handler.removeCallbacks(playbackTick)
        runCatching { player?.pause() }
    }

    fun stop() {
        pendingPlay = false
        handler.removeCallbacks(playbackTick)
        runCatching { player?.stop() }
        player?.release()
        player = null
        nextEventIndex = 0
        hapticController.cancel()
    }

    fun release() = stop()

    fun setVibrateFromAudio(enabled: Boolean) {
        vibrateFromAudio = enabled
        if (!enabled) hapticController.cancel()
    }

    fun updateTuning(
        noiseGate: Double,
        onsetThreshold: Int,
        sustainedThreshold: Int,
        smoothingAlpha: Double,
        peakDecay: Double,
        beatHoldoffMs: Long,
        useBassOnly: Boolean,
        useDrumOnly: Boolean,
        bassThreshold: Double,
        drumThreshold: Double,
    ) {
        this.noiseGate = noiseGate
        this.onsetThreshold = onsetThreshold
        this.sustainedThreshold = sustainedThreshold
        this.smoothingAlpha = smoothingAlpha
        this.peakDecay = peakDecay
        this.beatHoldoffMs = beatHoldoffMs
        this.bassThreshold = bassThreshold
        this.drumThreshold = drumThreshold
        analyzeBassOnly = useBassOnly
        analyzeDrumOnly = useDrumOnly
    }

    private var analyzeBassOnly = false
    private var analyzeDrumOnly = false

    fun setPrecomputedAnalysis(analysis: List<AudioHapticEvent>) {
        events = analysis.sortedBy { it.timeMs }
        nextEventIndex = player?.currentPosition?.let { position ->
            events.indexOfFirst { it.timeMs >= position }.let { if (it < 0) events.size else it }
        } ?: 0
    }

    suspend fun analyze(uri: Uri): List<AudioHapticEvent> {
        val gate = noiseGate
        val onset = onsetThreshold
        val sustained = sustainedThreshold
        val alpha = smoothingAlpha
        val decay = peakDecay
        val holdoff = beatHoldoffMs
        val bassCutoff = bassThreshold
        val drumCutoff = drumThreshold
        val bassOnly = analyzeBassOnly
        val drumOnly = analyzeDrumOnly
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeAndAnalyze(
                uri,
                gate,
                onset,
                sustained,
                alpha,
                decay,
                holdoff,
                bassCutoff,
                drumCutoff,
                bassOnly,
                drumOnly,
            )
        }
    }

    private fun decodeAndAnalyze(
        uri: Uri,
        noiseGate: Double,
        onsetThreshold: Int,
        sustainedThreshold: Int,
        smoothingAlpha: Double,
        peakDecay: Double,
        beatHoldoffMs: Long,
        bassThreshold: Double,
        drumThreshold: Double,
        bassOnly: Boolean,
        drumOnly: Boolean,
    ): List<AudioHapticEvent> {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return emptyList()
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return emptyList()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            extractor.selectTrack(track)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val result = mutableListOf<AudioHapticEvent>()
            val info = MediaCodec.BufferInfo()
            val windowSamples = mutableListOf<Double>()
            val framesPerWindow = (sampleRate * ANALYSIS_WINDOW_MS / 1000).coerceAtLeast(1L).toInt()
            var inputDone = false
            var outputDone = false
            var windowStartMs = 0L
            var previousLevel = 0.0
            var smoothedLevel = 0.0
            var peak = 0.0
            var lowPass = 0.0
            var previousPulseMs = Long.MIN_VALUE

            fun analyzeWindow() {
                if (windowSamples.isEmpty()) return
                var squareSum = 0.0
                var lowSquareSum = 0.0
                var crossingCount = 0
                var previousSample = windowSamples.first()
                val coefficient = (1.0 - exp(-2.0 * PI * bassThreshold.coerceAtLeast(1.0) / sampleRate)).coerceIn(0.001, 1.0)
                for (sample in windowSamples) {
                    squareSum += sample * sample
                    lowPass += coefficient * (sample - lowPass)
                    lowSquareSum += lowPass * lowPass
                    if ((sample >= 0.0) != (previousSample >= 0.0)) crossingCount++
                    previousSample = sample
                }
                val rms = sqrt(squareSum / windowSamples.size)
                val level = rms * 255.0
                smoothedLevel += smoothingAlpha.coerceIn(0.0, 1.0) * (level - smoothedLevel)
                peak = maxOf(level, peak * peakDecay.coerceIn(0.0, 1.0))
                val attack = maxOf(0.0, level - previousLevel)
                previousLevel = smoothedLevel
                val lowRatio = if (squareSum > 0.0) (lowSquareSum / squareSum).coerceIn(0.0, 1.0) else 0.0
                val crossingHz = crossingCount * sampleRate / (2.0 * windowSamples.size)
                val bassOnset = level >= noiseGate && lowRatio >= 0.25 && crossingHz <= bassThreshold
                val drumHit = level >= noiseGate && crossingHz >= drumThreshold && attack >= onsetThreshold
                val sustainedHit = peak >= sustainedThreshold && attack < onsetThreshold
                val selected = when {
                    bassOnly -> bassOnset
                    drumOnly -> drumHit
                    else -> bassOnset || drumHit || sustainedHit
                }
                if (selected && level >= noiseGate && windowStartMs - previousPulseMs >= beatHoldoffMs) {
                    val levelInt = level.roundToInt().coerceIn(1, 255)
                    val onMs = (35L + levelInt * 0.32).toLong().coerceIn(35L, 120L)
                    val pulse = HapticPulseDebug(
                        level = levelInt,
                        onMs = onMs,
                        offMs = (ANALYSIS_WINDOW_MS - onMs).coerceAtLeast(0L),
                        amplitude = levelInt,
                        isBassOnset = bassOnset,
                        isDrumHit = drumHit,
                        isSustained = sustainedHit,
                    )
                    result += AudioHapticEvent(windowStartMs, pulse)
                    previousPulseMs = windowStartMs
                }
                windowSamples.clear()
                windowStartMs += ANALYSIS_WINDOW_MS
            }

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && info.size > 0) {
                            outputBuffer.position(info.offset)
                            outputBuffer.limit(info.offset + info.size)
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            while (outputBuffer.remaining() >= channelCount * 2) {
                                var frame = 0.0
                                repeat(channelCount) { frame += outputBuffer.short / 32768.0 }
                                windowSamples += frame / channelCount
                                if (windowSamples.size >= framesPerWindow) analyzeWindow()
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            analyzeWindow()
            return result
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }

    private fun startPlayback() {
        val mediaPlayer = player ?: return
        pendingPlay = false
        try {
            if (!mediaPlayer.isPlaying) mediaPlayer.start()
            nextEventIndex = events.indexOfFirst { it.timeMs >= mediaPlayer.currentPosition }
                .let { if (it < 0) events.size else it }
            handler.removeCallbacks(playbackTick)
            handler.post(playbackTick)
        } catch (_: IllegalStateException) {
            pendingPlay = true
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    } catch (_: Throwable) {
        null
    }

    private companion object {
        const val ANALYSIS_WINDOW_MS = 20L
        const val CODEC_TIMEOUT_US = 10_000L
        const val PLAYBACK_TICK_MS = 20L
    }
}

class ThrottleGate(private val intervalMs: Long) {
    private var lastExecutionMs = Long.MIN_VALUE

    fun canExecute(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastExecutionMs != Long.MIN_VALUE && now - lastExecutionMs < intervalMs) return false
        lastExecutionMs = now
        return true
    }

    fun timeUntilNextMs(): Long {
        if (lastExecutionMs == Long.MIN_VALUE) return 0L
        return (intervalMs - (SystemClock.elapsedRealtime() - lastExecutionMs)).coerceAtLeast(0L)
    }
}
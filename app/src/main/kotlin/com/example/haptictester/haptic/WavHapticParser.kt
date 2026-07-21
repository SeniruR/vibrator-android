package com.example.haptictester.haptic

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

object WavHapticParser {
    private const val DEFAULT_WINDOW_SIZE_MS = 20L
    private const val MIN_INTENSITY = 18

    fun parse(
        context: Context,
        uri: Uri,
        windowSizeMs: Long = DEFAULT_WINDOW_SIZE_MS,
    ): HapticMapData {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            return parseStream(stream, windowSizeMs)
        } ?: throw IllegalArgumentException("Unable to open WAV file")
    }

    fun parseStream(
        input: InputStream,
        windowSizeMs: Long = DEFAULT_WINDOW_SIZE_MS,
    ): HapticMapData {
        val header = readFully(input, 12)
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if (riff != "RIFF" || wave != "WAVE") {
            throw IllegalArgumentException("Not a valid WAV file (expected RIFF/WAVE header)")
        }

        var sampleRate = 44_100
        var channels = 1
        var bitsPerSample = 16
        var audioFormat = 1
        var dataSize = 0
        var dataStartKnown = false

        while (true) {
            val chunkHeader = readFullyOrNull(input, 8) ?: break
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .int

            when (chunkId) {
                "fmt " -> {
                    val fmt = readFully(input, chunkSize)
                    val fmtBuffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    audioFormat = fmtBuffer.short.toInt() and 0xFFFF
                    channels = fmtBuffer.short.toInt() and 0xFFFF
                    sampleRate = fmtBuffer.int
                    fmtBuffer.int
                    fmtBuffer.short
                    bitsPerSample = fmtBuffer.short.toInt() and 0xFFFF
                }
                "data" -> {
                    dataSize = chunkSize
                    dataStartKnown = true
                    break
                }
                else -> skipFully(input, chunkSize)
            }

            if (chunkSize % 2 != 0) {
                input.read()
            }
        }

        if (!dataStartKnown) {
            throw IllegalArgumentException("WAV file is missing a data chunk")
        }
        if (audioFormat != 1) {
            throw IllegalArgumentException("Only PCM WAV files are supported (format=$audioFormat)")
        }
        if (bitsPerSample != 16) {
            throw IllegalArgumentException("Only 16-bit PCM WAV files are supported")
        }
        if (channels !in 1..2) {
            throw IllegalArgumentException("Unsupported channel count: $channels")
        }

        val bytesPerFrame = channels * (bitsPerSample / 8)
        if (bytesPerFrame <= 0) {
            throw IllegalArgumentException("Invalid WAV frame layout")
        }

        val window = windowSizeMs.coerceAtLeast(1L)
        val samplesPerWindow = ((sampleRate * window) / 1000L).coerceAtLeast(1L).toInt()

        data class WindowMetrics(
            var peak: Double = 0.0,
            var sumSquares: Double = 0.0,
            var sampleCount: Int = 0,
            var zeroCrossings: Int = 0,
            var prevSample: Double? = null,
        )

        val metricsByWindow = linkedMapOf<Long, WindowMetrics>()
        var currentWindowStartMs = 0L
        var samplesInCurrentWindow = 0
        var currentMetrics = WindowMetrics()
        var totalSamplesProcessed = 0L

        val buffer = ByteArray(8192)
        var bytesRemaining = dataSize
        while (bytesRemaining > 0) {
            val toRead = minOf(buffer.size, bytesRemaining)
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            bytesRemaining -= read

            var offset = 0
            while (offset + bytesPerFrame <= read) {
                val normalized = when (channels) {
                    1 -> readSample16(buffer, offset) / 32768.0
                    else -> {
                        val left = readSample16(buffer, offset) / 32768.0
                        val right = readSample16(buffer, offset + 2) / 32768.0
                        (left + right) / 2.0
                    }
                }

                val absSample = abs(normalized)
                currentMetrics.peak = maxOf(currentMetrics.peak, absSample)
                currentMetrics.sumSquares += normalized * normalized
                currentMetrics.sampleCount++

                val prev = currentMetrics.prevSample
                if (prev != null && ((prev >= 0.0 && normalized < 0.0) || (prev < 0.0 && normalized >= 0.0))) {
                    currentMetrics.zeroCrossings++
                }
                currentMetrics.prevSample = normalized

                samplesInCurrentWindow++
                totalSamplesProcessed++

                if (samplesInCurrentWindow >= samplesPerWindow) {
                    metricsByWindow[currentWindowStartMs] = currentMetrics
                    currentWindowStartMs += window
                    samplesInCurrentWindow = 0
                    currentMetrics = WindowMetrics()
                }

                offset += bytesPerFrame
            }
        }

        if (currentMetrics.sampleCount > 0) {
            metricsByWindow[currentWindowStartMs] = currentMetrics
        }

        if (metricsByWindow.isEmpty()) {
            throw IllegalArgumentException("WAV file does not contain any haptic samples")
        }

        val sortedStarts = metricsByWindow.keys.sorted()
        val envelopeValues = sortedStarts.map { startMs ->
            val m = metricsByWindow[startMs]!!
            val rms = if (m.sampleCount > 0) sqrt(m.sumSquares / m.sampleCount) else 0.0
            maxOf(m.peak, rms * sqrt(2.0))
        }
        val zcrValues = sortedStarts.map { startMs ->
            metricsByWindow[startMs]!!.zeroCrossings.toDouble()
        }

        // High-pass the envelope so steady sine carriers (A/C/D) don't read as always-on.
        val smoothRadius = 8
        val smoothedEnvelope = envelopeValues.mapIndexed { index, _ ->
            val from = maxOf(0, index - smoothRadius)
            val to = minOf(envelopeValues.size, index + smoothRadius + 1)
            envelopeValues.subList(from, to).average()
        }
        val highPassEnvelope = envelopeValues.mapIndexed { index, value ->
            maxOf(0.0, value - smoothedEnvelope[index] * 0.94)
        }

        // FM-only tracks: use changes in zero-crossing rate, not absolute level.
        val zcrDelta = zcrValues.mapIndexed { index, value ->
            if (index == 0) 0.0 else abs(value - zcrValues[index - 1])
        }

        val combined = highPassEnvelope.mapIndexed { index, hp ->
            (0.82 * hp + 0.18 * zcrDelta[index]).coerceAtLeast(0.0)
        }

        val sortedCombined = combined.sorted()
        val p50 = percentile(sortedCombined, 0.50)
        val p90 = percentile(sortedCombined, 0.90).coerceAtLeast(1e-6)
        val gate = p50 + (p90 - p50) * 0.30

        val track = buildMap<Long, Int> {
            sortedStarts.forEachIndexed { index, startMs ->
                val value = combined[index]
                if (value < gate) {
                    put(startMs, 0)
                    return@forEachIndexed
                }
                val normalized = ((value - gate) / (p90 - gate).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                val intensity = (normalized * 255.0).roundToInt().coerceIn(0, 255)
                put(startMs, if (intensity < MIN_INTENSITY) 0 else intensity)
            }
        }

        val durationMs = ((totalSamplesProcessed * 1000L) / sampleRate.coerceAtLeast(1)).coerceAtLeast(window)

        return HapticMapData(
            windowSizeMs = window,
            track = track,
            durationMs = durationMs.coerceAtLeast(window),
            format = HapticTrackFormat.WAV,
            useSustainedPlayback = false,
        )
    }

    private fun percentile(sortedValues: List<Double>, fraction: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val clamped = fraction.coerceIn(0.0, 1.0)
        val index = ((sortedValues.size - 1) * clamped).roundToInt()
        return sortedValues[index.coerceIn(0, sortedValues.lastIndex)]
    }

    private fun readSample16(buffer: ByteArray, offset: Int): Double {
        val low = buffer[offset].toInt() and 0xFF
        val high = buffer[offset + 1].toInt()
        return (high shl 8 or low).toShort().toDouble()
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) {
                throw IllegalArgumentException("Unexpected end of WAV file")
            }
            offset += read
        }
        return buffer
    }

    private fun readFullyOrNull(input: InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) return null
            offset += read
        }
        return buffer
    }

    private fun skipFully(input: InputStream, size: Int) {
        var remaining = size.toLong()
        val skipBuffer = ByteArray(4096)
        while (remaining > 0) {
            val toRead = minOf(skipBuffer.size.toLong(), remaining).toInt()
            val read = input.read(skipBuffer, 0, toRead)
            if (read < 0) break
            remaining -= read
        }
    }
}

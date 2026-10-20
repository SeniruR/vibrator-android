package com.example.haptictester.haptic

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlin.math.roundToInt

class VideoHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    private companion object {
        const val MIN_INTENSITY = 18
        const val ACCENT_FLOOR = 104
        const val ACCENT_RELEASE_FLOOR = 50
        const val ACCENT_RELEASE_WINDOWS = 3
        const val ACCENT_SHAPE_FLOOR_RATIO = 0.45f
        const val ACCENT_MIN_SEPARATION_MS = 240L
        const val ACCENT_MAX_DURATION_MS = 1_200L
        const val ACCENT_LOOKAHEAD_MS = 80L
        const val ACCENT_CATCHUP_MS = 80L
        const val ACCENT_SPIKE_MS = 55L
        const val PWM_PERIOD_MS = 20L
        const val BED_MAX_AMPLITUDE = 150
        const val BED_MIN_AMPLITUDE = 24
        const val BED_SCALE = 0.55f
        const val BED_BUCKET_SIZE = 24
        const val BED_CHUNK_MS = 10_000L
        const val BED_RESYNC_LEAD_MS = 1_000L
        const val BED_DRIFT_MS = 80L
    }

    private data class WavAccent(
        val peakMs: Long,
        val fireAtMs: Long,
        val endMs: Long,
        val levels: List<Int>,
    )

    private val tag = "VideoHapticController"
    private val handler = Handler(Looper.getMainLooper())
    private var hapticMap: HapticMapData? = null
    private var mapLabel: String? = null
    private var lastWindowStartMs = Long.MIN_VALUE
    private var scheduledRunnable: Runnable? = null
    private var scheduledWindowStartMs = Long.MIN_VALUE
    private var accents: List<WavAccent> = emptyList()
    private var nextAccentIndex = 0
    private var accentHoldUntilMs = Long.MIN_VALUE

    // The bed between accents is planned per window and played as one waveform.
    // Sampling it from the 20 ms position poll picked a different window, and so
    // a different level, on every play of a busy track.
    private var bedPlan: IntArray? = null
    private var bedPlanCap = -1
    private var bedStartPositionMs = Long.MIN_VALUE
    private var bedStartElapsedMs = 0L
    private var bedEndPositionMs = Long.MIN_VALUE
    private var bedChunkLimited = false

    fun loadWav(uri: Uri): String {
        val map = WavHapticParser.parse(context, uri)
        applyMap(map, resolveDisplayName(uri) ?: "Haptic WAV")
        return mapLabel ?: "Haptic WAV"
    }

    fun loadMapData(map: HapticMapData, label: String) {
        applyMap(map, label)
    }

    fun forceSyncAt(positionMs: Long, amplitudeCap: Int) {
        resetPlaybackState(positionMs)
        updatePlaybackPosition(positionMs, amplitudeCap)
    }

    fun updatePlaybackPosition(positionMs: Long, amplitudeCap: Int) {
        val map = hapticMap ?: return
        val position = positionMs.coerceAtLeast(0L)
        val windowSizeMs = map.windowSizeMs.coerceAtLeast(1L)
        val windowStartMs = (position / windowSizeMs) * windowSizeMs

        if (lastWindowStartMs != Long.MIN_VALUE && windowStartMs < lastWindowStartMs) {
            resetPlaybackState(position)
        }
        if (windowStartMs == lastWindowStartMs) return
        lastWindowStartMs = windowStartMs

        while (nextAccentIndex < accents.size && accents[nextAccentIndex].endMs < position - ACCENT_CATCHUP_MS) {
            nextAccentIndex++
        }

        val nextAccent = accents.getOrNull(nextAccentIndex)
        if (nextAccent != null && nextAccent.fireAtMs <= position + ACCENT_LOOKAHEAD_MS) {
            val cap = amplitudeCap.coerceIn(0, 255)
            if (cap > 0 && nextAccent.endMs >= position - ACCENT_CATCHUP_MS) {
                cancelScheduledPulse()
                stopBed()
                val waveform = accentWaveform(nextAccent, cap, windowSizeMs)
                val delayMs = (nextAccent.fireAtMs - position).coerceAtLeast(0L)
                // The bed must not cut an accent short, so hold for the whole pattern.
                accentHoldUntilMs = maxOf(nextAccent.endMs, nextAccent.fireAtMs + waveform.first.sum())
                val runnable = Runnable {
                    scheduledRunnable = null
                    // Firing cancels whatever bed waveform is playing; restart it after the hold.
                    bedStartPositionMs = Long.MIN_VALUE
                    bedEndPositionMs = Long.MIN_VALUE
                    hapticController.vibrateAccentPattern(waveform.first, waveform.second)
                    Log.d(
                        tag,
                        "WAV accent fireAtMs=${nextAccent.fireAtMs} peakMs=${nextAccent.peakMs} " +
                            "endMs=${nextAccent.endMs} " +
                            "steps=${waveform.first.size} cap=$cap",
                    )
                }
                scheduledRunnable = runnable
                scheduledWindowStartMs = windowStartMs
                if (delayMs == 0L) handler.post(runnable) else handler.postDelayed(runnable, delayMs)
            }
            nextAccentIndex++
            return
        }

        if (position <= accentHoldUntilMs) return
        // A jump in the reported position can pass the hold before the accent fires.
        if (scheduledRunnable != null) return
        accentHoldUntilMs = Long.MIN_VALUE

        updateBed(map, position, amplitudeCap.coerceIn(0, 255))
    }

    private fun detectAccents(map: HapticMapData): List<WavAccent> {
        val window = map.windowSizeMs.coerceAtLeast(1L)
        val entries = (map.envelope.ifEmpty { map.track }).toSortedMap().entries.toList()
        if (entries.size < 3) return emptyList()

        val regions = mutableListOf<Pair<Int, Int>>()
        var regionStartIndex = -1
        var regionPeakIndex = -1
        var belowReleaseCount = 0

        fun finishRegion() {
            if (regionPeakIndex < 0) return
            val previousRegion = regions.lastOrNull()
            val previousPeak = previousRegion?.second
            if (previousPeak != null && entries[regionPeakIndex].key - entries[previousPeak].key < ACCENT_MIN_SEPARATION_MS) {
                if (entries[regionPeakIndex].value > entries[previousPeak].value) {
                    regions[regions.lastIndex] = regionStartIndex to regionPeakIndex
                }
            } else {
                regions += regionStartIndex to regionPeakIndex
            }
            regionStartIndex = -1
            regionPeakIndex = -1
            belowReleaseCount = 0
        }

        for (index in entries.indices) {
            val level = entries[index].value
            if (regionPeakIndex < 0) {
                if (level >= ACCENT_FLOOR) {
                    regionStartIndex = index
                    regionPeakIndex = index
                }
                continue
            }

            if (level > entries[regionPeakIndex].value) regionPeakIndex = index
            belowReleaseCount = if (level < ACCENT_RELEASE_FLOOR) belowReleaseCount + 1 else 0
            val regionTooLong = entries[index].key - entries[regionStartIndex].key >= ACCENT_MAX_DURATION_MS
            if (belowReleaseCount >= ACCENT_RELEASE_WINDOWS || regionTooLong) {
                finishRegion()
                if (level >= ACCENT_FLOOR) {
                    regionStartIndex = index
                    regionPeakIndex = index
                }
            }
        }
        finishRegion()

        return regions.mapNotNull { (regionStartIndex, peakIndex) ->
            val peakMs = entries[peakIndex].key
            val peakLevel = entries[peakIndex].value
            val shapeFloor = (peakLevel * ACCENT_SHAPE_FLOOR_RATIO).roundToInt().coerceAtLeast(ACCENT_FLOOR / 2)
            val fireAtMs = entries[regionStartIndex].key
            val startIndex = regionStartIndex
            var endIndex = peakIndex
            while (endIndex < entries.lastIndex &&
                entries[endIndex + 1].key - peakMs <= ACCENT_MAX_DURATION_MS &&
                entries[endIndex + 1].value >= shapeFloor
            ) {
                endIndex++
            }
            if (startIndex > endIndex) return@mapNotNull null
            WavAccent(
                peakMs = peakMs,
                fireAtMs = fireAtMs,
                endMs = entries[endIndex].key + window,
                levels = entries.subList(startIndex, endIndex + 1).map { it.value.coerceIn(0, 255) },
            )
        }
    }

    private fun accentWaveform(accent: WavAccent, cap: Int, windowSizeMs: Long): Pair<LongArray, IntArray> {
        val peak = accent.levels.maxOrNull()?.coerceAtLeast(1) ?: 1
        val strength = (peak * cap / 255).coerceIn(1, cap)
        val widthMs = accent.levels.size * windowSizeMs
        if (widthMs <= 140L) {
            if (hapticController.hasAmplitudeControl()) {
                return longArrayOf(0L, ACCENT_SPIKE_MS) to intArrayOf(0, strength)
            }
            val onMs = (ACCENT_SPIKE_MS * strength / cap).toLong().coerceIn(10L, ACCENT_SPIKE_MS)
            val timings = if (onMs < ACCENT_SPIKE_MS) {
                longArrayOf(0L, onMs, ACCENT_SPIKE_MS - onMs)
            } else {
                longArrayOf(0L, onMs)
            }
            val amplitudes = if (timings.size == 3) intArrayOf(0, 255, 0) else intArrayOf(0, 255)
            return timings to amplitudes
        }

        val stepWindows = (40L / windowSizeMs).toInt().coerceAtLeast(1)
        val timings = mutableListOf(0L)
        val amplitudes = mutableListOf(0)
        var index = 0
        while (index < accent.levels.size) {
            val stopIndex = minOf(index + stepWindows, accent.levels.size)
            val level = accent.levels.subList(index, stopIndex).maxOrNull() ?: 0
            val durationMs = ((stopIndex - index) * windowSizeMs).coerceAtLeast(windowSizeMs)
            val relative = (level.toFloat() / peak).coerceIn(0f, 1f)
            if (hapticController.hasAmplitudeControl()) {
                timings += durationMs
                amplitudes += (strength * relative).roundToInt().coerceAtLeast(1)
            } else if (relative < 0.15f) {
                timings += durationMs
                amplitudes += 0
            } else {
                val onMs = (durationMs * relative).toLong().coerceIn(minOf(8L, durationMs), durationMs)
                timings += onMs
                amplitudes += 255
                val offMs = durationMs - onMs
                if (offMs > 0L) {
                    timings += offMs
                    amplitudes += 0
                }
            }
            index = stopIndex
        }
        return timings.toLongArray() to amplitudes.toIntArray()
    }

    private fun updateBed(map: HapticMapData, positionMs: Long, amplitudeCap: Int) {
        if (bedPlan == null || bedPlanCap != amplitudeCap) {
            bedPlan = buildBedPlan(map, amplitudeCap)
            bedPlanCap = amplitudeCap
            bedStartPositionMs = Long.MIN_VALUE
        }

        if (bedStartPositionMs != Long.MIN_VALUE) {
            val expectedMs = bedStartPositionMs + (SystemClock.elapsedRealtime() - bedStartElapsedMs)
            val inSync = kotlin.math.abs(positionMs - expectedMs) <= BED_DRIFT_MS
            val nearEnd = bedChunkLimited && positionMs >= bedEndPositionMs - BED_RESYNC_LEAD_MS
            if (inSync && !nearEnd) return
        }

        startBedAt(map, positionMs)
    }

    /**
     * Bed level per window, 0 where it is off. Same rules as sampling the poll
     * (scale, floor, and a new level only when its bucket changes), applied to
     * every window in order and silent wherever an accent owns the motor.
     */
    private fun buildBedPlan(map: HapticMapData, amplitudeCap: Int): IntArray {
        val windowSizeMs = map.windowSizeMs.coerceAtLeast(1L)
        val windowCount = ((map.durationMs + windowSizeMs - 1) / windowSizeMs).toInt().coerceAtLeast(1)
        val plan = IntArray(windowCount)
        var accentIndex = 0
        var bucket = -1
        var heldLevel = 0
        for (index in 0 until windowCount) {
            val startMs = index * windowSizeMs
            while (accentIndex < accents.size && accents[accentIndex].endMs < startMs) accentIndex++
            val accent = accents.getOrNull(accentIndex)
            val inAccent = accent != null &&
                startMs >= accent.fireAtMs - ACCENT_LOOKAHEAD_MS &&
                startMs <= accent.endMs
            if (inAccent) {
                bucket = -1
                continue
            }
            val source = map.track[startMs]?.coerceIn(0, 255) ?: 0
            val level = minOf((source * BED_SCALE).roundToInt(), BED_MAX_AMPLITUDE, amplitudeCap)
            if (level < BED_MIN_AMPLITUDE) {
                bucket = -1
                continue
            }
            val levelBucket = level / BED_BUCKET_SIZE
            if (levelBucket != bucket) {
                bucket = levelBucket
                heldLevel = level
            }
            plan[index] = heldLevel
        }
        return plan
    }

    private fun startBedAt(map: HapticMapData, positionMs: Long) {
        val plan = bedPlan ?: return
        val windowSizeMs = map.windowSizeMs.coerceAtLeast(1L)
        val nextAccent = accents.getOrNull(nextAccentIndex)
        val accentLimitMs = nextAccent?.let { it.fireAtMs - ACCENT_LOOKAHEAD_MS } ?: Long.MAX_VALUE
        val chunkEndMs = positionMs + BED_CHUNK_MS
        val endMs = minOf(map.durationMs, accentLimitMs, chunkEndMs)
        val amplitudeControl = hapticController.hasAmplitudeControl()

        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        fun append(durationMs: Long, amplitude: Int) {
            if (durationMs <= 0L) return
            val last = amplitudes.lastIndex
            if (last >= 0 && amplitudes[last] == amplitude) {
                timings[last] = timings[last] + durationMs
            } else {
                timings += durationMs
                amplitudes += amplitude
            }
        }

        var cursorMs = positionMs
        var index = (positionMs / windowSizeMs).toInt()
        while (cursorMs < endMs) {
            val windowStartMs = index * windowSizeMs
            val windowEndMs = minOf(windowStartMs + windowSizeMs, endMs)
            val level = plan.getOrNull(index) ?: 0
            if (level <= 0) {
                append(windowEndMs - cursorMs, 0)
            } else if (amplitudeControl) {
                append(windowEndMs - cursorMs, level)
            } else {
                val onMs = ((level / 255.0) * PWM_PERIOD_MS).toLong().coerceIn(4L, PWM_PERIOD_MS - 4L)
                val onEndMs = minOf(windowStartMs + onMs, windowEndMs)
                if (cursorMs < onEndMs) {
                    append(onEndMs - cursorMs, 255)
                    cursorMs = onEndMs
                }
                append(windowEndMs - cursorMs, 0)
            }
            cursorMs = windowEndMs
            index++
        }

        bedStartPositionMs = positionMs
        bedStartElapsedMs = SystemClock.elapsedRealtime()
        bedEndPositionMs = endMs
        bedChunkLimited = endMs == chunkEndMs && chunkEndMs < minOf(map.durationMs, accentLimitMs)

        if (amplitudes.none { it > 0 }) {
            hapticController.cancel()
            return
        }
        hapticController.vibrateWaveform(
            timings = longArrayOf(0L) + timings.toLongArray(),
            amplitudes = intArrayOf(0) + amplitudes.toIntArray(),
            repeat = -1,
        )
    }

    private fun stopBed() {
        if (bedStartPositionMs == Long.MIN_VALUE) return
        bedStartPositionMs = Long.MIN_VALUE
        bedEndPositionMs = Long.MIN_VALUE
        hapticController.cancel()
    }

    fun stop() {
        resetPlaybackState()
        hapticController.cancel()
    }

    fun release() {
        stop()
        hapticMap = null
        mapLabel = null
    }

    fun getMapLabel(): String? = mapLabel

    fun getWindowCount(): Int = hapticMap?.track?.size ?: 0

    fun getWindowSizeMs(): Long = hapticMap?.windowSizeMs ?: 0L

    fun getDurationMs(): Long = hapticMap?.durationMs ?: 0L

    fun getTrackFormat(): HapticTrackFormat? = hapticMap?.format

    fun hasLoadedTrack(): Boolean = hapticMap != null

    private fun applyMap(map: HapticMapData, label: String) {
        hapticMap = map
        mapLabel = label
        accents = detectAccents(map)
        bedPlan = null
        bedPlanCap = -1
        resetPlaybackState()
        val activeWindows = map.track.values.count { it > 0 }
        Log.d(
            tag,
            "Loaded WAV label=$label windows=${map.track.size} activeWindows=$activeWindows " +
                "accents=${accents.size} windowSizeMs=${map.windowSizeMs} durationMs=${map.durationMs}",
        )
    }

    private fun resetPlaybackState(positionMs: Long? = null) {
        lastWindowStartMs = Long.MIN_VALUE
        accentHoldUntilMs = Long.MIN_VALUE
        bedStartPositionMs = Long.MIN_VALUE
        bedEndPositionMs = Long.MIN_VALUE
        hapticController.cancel()
        nextAccentIndex = if (positionMs == null) {
            0
        } else {
            accents.indexOfFirst { it.endMs >= positionMs - ACCENT_CATCHUP_MS }
                .let { if (it < 0) accents.size else it }
        }
        cancelScheduledPulse()
    }

    private fun cancelScheduledPulse() {
        scheduledRunnable?.let { handler.removeCallbacks(it) }
        scheduledRunnable = null
        scheduledWindowStartMs = Long.MIN_VALUE
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (throwable: Throwable) {
            Log.w(tag, "resolveDisplayName failed", throwable)
            null
        }
    }
}
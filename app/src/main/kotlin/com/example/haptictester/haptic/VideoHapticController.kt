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
        const val BED_HOLD_MS = 4_500L
        const val BED_REFRESH_MS = 4_000L
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
    private var bedBucket = -1
    private var lastBedRefreshMs = 0L

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
                accentHoldUntilMs = nextAccent.endMs
                val runnable = Runnable {
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
        accentHoldUntilMs = Long.MIN_VALUE

        val sourceIntensity = map.track[windowStartMs]?.coerceIn(0, 255) ?: 0
        val level = minOf(
            (sourceIntensity * BED_SCALE).roundToInt(),
            BED_MAX_AMPLITUDE,
            amplitudeCap.coerceIn(0, 255),
        )
        if (level < BED_MIN_AMPLITUDE) {
            stopBed()
            return
        }

        updateBed(level)
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

    private fun updateBed(level: Int) {
        val bucket = level / BED_BUCKET_SIZE
        val now = SystemClock.elapsedRealtime()
        if (bucket == bedBucket && now - lastBedRefreshMs < BED_REFRESH_MS) return
        bedBucket = bucket
        lastBedRefreshMs = now
        if (hapticController.hasAmplitudeControl()) {
            hapticController.vibrateOneShot(BED_HOLD_MS, level)
            return
        }

        val onMs = ((level / 255.0) * PWM_PERIOD_MS).toLong().coerceIn(4L, PWM_PERIOD_MS - 4L)
        hapticController.vibrateWaveform(
            timings = longArrayOf(0L, onMs, PWM_PERIOD_MS - onMs),
            amplitudes = intArrayOf(0, 255, 0),
            repeat = 0,
        )
    }

    private fun stopBed() {
        if (bedBucket < 0) return
        bedBucket = -1
        lastBedRefreshMs = 0L
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
        bedBucket = -1
        lastBedRefreshMs = 0L
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
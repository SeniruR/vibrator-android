package com.example.haptictester.haptic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import org.json.JSONObject

class VideoHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    private val tag = "VideoHapticController"
    private var hapticMap: HapticMapData? = null
    private var mapLabel: String? = null
    private var lastWindowStartMs: Long = Long.MIN_VALUE
    private var lastEffectiveAmplitude: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledRunnable: Runnable? = null
    private var scheduledWindowStartMs: Long = Long.MIN_VALUE
    private val minIntensityJson = 16
    private val minIntensityWav = 18
    private val wavChangeThreshold = 14

    fun loadMap(uri: Uri): String {
        val map = parseJsonMap(uri)
        applyMap(map, resolveDisplayName(uri) ?: "Haptic JSON")
        return mapLabel ?: "Haptic map"
    }

    fun loadWav(uri: Uri): String {
        val map = WavHapticParser.parse(context, uri)
        applyMap(map, resolveDisplayName(uri) ?: "Haptic WAV")
        return mapLabel ?: "Haptic WAV"
    }

    fun loadMapData(map: HapticMapData, label: String) {
        applyMap(map, label)
    }

    /** Switch track mid-playback and immediately sync haptics to the current video position. */
    fun forceSyncAt(positionMs: Long, amplitudeCap: Int) {
        resetPlaybackState()
        updatePlaybackPosition(positionMs.coerceAtLeast(0L), amplitudeCap)
    }

    fun updatePlaybackPosition(positionMs: Long, amplitudeCap: Int) {
        val map = hapticMap ?: return
        val windowSizeMs = map.windowSizeMs.coerceAtLeast(1L)
        val windowStartMs = (positionMs / windowSizeMs) * windowSizeMs
        val minIntensity = if (map.format == HapticTrackFormat.WAV) minIntensityWav else minIntensityJson

        if (windowStartMs < lastWindowStartMs) {
            resetPlaybackState()
        }

        if (windowStartMs == lastWindowStartMs) {
            return
        }
        lastWindowStartMs = windowStartMs

        val sourceIntensity = map.track[windowStartMs]?.coerceIn(0, 255) ?: 0
        val effectiveAmplitude = minOf(sourceIntensity, amplitudeCap.coerceIn(0, 255))

        if (effectiveAmplitude < minIntensity) {
            if (lastEffectiveAmplitude >= minIntensity) {
                cancelScheduledPulse()
                hapticController.cancel()
            }
            lastEffectiveAmplitude = 0
            return
        }

        if (map.format == HapticTrackFormat.WAV) {
            val delta = abs(effectiveAmplitude - lastEffectiveAmplitude)
            val isOnset = lastEffectiveAmplitude < minIntensity
            if (!isOnset && delta < wavChangeThreshold) {
                return
            }
        } else if (effectiveAmplitude == lastEffectiveAmplitude) {
            return
        }

        lastEffectiveAmplitude = effectiveAmplitude

        val pulseDurationMs = when (map.format) {
            HapticTrackFormat.WAV -> (windowSizeMs * 0.85).toLong().coerceIn(14L, 60L)
            HapticTrackFormat.JSON -> (windowSizeMs * 0.75).toLong().coerceIn(15L, 180L)
        }
        val windowCenterMs = windowStartMs + windowSizeMs / 2
        val delayMs = (windowCenterMs - positionMs).coerceAtLeast(0L)

        if (scheduledWindowStartMs != windowStartMs) {
            cancelScheduledPulse()
        }

        val runnable = Runnable {
            firePulse(pulseDurationMs, effectiveAmplitude)
        }

        scheduledRunnable = runnable
        scheduledWindowStartMs = windowStartMs
        if (delayMs <= 15L) {
            handler.post(runnable)
        } else {
            handler.postDelayed(runnable, delayMs)
        }
    }

    private fun firePulse(durationMs: Long, amplitude: Int) {
        if (amplitude <= 0) return

        hapticController.cancel()

        if (hapticController.hasAmplitudeControl()) {
            hapticController.vibrateOneShot(durationMs, amplitude)
            return
        }

        val onMs = ((amplitude / 255.0) * durationMs).toLong().coerceAtLeast(12L)
        val gapMs = (durationMs * 2 - onMs).coerceAtLeast(20L)
        hapticController.vibrateWaveform(
            timings = longArrayOf(0L, onMs, gapMs),
            amplitudes = intArrayOf(0, 255, 0),
            repeat = -1,
        )
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
        resetPlaybackState()
        val activeWindows = map.track.values.count { it > 0 }
        Log.d(
            tag,
            "Loaded haptic track label=$label format=${map.format} windows=${map.track.size} " +
                "activeWindows=$activeWindows windowSizeMs=${map.windowSizeMs} durationMs=${map.durationMs}",
        )
    }

    private fun resetPlaybackState() {
        lastWindowStartMs = Long.MIN_VALUE
        lastEffectiveAmplitude = 0
        cancelScheduledPulse()
    }

    private fun cancelScheduledPulse() {
        scheduledRunnable?.let { handler.removeCallbacks(it) }
        scheduledRunnable = null
        scheduledWindowStartMs = Long.MIN_VALUE
    }

    private fun parseJsonMap(uri: Uri): HapticMapData {
        val jsonText = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            reader.readText()
        } ?: throw IllegalArgumentException("Unable to open haptic map")

        val root = JSONObject(jsonText)
        val windowSizeMs = root.optLong("window_size_ms", 40L).coerceAtLeast(1L)
        val trackObject = root.optJSONObject("track") ?: JSONObject()

        val track = mutableMapOf<Long, Int>()
        val keys = trackObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val rawWindowStartMs = key.toLongOrNull() ?: continue
            val normalizedWindowStartMs = ((rawWindowStartMs / windowSizeMs) * windowSizeMs)
            val intensity = trackObject.optInt(key, 0).coerceIn(0, 255)
            val previous = track[normalizedWindowStartMs] ?: 0
            track[normalizedWindowStartMs] = maxOf(previous, intensity)
        }

        val normalizedTrack = buildMap<Long, Int> {
            val maxWindow = track.keys.maxOrNull() ?: 0L
            var cursor = 0L
            while (cursor <= maxWindow) {
                put(cursor, track[cursor] ?: 0)
                cursor += windowSizeMs
            }
        }

        if (normalizedTrack.isEmpty()) {
            throw IllegalArgumentException("Haptic map does not contain any windows")
        }

        val durationMs = normalizedTrack.keys.maxOrNull()?.plus(windowSizeMs) ?: windowSizeMs

        return HapticMapData(
            windowSizeMs = windowSizeMs,
            track = normalizedTrack,
            durationMs = durationMs,
            format = HapticTrackFormat.JSON,
            useSustainedPlayback = false,
        )
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
                if (index >= 0 && cursor.moveToFirst()) {
                    cursor.getString(index)
                } else {
                    null
                }
            }
        } catch (throwable: Throwable) {
            Log.w(tag, "resolveDisplayName failed", throwable)
            null
        }
    }
}

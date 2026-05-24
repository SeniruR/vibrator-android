package com.example.haptictester.haptic

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

class VideoHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    data class HapticMap(
        val windowSizeMs: Long,
        val track: Map<Long, Int>,
    )

    private val tag = "VideoHapticController"
    private var hapticMap: HapticMap? = null
    private var mapLabel: String? = null
    private var lastWindowStartMs: Long = Long.MIN_VALUE
    private var lastEffectiveAmplitude: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledRunnable: Runnable? = null
    private var scheduledWindowStartMs: Long = Long.MIN_VALUE
    // Minimum intensity to trigger a vibration (filters tiny pre-spikes)
    private val MIN_INTENSITY = 16

    fun loadMap(uri: Uri): String {
        val map = parseMap(uri)
        hapticMap = map
        mapLabel = resolveDisplayName(uri)
        lastWindowStartMs = Long.MIN_VALUE
        Log.d(tag, "Loaded haptic map label=${mapLabel ?: "unknown"} windows=${map.track.size} windowSizeMs=${map.windowSizeMs}")
        return mapLabel ?: "Haptic map"
    }

    fun updatePlaybackPosition(positionMs: Long, amplitudeCap: Int) {
        val map = hapticMap ?: return
        val windowSizeMs = map.windowSizeMs.coerceAtLeast(1L)
        val windowStartMs = (positionMs / windowSizeMs) * windowSizeMs
        if (windowStartMs == lastWindowStartMs) {
            return
        }
        lastWindowStartMs = windowStartMs

        val sourceIntensity = map.track[windowStartMs]?.coerceIn(0, 255) ?: 0
        val effectiveAmplitude = minOf(sourceIntensity, amplitudeCap.coerceIn(0, 255))

        // If amplitude didn't change, don't restart vibration (prevents constant restart/continuous feel)
        if (effectiveAmplitude == lastEffectiveAmplitude) {
            return
        }

        // If amplitude is below minimum, treat as zero (filter noise)
        if (effectiveAmplitude < MIN_INTENSITY) {
            lastEffectiveAmplitude = 0
            // cancel any scheduled or running vibration
            scheduledRunnable?.let { handler.removeCallbacks(it) }
            scheduledRunnable = null
            scheduledWindowStartMs = Long.MIN_VALUE
            hapticController.cancel()
            return
        }

        // If amplitude is zero, cancel any ongoing vibration
        if (effectiveAmplitude <= 0) {
            lastEffectiveAmplitude = 0
            hapticController.cancel()
            return
        }
        lastEffectiveAmplitude = effectiveAmplitude

        // Pulse duration scaled to the window; allow longer pulses for perceptible taps
        val pulseDurationMs = (windowSizeMs * 0.75).toLong().coerceIn(15L, 180L)

        // Schedule vibration at the center of the window to better align with detected events
        val windowCenterMs = windowStartMs + windowSizeMs / 2
        val delayMs = (windowCenterMs - positionMs).coerceAtLeast(0L)

        // If a previous window is already scheduled, cancel it (we're moving forward)
        if (scheduledWindowStartMs != windowStartMs) {
            scheduledRunnable?.let { handler.removeCallbacks(it) }
            scheduledRunnable = null
            scheduledWindowStartMs = Long.MIN_VALUE
        }

        val runnable = Runnable {
            // Re-check amplitude in case it changed before run
            val currentAmp = lastEffectiveAmplitude
            if (currentAmp <= 0) return@Runnable

            // If device supports amplitude control (LRA-like), use one-shot with amplitude
            if (hapticController.hasAmplitudeControl()) {
                hapticController.vibrateOneShot(pulseDurationMs, currentAmp)
                return@Runnable
            }

            // Fallback for ERM motors: approximate strength by duty cycle
            val maxOn = pulseDurationMs
            val onMs = ((currentAmp / 255.0) * maxOn).toLong().coerceAtLeast(12L)
            val gapMs = (maxOn * 2 - onMs).coerceAtLeast(20L)
            val timings = longArrayOf(0L, onMs, gapMs)
            val amps = intArrayOf(0, 255, 0)
            hapticController.vibrateWaveform(timings, amps, -1)
        }

        scheduledRunnable = runnable
        scheduledWindowStartMs = windowStartMs
        if (delayMs <= 20L) {
            handler.post(runnable)
        } else {
            handler.postDelayed(runnable, delayMs)
        }
    }

    fun stop() {
        lastWindowStartMs = Long.MIN_VALUE
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

    private fun parseMap(uri: Uri): HapticMap {
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
            // Keep strongest amplitude when multiple raw points collapse into one playback bucket.
            track[normalizedWindowStartMs] = maxOf(previous, intensity)
        }

        val normalizedTrack = buildMap<Long, Int> {
            // Fill missing windows with zeros so playback has deterministic lookups.
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

        return HapticMap(windowSizeMs = windowSizeMs, track = normalizedTrack)
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
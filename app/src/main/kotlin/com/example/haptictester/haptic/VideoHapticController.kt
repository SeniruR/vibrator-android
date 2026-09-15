package com.example.haptictester.haptic

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import kotlin.math.abs
import org.json.JSONObject

class VideoHapticController(
    private val context: Context,
    private val hapticController: HapticController,
) {
    private companion object {
        /** Duty-cycle period used to fake amplitude on motors without it. */
        const val PWM_PERIOD_MS = 20L

        /** Length of a full-strength hold before it must be refreshed. */
        const val MAX_SUSTAIN_MS = 5_000L

        val IMPULSIVE_CATEGORIES = setOf("explosion", "gunshot")
        val RUMBLE_CATEGORIES = setOf("vehicle", "weather")

        /** Start the tap slightly before peak so the burst covers the flash. */
        const val HIT_LEAD_MS = 40L
        /** ERM: short lead — long lead made the burst end as the flash appeared. */
        const val HIT_LEAD_ERM_MS = 70L
        /** Duration when the device can set amplitude. */
        const val HIT_DURATION_MS = 100L
        /** Solid ON spanning the flash (spin-up + visible bang). */
        const val HIT_ERM_DURATION_MS = 200L
        /** Keep WAV path from canceling while the punch is still running. */
        const val HIT_HOLD_MS = 220L
        const val HIT_LOOKAHEAD_MS = 60L
        const val HIT_CATCHUP_MS = 100L

        /** Soft engine/rain bed while inside a sustained event span. */
        const val RUMBLE_AMPLITUDE = 72
        const val RUMBLE_ERM_AMPLITUDE = 90

        /**
         * How far around a peak the loaded track is searched for its own accent.
         * Wide enough to measure a blast slope, narrow enough not to steal the
         * next volley shot.
         */
        const val ACCENT_LOOKUP_RADIUS_MS = 160L
        const val SPIKE_WIDTH_MS = 50L
        const val SLOPE_WIDTH_MS = 180L
        const val HIT_SPIKE_MS = 55L
        const val HIT_SLOPE_MS = 220L
        /** Leave a gap so a decaying tail cannot swallow the next muzzle flash. */
        const val EVENT_GAP_MS = 80L
        const val MAX_BLAST_MS = 1_200L
        const val SPIKE_SPAN_MS = 140L
        const val DECAY_FLOOR = 18
        const val SOLID_REL = 0.38f
        const val PWM_STEP_MS = 40L
        const val PWM_MIN_ON_MS = 12L

        /** Accent length range on ERMs: a weak accent is a shorter punch. */
        const val HIT_ERM_MIN_DURATION_MS = 80L
        const val HIT_ERM_MAX_DURATION_MS = 240L

        /** Bed between accents. Capped so it can never read as a fire hit. */
        const val TEXTURE_SCALE = 0.55
        const val TEXTURE_MAX_AMPLITUDE = 150
        const val TEXTURE_MIN_AMPLITUDE = 30
        /** Coarse steps: restarting the motor on every tiny change reads as taps. */
        const val TEXTURE_BUCKET_SIZE = 28
    }

    private val tag = "VideoHapticController"
    private var hapticMap: HapticMapData? = null
    private var mapLabel: String? = null
    private var lastWindowStartMs: Long = Long.MIN_VALUE
    private var lastEffectiveAmplitude: Int = 0
    private var eventTriggerMode: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private var scheduledRunnable: Runnable? = null
    private var scheduledWindowStartMs: Long = Long.MIN_VALUE
    private val minIntensityJsonDefault = 16
    private val minIntensityWavDefault = 18
    private val wavChangeThresholdDefault = 14

    private var lastDebugLogMs: Long = 0L
    private val debugLogIntervalMs: Long = 300L

    /**
     * Level currently held by a looping vibration, or -1 when nothing is held.
     *
     * The pipeline's continuous layer produces long runs of active windows. Each
     * window used to cancel the motor and fire a fresh burst, which reads as a
     * string of separate taps instead of a rumble, so instead a looping effect is
     * started once and left running until the level changes materially.
     */
    private var sustainedBucket: Int = -1

    /** Level held by the between-accent bed, or -1 when the bed is silent. */
    private var textureBucket: Int = -1
    private var lastTextureWindowMs: Long = Long.MIN_VALUE
    /** Fraction of envelope windows that are “on”; character of this algorithm. */
    private var trackDensity: Float = 0f

    /** Quantisation of intensity into levels that are actually distinguishable. */
    private fun amplitudeBucket(amplitude: Int): Int = amplitude / 16

    private var pipelineEvents: List<PipelineEvent> = emptyList()
    private var pipelineEventsLabel: String? = null
    private var nextPipelineEventIndex: Int = 0
    private var nextHitIndex: Int = 0
    private var lastPlaybackPositionMs: Long = -1L
    private var hitHoldUntilElapsedMs: Long = 0L
    private val impulsiveHits: List<PipelineEvent>
        get() = pipelineEvents.filter { it.category.lowercase() in IMPULSIVE_CATEGORIES }

    private val rumbleSpans: List<PipelineEvent>
        get() = pipelineEvents.filter { it.category.lowercase() in RUMBLE_CATEGORIES }

    fun setEventTriggerMode(enabled: Boolean) {
        if (enabled == eventTriggerMode) return
        eventTriggerMode = enabled
        resetPlaybackState()
    }

    fun loadMap(uri: Uri): String {
        val map = parseJsonMap(uri)
        applyMap(map, resolveDisplayName(uri) ?: "Haptic JSON")
        return mapLabel ?: "Haptic map"
    }

    fun loadWav(uri: Uri): String {
        val map = WavHapticParser.parse(context, uri, eventTriggerMode = eventTriggerMode)
        applyMap(map, resolveDisplayName(uri) ?: "Haptic WAV")
        return mapLabel ?: "Haptic WAV"
    }

    fun loadMapData(map: HapticMapData, label: String) {
        applyMap(map, label)
    }

    fun loadPipelineEvents(uri: Uri): String {
        val events = PipelineEventsParser.parse(context, uri)
        return applyPipelineEvents(events, resolveDisplayName(uri) ?: "events.json")
    }

    fun loadPipelineEventsJson(jsonText: String, label: String = "events.json"): String {
        return applyPipelineEvents(PipelineEventsParser.parseJson(jsonText), label)
    }

    private fun applyPipelineEvents(events: List<PipelineEvent>, label: String): String {
        pipelineEvents = events
        pipelineEventsLabel = label
        resetPlaybackState()
        val firstHit = events.firstOrNull { it.category.lowercase() in IMPULSIVE_CATEGORIES }?.peakMs
        Log.d(
            tag,
            "Loaded pipeline events count=${events.size} label=$pipelineEventsLabel firstHitMs=$firstHit",
        )
        return pipelineEventsLabel ?: "events.json"
    }

    fun clearPipelineEvents() {
        pipelineEvents = emptyList()
        pipelineEventsLabel = null
        resetPipelineEventState()
    }

    fun hasPipelineEvents(): Boolean = pipelineEvents.isNotEmpty()

    fun getPipelineEventCount(): Int = pipelineEvents.size

    /** Switch track mid-playback and immediately sync haptics to the current video position. */
    fun forceSyncAt(positionMs: Long, amplitudeCap: Int) {
        val pos = positionMs.coerceAtLeast(0L)
        if (shouldUsePipelineEventPeaks()) {
            lastWindowStartMs = Long.MIN_VALUE
            lastEffectiveAmplitude = 0
            cancelScheduledPulse()
            lastPlaybackPositionMs = pos
            nextPipelineEventIndex = indexForPosition(pos)
            nextHitIndex = indexForHit(pos)
            updatePlaybackFromPipelineEvents(pos, amplitudeCap)
            return
        }
        resetPlaybackState()
        if (pipelineEvents.isNotEmpty()) {
            lastPlaybackPositionMs = pos
            nextPipelineEventIndex = indexForPosition(pos)
            nextHitIndex = indexForHit(pos)
        }
        updatePlaybackPosition(pos, amplitudeCap)
    }

    fun updatePlaybackPosition(positionMs: Long, amplitudeCap: Int) {
        val pos = positionMs.coerceAtLeast(0L)
        if (shouldUsePipelineEventPeaks()) {
            updatePlaybackFromPipelineEvents(pos, amplitudeCap)
            return
        }

        val map = hapticMap ?: return
        maybeFireImpulsiveHits(pos, amplitudeCap)
        lastPlaybackPositionMs = pos
        if (SystemClock.elapsedRealtime() < hitHoldUntilElapsedMs) {
            return
        }

        // Event-Trigger + explosion/gunshot peaks: events.json owns accent
        // *timing* (WAV accents land 150-350 ms late). Algorithm differences
        // live in the hit shape only. Driving the WAV as a between-hit bed on
        // ERMs without amplitude control restarts the motor every 20 ms and
        // reads as constant noise.
        if (eventTriggerMode && impulsiveHits.isNotEmpty()) {
            maybeLogPipelinePeakReference(pos)
            if (lastEffectiveAmplitude > 0 || textureBucket >= 0 || sustainedBucket >= 0) {
                cancelScheduledPulse()
                stopTexture()
                hapticController.cancel()
                sustainedBucket = -1
                lastEffectiveAmplitude = 0
            }
            return
        }

        maybeLogPipelinePeakReference(pos)
        val windowSizeMs = map.windowSizeMs.coerceAtLeast(1L)
        val windowStartMs = (pos / windowSizeMs) * windowSizeMs
        // Event-Trigger without impulsive events: accents only from WAV (high floor).
        val minIntensityWav = if (eventTriggerMode) 24 else minIntensityWavDefault
        val wavChangeThreshold = if (eventTriggerMode) 0 else wavChangeThresholdDefault
        val minIntensityJson = if (eventTriggerMode) 1 else minIntensityJsonDefault
        val minIntensity = if (map.format == HapticTrackFormat.WAV) minIntensityWav else minIntensityJson

        if (windowStartMs < lastWindowStartMs) {
            resetPlaybackState()
        }

        if (windowStartMs == lastWindowStartMs) {
            return
        }
        lastWindowStartMs = windowStartMs

        val sourceIntensity = map.track[windowStartMs]?.coerceIn(0, 255) ?: 0
        val rumbleAmp = softRumbleAmplitude(pos, amplitudeCap)
        val blendedIntensity = maxOf(sourceIntensity, rumbleAmp)
        val effectiveAmplitude = minOf(blendedIntensity, amplitudeCap.coerceIn(0, 255))
        val isSoftRumbleOnly = sourceIntensity < minIntensity && rumbleAmp > 0

        val nowMs = System.currentTimeMillis()
        val shouldLog = eventTriggerMode && (nowMs - lastDebugLogMs) >= debugLogIntervalMs
        if (shouldLog) {
            val skipReason = when {
                effectiveAmplitude < minIntensity -> "below_min"
                map.format == HapticTrackFormat.WAV -> {
                    val delta = abs(effectiveAmplitude - lastEffectiveAmplitude)
                    val isOnset = lastEffectiveAmplitude < minIntensity
                    if (!isOnset && delta < wavChangeThreshold) "wav_small_delta" else null
                }
                else -> {
                    if (effectiveAmplitude == lastEffectiveAmplitude) "json_equal_amplitude" else null
                }
            }
            val fired = skipReason == null
            val windowCenterMs = windowStartMs + windowSizeMs / 2
            Log.d(
                tag,
                "DBG winStart=${windowStartMs} winCenter=${windowCenterMs} srcInt=${sourceIntensity} eff=${effectiveAmplitude} min=${minIntensity} lastEff=${lastEffectiveAmplitude} fired=$fired skip=$skipReason",
            )
            lastDebugLogMs = nowMs
        }

        if (effectiveAmplitude < minIntensity) {
            if (lastEffectiveAmplitude >= minIntensity) {
                cancelScheduledPulse()
                hapticController.cancel()
                sustainedBucket = -1
            }
            lastEffectiveAmplitude = 0
            return
        }

        // Mid-run window at the same perceived level: leave the looping effect
        // alone rather than restarting the motor.
        val wasActive = lastEffectiveAmplitude >= minIntensity
        val risingHit = effectiveAmplitude - lastEffectiveAmplitude >= 40
        if (eventTriggerMode && wasActive && !risingHit && sustainedBucket == amplitudeBucket(effectiveAmplitude)) {
            lastEffectiveAmplitude = effectiveAmplitude
            return
        }

        if (map.format == HapticTrackFormat.WAV) {
            val delta = abs(effectiveAmplitude - lastEffectiveAmplitude)
            val isOnset = lastEffectiveAmplitude < minIntensity
            if (!isOnset && delta < wavChangeThreshold) {
                return
            }
        } else if (effectiveAmplitude == lastEffectiveAmplitude && !eventTriggerMode) {
            return
        }

        lastEffectiveAmplitude = effectiveAmplitude

        val pulseDurationMs = when {
            isSoftRumbleOnly -> windowSizeMs.coerceIn(40L, 120L)
            map.format == HapticTrackFormat.WAV && eventTriggerMode -> {
                // Accent spike: short solid hit, not a looping sustain.
                (windowSizeMs * 2).coerceIn(40L, 120L)
            }
            map.format == HapticTrackFormat.WAV -> {
                (windowSizeMs * 0.85).toLong().coerceIn(14L, 60L)
            }
            eventTriggerMode -> windowSizeMs.coerceIn(30L, 180L)
            else -> (windowSizeMs * 0.75).toLong().coerceIn(15L, 180L)
        }
        val windowCenterMs = windowStartMs + windowSizeMs / 2
        val delayMs = (windowCenterMs - pos).coerceAtLeast(0L)

        if (scheduledWindowStartMs != windowStartMs) {
            cancelScheduledPulse()
        }

        val runnable = Runnable {
            if (isSoftRumbleOnly) {
                fireSoftRumble(effectiveAmplitude)
            } else if (eventTriggerMode && map.format == HapticTrackFormat.WAV) {
                // Accent from WAV: solid punch, then let silence resume.
                hapticController.vibrateSolidBurst(pulseDurationMs)
                sustainedBucket = -1
            } else {
                firePulse(pulseDurationMs, effectiveAmplitude)
            }
        }

        scheduledRunnable = runnable
        scheduledWindowStartMs = windowStartMs
        if (delayMs <= 15L) {
            handler.post(runnable)
        } else {
            handler.postDelayed(runnable, delayMs)
        }
    }

    /**
     * Envelope around a peak: how long that blast stays loud.
     *
     * A spike is a tall, narrow island. A slope is the same island stretched in
     * time. Neighbouring wobble in the lookup window is ignored — it is not a
     * second tap.
     */
    private fun envelopeAround(peakMs: Long): EnvelopeSlice {
        val empty = EnvelopeSlice(0, 0L, 0f)
        val map = hapticMap ?: return empty
        val source = map.envelope.ifEmpty { map.track }
        if (source.isEmpty()) return empty
        val window = map.windowSizeMs.coerceAtLeast(1L)
        val from = (peakMs - ACCENT_LOOKUP_RADIUS_MS).coerceAtLeast(0L)
        val to = peakMs + ACCENT_LOOKUP_RADIUS_MS
        val values = mutableListOf<Int>()
        var cursor = (from / window) * window
        while (cursor <= to) {
            values += source[cursor] ?: 0
            cursor += window
        }
        if (values.isEmpty()) return empty
        val loudest = values.max()
        if (loudest <= 0) return empty
        val peakIdx = values.indices.maxBy { values[it] }
        val floor = (loudest * 0.45f).toInt().coerceAtLeast(1)
        var left = peakIdx
        var right = peakIdx
        while (left > 0 && values[left - 1] >= floor) left--
        while (right < values.lastIndex && values[right + 1] >= floor) right++
        val widthMs = ((right - left + 1) * window)
        val busy = values.count { it >= floor }.toFloat() / values.size
        return EnvelopeSlice(loudest, widthMs, busy)
    }

    private data class EnvelopeSlice(
        val loudness: Int,
        val widthMs: Long,
        val busy: Float,
    )

    /**
     * Windowed 0..255 envelope from [fromMs] to [toMs], with a decaying tail so a
     * blast that stays in the soundtrack is not cut off after the first pulse.
     */
    private fun blastLevels(fromMs: Long, toMs: Long, peakMs: Long, window: Long): List<Int> {
        val map = hapticMap
        val source = map?.envelope?.ifEmpty { map.track }.orEmpty()
        val n = (((toMs - fromMs) / window).toInt() + 1).coerceAtLeast(1)
        val levels = MutableList(n) { i ->
            val t = ((fromMs + i * window) / window) * window
            source[t] ?: 0
        }
        val peakIdx = ((peakMs - fromMs) / window).toInt().coerceIn(0, n - 1)
        val peakLevel = maxOf(levels[peakIdx], levels.maxOrNull() ?: 0, 1)
        val lastLoud = levels.indices.lastOrNull { levels[it] >= DECAY_FLOOR } ?: peakIdx
        val tailBudget = n - 1 - peakIdx
        val diedEarly = lastLoud <= peakIdx + 2 || lastLoud < peakIdx + (tailBudget * 0.35f).toInt()
        if (diedEarly) {
            val start = maxOf(lastLoud, peakIdx)
            val startLv = maxOf(levels[start], (peakLevel * 0.75f).toInt())
            val remain = (n - 1 - start).coerceAtLeast(1)
            for (i in start until n) {
                val t = (i - start).toFloat() / remain
                val faded = (startLv * (1f - t) * (1f - t)).toInt()
                if (faded >= DECAY_FLOOR) {
                    levels[i] = maxOf(levels[i], faded)
                }
            }
        }
        val fadeFrom = (n * 0.65f).toInt().coerceIn(peakIdx + 1, n - 1)
        for (i in fadeFrom until n) {
            val t = (i - fadeFrom + 1).toFloat() / (n - fadeFrom).coerceAtLeast(1)
            val gain = (1f - t) * (1f - t)
            levels[i] = (levels[i] * gain).toInt()
        }
        return levels
    }

    /**
     * Solid through the peak (one continuous blast), then a coarse fade.
     *
     * Fine 8/12 ms PWM for the whole span reads as chatter, not a declining boom.
     */
    private fun decayWaveform(
        levels: List<Int>,
        window: Long,
        cap: Int,
    ): Pair<LongArray, IntArray> {
        val first = levels.indexOfFirst { it >= DECAY_FLOOR }
        val last = levels.indexOfLast { it >= DECAY_FLOOR }
        if (first < 0 || last < first) {
            return longArrayOf(0L, HIT_SPIKE_MS) to intArrayOf(0, cap)
        }
        val delay = first * window
        val slice = levels.subList(first, last + 1)
        val peak = slice.max().coerceAtLeast(1)
        val peakIdx = first + slice.indices.maxBy { slice[it] }
        val loudFloor = (peak * SOLID_REL).toInt().coerceAtLeast(DECAY_FLOOR)
        val minAttackIdx = first + ((last - first) * 0.40f).toInt()
        var attackUntil = maxOf(peakIdx, minAttackIdx)
        for (i in attackUntil..last) {
            if (levels[i] >= loudFloor) {
                attackUntil = i
            } else {
                break
            }
        }
        val attackMs = ((attackUntil - first + 1) * window).coerceAtLeast(HIT_SPIKE_MS)
        val timings = mutableListOf(delay, attackMs)
        val amps = mutableListOf(0, cap)

        if (hapticController.hasAmplitudeControl()) {
            for (i in (attackUntil + 1)..last) {
                val v = levels[i].coerceIn(0, 255)
                if (v < DECAY_FLOOR) continue
                timings += window
                amps += ((v / 255f) * cap).toInt().coerceIn(1, cap)
            }
            return timings.toLongArray() to amps.toIntArray()
        }

        val tail = mutableListOf<Int>()
        var i = attackUntil + 1
        val stepWindows = (PWM_STEP_MS / window).toInt().coerceAtLeast(1)
        while (i <= last) {
            val end = minOf(i + stepWindows - 1, last)
            var sum = 0
            var n = 0
            for (k in i..end) {
                sum += levels[k]
                n++
            }
            tail += if (n > 0) sum / n else 0
            i = end + 1
        }
        if (tail.isEmpty()) {
            return timings.toLongArray() to amps.toIntArray()
        }
        var pendingOn = 0L
        fun flushOn() {
            if (pendingOn > 0L) {
                timings += pendingOn
                amps += cap
                pendingOn = 0L
            }
        }
        val tailPeak = maxOf(tail.max(), 1)
        tail.forEachIndexed { index, raw ->
            val t = (index + 1).toFloat() / tail.size
            val faded = (raw * (1f - t) * (1f - 0.45f * t)).toInt()
            val v = maxOf(faded, (tailPeak * (1f - t) * 0.35f).toInt())
            if (v < DECAY_FLOOR) {
                flushOn()
                return@forEachIndexed
            }
            val onMs = ((v / 255.0) * PWM_STEP_MS).toLong()
            if (onMs >= PWM_STEP_MS - 4L) {
                pendingOn += PWM_STEP_MS
            } else {
                flushOn()
                val held = onMs.coerceIn(PWM_MIN_ON_MS, PWM_STEP_MS)
                timings += held
                amps += cap
                val offMs = PWM_STEP_MS - held
                if (offMs > 0L) {
                    timings += offMs
                    amps += 0
                }
            }
        }
        flushOn()
        while (amps.size > 2 && amps.last() == 0) {
            timings.removeAt(timings.lastIndex)
            amps.removeAt(amps.lastIndex)
        }
        return timings.toLongArray() to amps.toIntArray()
    }

    /**
     * One fire, shaped like the WAV at that instant.
     *
     * A true spike (short event, energy dies immediately) stays one punch. A
     * blast that the soundtrack holds and lets decline keeps the motor on for
     * that span and fades with it — not a pulse at the first sample.
     */
    private fun accentPattern(event: PipelineEvent, cap: Int, nextPeakMs: Long?): AccentHit {
        val leadMs = if (hapticController.hasAmplitudeControl()) {
            HIT_LEAD_MS
        } else {
            HIT_LEAD_ERM_MS
        }
        val fromMs = (event.peakMs - leadMs).coerceAtLeast(event.startMs).coerceAtLeast(0L)
        val untilEvent = event.endMs.coerceAtLeast(event.peakMs + HIT_SPIKE_MS)
        val untilNext = nextPeakMs?.minus(EVENT_GAP_MS) ?: Long.MAX_VALUE
        val toMs = minOf(untilEvent, untilNext, fromMs + MAX_BLAST_MS)
            .coerceAtLeast(fromMs + HIT_SPIKE_MS)
        val spanMs = toMs - fromMs
        val window = hapticMap?.windowSizeMs?.coerceAtLeast(1L) ?: PWM_PERIOD_MS
        val levels = blastLevels(fromMs, toMs, event.peakMs, window)
        val loudness = levels.maxOrNull() ?: 0
        val busy = if (levels.isEmpty()) {
            0f
        } else {
            levels.count { it >= DECAY_FLOOR }.toFloat() / levels.size
        }

        val isSpike = spanMs <= SPIKE_SPAN_MS
        val timings: LongArray
        val amplitudes: IntArray
        val shape: String
        if (isSpike || loudness < DECAY_FLOOR) {
            timings = longArrayOf(0L, HIT_SPIKE_MS)
            amplitudes = intArrayOf(0, cap)
            shape = "spike"
        } else {
            val wave = decayWaveform(levels, window, cap)
            timings = wave.first
            amplitudes = wave.second
            shape = "decay"
        }
        return AccentHit(
            timings = timings,
            amplitudes = amplitudes,
            strength = cap,
            loudness = loudness,
            pulses = 1,
            density = trackDensity,
            busy = busy,
            shape = shape,
            widthMs = spanMs,
        )
    }

    private data class AccentHit(
        val timings: LongArray,
        val amplitudes: IntArray,
        val strength: Int,
        val loudness: Int,
        val pulses: Int,
        val density: Float,
        val busy: Float,
        val shape: String,
        val widthMs: Long,
    ) {
        val durationMs: Long get() = timings.sum()
    }

    private fun computeTrackDensity(map: HapticMapData): Float {
        val source = map.envelope.ifEmpty { map.track }
        if (source.isEmpty()) return 0f
        val active = source.values.count { it >= 50 }
        return active.toFloat() / source.size.toFloat()
    }

    /** Loudest window near a peak (events-only playback fallback). */
    private fun trackLoudnessNear(peakMs: Long): Int? {
        if (hapticMap == null) return null
        return envelopeAround(peakMs).loudness
    }

    /**
     * Duration and strength for one accent, taken from the loaded algorithm.
     *
     * Confidence alone is nearly constant across A-E, so it cannot separate them;
     * the track's own envelope at that instant can.
     */
    private fun accentProfile(event: PipelineEvent, cap: Int): Triple<Long, Int, Int> {
        val hit = accentPattern(event, cap, nextPeakMs = null)
        return Triple(hit.durationMs, hit.strength, hit.loudness)
    }

    /** Algorithm-specific bed between accents; never reaches accent strength. */
    private fun updateTextureFromTrack(positionMs: Long, map: HapticMapData, amplitudeCap: Int) {
        val window = map.windowSizeMs.coerceAtLeast(1L)
        val windowStartMs = (positionMs / window) * window
        if (windowStartMs == lastTextureWindowMs) return
        lastTextureWindowMs = windowStartMs

        val source = map.envelope.ifEmpty { map.track }
        val raw = source[windowStartMs]?.coerceIn(0, 255) ?: 0
        val ceiling = minOf(TEXTURE_MAX_AMPLITUDE, amplitudeCap.coerceIn(0, 255))
        val level = minOf((raw * TEXTURE_SCALE).toInt(), ceiling)
        if (level < TEXTURE_MIN_AMPLITUDE) {
            stopTexture()
            return
        }
        fireTexture(level)
    }

    private fun fireTexture(level: Int) {
        val bucket = level / TEXTURE_BUCKET_SIZE
        if (textureBucket == bucket) return
        textureBucket = bucket
        sustainedBucket = -1
        hapticController.cancel()
        if (hapticController.hasAmplitudeControl()) {
            hapticController.vibrateOneShot(MAX_SUSTAIN_MS, level)
            return
        }
        // No amplitude control: duty cycle carries the level, looped so the bed
        // sustains instead of re-triggering as a string of taps.
        val onMs = ((level / 255.0) * PWM_PERIOD_MS).toLong().coerceIn(2L, PWM_PERIOD_MS - 2L)
        hapticController.vibrateWaveform(
            timings = longArrayOf(0L, onMs, PWM_PERIOD_MS - onMs),
            amplitudes = intArrayOf(0, 255, 0),
            repeat = 0,
        )
    }

    private fun stopTexture() {
        if (textureBucket < 0) return
        textureBucket = -1
        sustainedBucket = -1
        hapticController.cancel()
    }

    private fun softRumbleAmplitude(positionMs: Long, amplitudeCap: Int): Int {
        if (!eventTriggerMode || rumbleSpans.isEmpty()) return 0
        // Whole-clip vehicle beds (tank montage) would keep the motor on forever.
        // Soft rumble is only for short sustained bursts, not scene-length spans.
        val maxSpanMs = 4_000L
        val active = rumbleSpans.any { span ->
            span.contains(positionMs) && (span.endMs - span.startMs) <= maxSpanMs
        }
        if (!active) return 0
        val base = if (hapticController.hasAmplitudeControl()) {
            RUMBLE_AMPLITUDE
        } else {
            RUMBLE_ERM_AMPLITUDE
        }
        return minOf(base, amplitudeCap.coerceIn(0, 255))
    }

    private fun fireSoftRumble(amplitude: Int) {
        if (amplitude <= 0) return
        val bucket = amplitudeBucket(amplitude)
        if (sustainedBucket == bucket) return

        hapticController.cancel()
        if (hapticController.hasAmplitudeControl()) {
            hapticController.vibrateOneShot(MAX_SUSTAIN_MS, amplitude)
            sustainedBucket = bucket
            return
        }
        // Quiet looping duty cycle for engine bed — sparse enough to not feel "always on full".
        val onMs = 6L
        val offMs = 14L
        hapticController.vibrateWaveform(
            timings = longArrayOf(0L, onMs, offMs),
            amplitudes = intArrayOf(0, 255, 0),
            repeat = 0,
        )
        sustainedBucket = bucket
    }

    private fun firePulse(durationMs: Long, amplitude: Int) {
        if (amplitude <= 0) return

        hapticController.cancel()

        if (hapticController.hasAmplitudeControl()) {
            hapticController.vibrateOneShot(durationMs, amplitude)
            sustainedBucket = amplitudeBucket(amplitude)
            return
        }

        if (!eventTriggerMode) {
            val onMs = ((amplitude / 255.0) * durationMs).toLong().coerceAtLeast(12L)
            val gapMs = (durationMs * 2 - onMs).coerceAtLeast(20L)
            hapticController.vibrateWaveform(
                timings = longArrayOf(0L, onMs, gapMs),
                amplitudes = intArrayOf(0, 255, 0),
                repeat = -1,
            )
            return
        }

        // Event-Trigger on ERM: never hold MAX_SUSTAIN from a WAV window — that
        // reads as constant vibration. Use a finite waveform burst instead.
        val onMs = ((amplitude / 255.0) * PWM_PERIOD_MS).toLong().coerceIn(4L, PWM_PERIOD_MS)
        val cycles = (durationMs / PWM_PERIOD_MS).coerceIn(1L, 8L).toInt()
        val timings = LongArray(cycles * 2 + 1)
        val amps = IntArray(cycles * 2 + 1)
        timings[0] = 0L
        amps[0] = 0
        var idx = 1
        repeat(cycles) {
            timings[idx] = onMs
            amps[idx] = 255
            idx++
            timings[idx] = (PWM_PERIOD_MS - onMs).coerceAtLeast(1L)
            amps[idx] = 0
            idx++
        }
        hapticController.vibrateWaveform(timings, amps, repeat = -1)
        sustainedBucket = -1
    }

    fun stop() {
        resetPlaybackState()
        hapticController.cancel()
    }

    fun release() {
        stop()
        hapticMap = null
        mapLabel = null
        clearPipelineEvents()
    }

    private fun updatePlaybackFromPipelineEvents(positionMs: Long, amplitudeCap: Int) {
        if (positionMs + 50L < lastPlaybackPositionMs) {
            nextPipelineEventIndex = indexForPosition(positionMs)
        }
        lastPlaybackPositionMs = positionMs

        val cap = amplitudeCap.coerceIn(1, 255)
        val lookAheadMs = 30L

        while (nextPipelineEventIndex < pipelineEvents.size) {
            val event = pipelineEvents[nextPipelineEventIndex]
            if (event.peakMs > positionMs + lookAheadMs) {
                break
            }
            if (event.peakMs >= positionMs - lookAheadMs) {
                val amplitude = (cap * (0.55f + event.confidence * 0.45f)).toInt().coerceIn(1, cap)
                val durationMs = when (event.category) {
                    "vehicle", "weather" -> 180L
                    else -> 90L
                }
                val delayMs = (event.peakMs - positionMs).coerceAtLeast(0L)
                scheduleEventPulse(event, durationMs, amplitude, delayMs)
            }
            nextPipelineEventIndex++
        }
    }

    private fun scheduleEventPulse(
        event: PipelineEvent,
        durationMs: Long,
        amplitude: Int,
        delayMs: Long,
    ) {
        val runnable = Runnable {
            firePulse(durationMs, amplitude)
            Log.d(
                tag,
                "DBG event=${event.eventId} cat=${event.category} peakMs=${event.peakMs} " +
                    "label=${event.label} amp=$amplitude dur=${durationMs}ms fired=true",
            )
        }
        if (delayMs <= 15L) {
            handler.post(runnable)
        } else {
            handler.postDelayed(runnable, delayMs)
        }
    }

    private fun maybeFireImpulsiveHits(positionMs: Long, amplitudeCap: Int) {
        val hits = impulsiveHits
        if (hits.isEmpty()) return

        if (positionMs + 80L < lastPlaybackPositionMs) {
            nextHitIndex = indexForHit(positionMs)
        }

        val cap = amplitudeCap.coerceIn(1, 255)
        val leadMs = if (hapticController.hasAmplitudeControl()) {
            HIT_LEAD_MS
        } else {
            HIT_LEAD_ERM_MS
        }
        while (nextHitIndex < hits.size) {
            val event = hits[nextHitIndex]
            val fireAt = (event.peakMs - leadMs).coerceAtLeast(0L)
            if (fireAt > positionMs + HIT_LOOKAHEAD_MS) {
                break
            }
            val inWindow = fireAt >= positionMs - HIT_LOOKAHEAD_MS ||
                (positionMs in fireAt..(event.peakMs + HIT_CATCHUP_MS))
            if (inWindow) {
                val delayMs = (fireAt - positionMs).coerceAtLeast(0L)
                cancelScheduledPulse()
                hapticController.cancel()
                val nextPeak = hits.getOrNull(nextHitIndex + 1)?.peakMs
                val hit = accentPattern(event, cap, nextPeak)
                val holdMs = hit.durationMs + 40L
                val runnable = Runnable {
                    hapticController.vibrateAccentPattern(hit.timings, hit.amplitudes)
                    sustainedBucket = -1
                    textureBucket = -1
                    lastTextureWindowMs = Long.MIN_VALUE
                    lastEffectiveAmplitude = 0
                    hitHoldUntilElapsedMs = SystemClock.elapsedRealtime() + holdMs
                    val patternHead = hit.timings.take(10).joinToString()
                    Log.d(
                        tag,
                        "DBG hit event=${event.eventId} cat=${event.category} peakMs=${event.peakMs} " +
                            "endMs=${event.endMs} fireAt=$fireAt pos=$positionMs shape=${hit.shape} " +
                            "pulses=${hit.pulses} spanMs=${hit.widthMs} dur=${hit.durationMs} " +
                            "trackLoud=${hit.loudness} track=${mapLabel ?: "none"} " +
                            "steps=${hit.timings.size} pattern=$patternHead lead=$leadMs",
                    )
                }
                if (delayMs <= 15L) {
                    handler.post(runnable)
                    hitHoldUntilElapsedMs = SystemClock.elapsedRealtime() + holdMs
                } else {
                    handler.postDelayed(runnable, delayMs)
                    hitHoldUntilElapsedMs = SystemClock.elapsedRealtime() + delayMs + holdMs
                }
            }
            nextHitIndex++
        }
    }

    private fun indexForHit(positionMs: Long): Int {
        val hits = impulsiveHits
        val leadMs = if (hapticController.hasAmplitudeControl()) HIT_LEAD_MS else HIT_LEAD_ERM_MS
        return hits.indexOfFirst { (it.peakMs - leadMs) >= positionMs - HIT_LOOKAHEAD_MS }
            .let { if (it < 0) hits.size else it }
    }

    /** Peak-only playback when events.json is loaded without a WAV/JSON track. */
    private fun shouldUsePipelineEventPeaks(): Boolean {
        return eventTriggerMode && pipelineEvents.isNotEmpty() && hapticMap == null
    }

    private fun indexForPosition(positionMs: Long): Int {
        val lookAheadMs = 30L
        return pipelineEvents.indexOfFirst { it.peakMs >= positionMs - lookAheadMs }
            .let { if (it < 0) pipelineEvents.size else it }
    }

    private fun resetPipelineEventState() {
        nextPipelineEventIndex = if (lastPlaybackPositionMs >= 0L) {
            indexForPosition(lastPlaybackPositionMs)
        } else {
            0
        }
        nextHitIndex = if (lastPlaybackPositionMs >= 0L) {
            indexForHit(lastPlaybackPositionMs)
        } else {
            0
        }
    }

    /** Log peak_sec crossings for timing validation while WAV/JSON drives continuous haptics. */
    private fun maybeLogPipelinePeakReference(positionMs: Long) {
        if (!eventTriggerMode || pipelineEvents.isEmpty()) return
        if (positionMs + 50L < lastPlaybackPositionMs) {
            nextPipelineEventIndex = indexForPosition(positionMs)
        }
        val lookAheadMs = 30L
        while (nextPipelineEventIndex < pipelineEvents.size) {
            val event = pipelineEvents[nextPipelineEventIndex]
            if (event.peakMs > positionMs + lookAheadMs) break
            if (event.peakMs >= positionMs - lookAheadMs) {
                Log.d(
                    tag,
                    "DBG peak_ref event=${event.eventId} peakMs=${event.peakMs} pos=$positionMs " +
                        "(WAV drives haptics; peak reference only)",
                )
            }
            nextPipelineEventIndex++
        }
        lastPlaybackPositionMs = positionMs
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
        trackDensity = computeTrackDensity(map)
        resetPlaybackState()
        val activeWindows = map.track.values.count { it > 0 }
        Log.d(
            tag,
            "Loaded haptic track label=$label format=${map.format} windows=${map.track.size} " +
                "activeWindows=$activeWindows density=${"%.2f".format(trackDensity)} " +
                "windowSizeMs=${map.windowSizeMs} durationMs=${map.durationMs}",
        )
    }

    private fun resetPlaybackState() {
        lastWindowStartMs = Long.MIN_VALUE
        lastEffectiveAmplitude = 0
        lastPlaybackPositionMs = -1L
        nextPipelineEventIndex = 0
        nextHitIndex = 0
        hitHoldUntilElapsedMs = 0L
        sustainedBucket = -1
        textureBucket = -1
        lastTextureWindowMs = Long.MIN_VALUE
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
            envelope = normalizedTrack,
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

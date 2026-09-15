package com.example.haptictester.haptic

import android.content.Context
import android.util.Log
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticController(private val context: Context) {
    private companion object {
        /** Duty-cycle period used to fake amplitude on motors without it. */
        const val PWM_PERIOD_MS = 20L

        /** Gap between composed clicks when primitives carry the accent. */
        const val PRIMITIVE_SPACING_MS = 35L
    }

    private val TAG = "HapticController"
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun hasVibrator(): Boolean = vibrator?.hasVibrator() ?: false

    fun hasAmplitudeControl(): Boolean = vibrator?.hasAmplitudeControl() ?: false

    /**
     * Composition primitives accept a scale even when [hasAmplitudeControl] is
     * false, so on those devices they are the only way to get graded strength.
     */
    fun hasScalablePrimitives(): Boolean {
        val v = vibrator ?: return false
        return try {
            v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
        } catch (throwable: Throwable) {
            Log.w(TAG, "areAllPrimitivesSupported failed", throwable)
            false
        }
    }

    fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        if (vibrator == null) {
            Log.w(TAG, "vibrateOneShot: no vibrator available")
            return
        }
        if (durationMs <= 0L || amplitude <= 0) return
        vibrator?.cancel()
        // Devices without amplitude control reject non-DEFAULT amplitudes on some OEMs.
        val amp = if (hasAmplitudeControl()) {
            amplitude.coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        val effect = VibrationEffect.createOneShot(durationMs, amp)
        Log.d(TAG, "vibrateOneShot duration=$durationMs amp=$amp hasAmp=${hasAmplitudeControl()}")
        vibrator?.vibrate(effect)
    }

    /**
     * Full-strength solid burst. On ERMs without amplitude control, a short
     * one-shot is often imperceptible; a timed ON waveform gives the motor
     * time to spin up so a tank muzzle actually feels like a hit.
     */
    fun vibrateSolidBurst(durationMs: Long) {
        if (vibrator == null) {
            Log.w(TAG, "vibrateSolidBurst: no vibrator available")
            return
        }
        val dur = durationMs.coerceAtLeast(40L)
        vibrator?.cancel()
        if (hasAmplitudeControl()) {
            val effect = VibrationEffect.createOneShot(dur, 255)
            Log.d(TAG, "vibrateSolidBurst oneShot duration=$dur amp=255")
            vibrator?.vibrate(effect)
            return
        }
        // Amplitude values are ignored without hardware amp control; timings drive the motor.
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0L, dur),
            intArrayOf(0, 255),
            -1,
        )
        Log.d(TAG, "vibrateSolidBurst waveform duration=$dur")
        vibrator?.vibrate(effect)
    }

    /**
     * Burst whose *strength* survives on motors without amplitude control.
     *
     * A solid ON burst is the same hit for every algorithm, which is why A-E
     * felt identical. Here strength becomes duty cycle (or a scaled primitive
     * where the device supports one), so a quiet accent in one algorithm reads
     * softer than a loud one in another at the same instant.
     */
    fun vibrateShapedBurst(durationMs: Long, strength: Int) {
        if (vibrator == null) {
            Log.w(TAG, "vibrateShapedBurst: no vibrator available")
            return
        }
        val dur = durationMs.coerceAtLeast(40L)
        val level = strength.coerceIn(1, 255)
        vibrator?.cancel()

        if (hasAmplitudeControl()) {
            Log.d(TAG, "vibrateShapedBurst oneShot duration=$dur amp=$level")
            vibrator?.vibrate(VibrationEffect.createOneShot(dur, level))
            return
        }

        if (hasScalablePrimitives()) {
            val clicks = (dur / PRIMITIVE_SPACING_MS).coerceIn(1L, 6L).toInt()
            val scale = (level / 255f).coerceIn(0.1f, 1f)
            val composition = VibrationEffect.startComposition()
            repeat(clicks) { index ->
                composition.addPrimitive(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    scale,
                    if (index == 0) 0 else PRIMITIVE_SPACING_MS.toInt(),
                )
            }
            Log.d(TAG, "vibrateShapedBurst primitives clicks=$clicks scale=$scale dur=$dur")
            vibrator?.vibrate(composition.compose())
            return
        }

        // Duty cycle is the only intensity axis left. Keep a floor on ON time so
        // an ERM still spins up instead of buzzing inaudibly.
        val onMs = ((level / 255.0) * PWM_PERIOD_MS).toLong().coerceIn(4L, PWM_PERIOD_MS)
        val offMs = (PWM_PERIOD_MS - onMs).coerceAtLeast(0L)
        val cycles = (dur / PWM_PERIOD_MS).coerceIn(1L, 16L).toInt()
        val timings = LongArray(cycles * 2 + 1)
        val amplitudes = IntArray(cycles * 2 + 1)
        var index = 1
        repeat(cycles) {
            timings[index] = onMs
            amplitudes[index] = 255
            index++
            timings[index] = offMs
            amplitudes[index] = 0
            index++
        }
        Log.d(
            TAG,
            "vibrateShapedBurst pwm duration=$dur level=$level onMs=$onMs cycles=$cycles",
        )
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    /**
     * One finite accent. Never loops — looping PWM between hits was the noise.
     *
     * On motors without amplitude control, extra pulses (not extra duty-cycle
     * chatter) are how algorithms stay distinguishable: a dense WAV becomes a
     * double/triple tap, a sparse WAV stays a single punch.
     */
    fun vibrateAccentPattern(timings: LongArray, amplitudes: IntArray) {
        if (vibrator == null) {
            Log.w(TAG, "vibrateAccentPattern: no vibrator available")
            return
        }
        if (timings.size < 2) return
        vibrator?.cancel()
        Log.d(
            TAG,
            "vibrateAccentPattern timings=${timings.joinToString()} amps=${amplitudes.joinToString()}",
        )
        vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
    }

    fun vibrateWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int = -1) {
        if (vibrator == null) {
            Log.w(TAG, "vibrateWaveform: no vibrator available")
            return
        }
        vibrator?.cancel()
        val effect = VibrationEffect.createWaveform(timings, amplitudes, repeat)
        Log.d(TAG, "vibrateWaveform timings=${timings.joinToString()} amps=${amplitudes.joinToString()} repeat=$repeat")
        vibrator?.vibrate(effect)
    }

    fun cancel() {
        vibrator?.cancel()
    }
}

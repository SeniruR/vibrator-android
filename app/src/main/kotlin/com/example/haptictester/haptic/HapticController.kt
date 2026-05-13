package com.example.haptictester.haptic

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class HapticController(private val context: Context) {
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

    fun vibrateOneShot(durationMs: Long, amplitude: Int) {
        vibrator ?: return
        vibrator?.cancel()
        val amp = when {
            amplitude <= 0 -> 0
            amplitude > 255 -> 255
            else -> amplitude
        }
        val effect = VibrationEffect.createOneShot(durationMs, amp)
        vibrator?.vibrate(effect)
    }

    fun vibrateWaveform(timings: LongArray, amplitudes: IntArray, repeat: Int = -1) {
        vibrator ?: return
        vibrator?.cancel()
        val effect = VibrationEffect.createWaveform(timings, amplitudes, repeat)
        vibrator?.vibrate(effect)
    }

    fun cancel() {
        vibrator?.cancel()
    }
}

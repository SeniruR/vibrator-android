package com.example.haptictester.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.example.haptictester.haptic.AudioHapticController
import com.example.haptictester.haptic.HapticController
import com.example.haptictester.haptic.ThrottleGate

class HapticViewModel(application: Application) : AndroidViewModel(application) {
    private val haptic = HapticController(application.applicationContext)
    private val audioHaptic = AudioHapticController(application.applicationContext, haptic)
    private val gate = ThrottleGate(100L)
    private var liveUpdateJob: Job? = null

    private val _amplitude = MutableStateFlow(128)
    val amplitude = _amplitude.asStateFlow()

    private val _duty = MutableStateFlow(50)
    val duty = _duty.asStateFlow()

    private val _periodMs = MutableStateFlow(200)
    val periodMs = _periodMs.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()

    private val _hasAmplitude = MutableStateFlow(haptic.hasAmplitudeControl())
    val hasAmplitude = _hasAmplitude.asStateFlow()

    private val _hasVibrator = MutableStateFlow(haptic.hasVibrator())
    val hasVibrator = _hasVibrator.asStateFlow()

    private val _selectedAudioName = MutableStateFlow<String?>(null)
    val selectedAudioName = _selectedAudioName.asStateFlow()

    private val _audioPlaying = MutableStateFlow(false)
    val audioPlaying = _audioPlaying.asStateFlow()

    private val _audioVibrateEnabled = MutableStateFlow(true)
    val audioVibrateEnabled = _audioVibrateEnabled.asStateFlow()

    private val _audioLevel = MutableStateFlow(0)
    val audioLevel = _audioLevel.asStateFlow()

    private val _audioError = MutableStateFlow<String?>(null)
    val audioError = _audioError.asStateFlow()

    fun setAmplitude(v: Int) {
        _amplitude.value = v.coerceIn(0, 255)
        // clear any queued vibrations for safety
        requestLiveUpdate()
    }

    fun setDuty(percent: Int) {
        _duty.value = percent.coerceIn(0, 100)
        requestLiveUpdate()
    }

    fun setPeriodMs(value: Int) {
        _periodMs.value = value.coerceIn(60, 1000)
        requestLiveUpdate()
    }

    fun startTest() {
        if (!_hasVibrator.value) return
        if (_audioPlaying.value) return
        if (!gate.canExecute()) return
        _isTesting.value = true
        requestLiveUpdate()
    }

    fun stopTest() {
        liveUpdateJob?.cancel()
        liveUpdateJob = null
        haptic.cancel()
        _isTesting.value = false
    }

    fun setAudioVibrateEnabled(enabled: Boolean) {
        _audioVibrateEnabled.value = enabled
        audioHaptic.setVibrateFromAudio(enabled)
    }

    fun loadAudio(uri: Uri) {
        val app = getApplication<Application>()
        try {
            app.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // temporary permission is enough for this session
        }

        val displayName = audioHaptic.load(
            uri = uri,
            vibrateFromAudio = _audioVibrateEnabled.value,
            onLevel = { level ->
                _audioLevel.value = level
                handleAudioLevel(level)
            },
            onEnded = {
                _audioPlaying.value = false
            },
            onError = { message ->
                _audioError.value = message
            }
        )
        _selectedAudioName.value = displayName
        _audioError.value = null
        _audioPlaying.value = false
    }

    fun playAudio() {
        stopTest()
        audioHaptic.play()
        _audioPlaying.value = true
    }

    fun pauseAudio() {
        audioHaptic.pause()
        _audioPlaying.value = false
        haptic.cancel()
    }

    fun stopAudio() {
        audioHaptic.stop()
        _audioPlaying.value = false
        _audioLevel.value = 0
        haptic.cancel()
    }

    private fun requestLiveUpdate() {
        if (!_isTesting.value || !_hasVibrator.value) {
            return
        }

        liveUpdateJob?.cancel()
        liveUpdateJob = viewModelScope.launch {
            val waitMs = gate.timeUntilNextMs()
            if (waitMs > 0) {
                delay(waitMs)
            }
            if (!_isTesting.value) return@launch

            haptic.cancel()

            val amp = _amplitude.value
            val dutyPct = _duty.value
            val period = _periodMs.value.toLong()
            val onMs = (period * dutyPct / 100.0).toLong().coerceIn(10L, period)
            val offMs = (period - onMs).coerceAtLeast(10L)
            val timings = longArrayOf(onMs, offMs)
            val amplitudes = intArrayOf(amp, 0)
            haptic.vibrateWaveform(timings, amplitudes, -1)
        }
    }

    private fun handleAudioLevel(level: Int) {
        if (!_audioPlaying.value || !_audioVibrateEnabled.value) {
            return
        }

        val clamped = level.coerceIn(0, 100)
        if (clamped <= 2) {
            haptic.cancel()
            return
        }

        if (!gate.canExecute()) {
            return
        }

        val onMs = (15L + (clamped * 85L / 100L)).coerceIn(10L, 100L)
        val offMs = (100L - onMs).coerceAtLeast(10L)
        val amplitude = if (haptic.hasAmplitudeControl()) {
            (20 + clamped * 235 / 100).coerceIn(1, 255)
        } else {
            255
        }

        haptic.cancel()
        haptic.vibrateWaveform(longArrayOf(onMs, offMs), intArrayOf(amplitude, 0), -1)
    }

    override fun onCleared() {
        audioHaptic.release()
        stopTest()
        super.onCleared()
    }
}

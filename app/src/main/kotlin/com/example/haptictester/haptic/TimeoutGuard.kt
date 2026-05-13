package com.example.haptictester.haptic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TimeoutGuard(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun start(durationMs: Long, onTimeout: () -> Unit) {
        stop()
        job = scope.launch {
            var elapsed = 0L
            val step = 200L
            while (elapsed < durationMs) {
                delay(step)
                elapsed += step
            }
            onTimeout()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

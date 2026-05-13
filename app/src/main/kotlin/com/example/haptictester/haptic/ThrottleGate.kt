package com.example.haptictester.haptic

import java.util.concurrent.atomic.AtomicLong

/**
 * Simple throttle gate: allows one command per minIntervalMs.
 */
class ThrottleGate(private val minIntervalMs: Long = 100L) {
    private val last = AtomicLong(0)

    fun canExecute(): Boolean {
        val now = System.currentTimeMillis()
        val prev = last.get()
        return if (now - prev >= minIntervalMs) {
            last.set(now)
            true
        } else {
            false
        }
    }

    fun timeUntilNextMs(): Long {
        val now = System.currentTimeMillis()
        val prev = last.get()
        val rem = minIntervalMs - (now - prev)
        return if (rem <= 0) 0 else rem
    }
}

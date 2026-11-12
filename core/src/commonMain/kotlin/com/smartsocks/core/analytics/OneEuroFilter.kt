package com.smartsocks.core.analytics

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1€ filter (Casiez et al., 2012): strong smoothing when the signal is slow, little lag when it moves fast.
 * A good fit for a COP trace drawn live. Timestamps in nanoseconds.
 */
class OneEuroFilter(
    private val minCutoffHz: Double = 1.0,
    private val beta: Double = 0.02,
    private val derivativeCutoffHz: Double = 1.0,
) {
    private var lastNanos = 0L
    private var x = 0.0
    private var dx = 0.0
    private var initialized = false

    fun reset() {
        initialized = false
    }

    fun filter(value: Double, timestampNanos: Long): Double {
        if (!initialized) {
            initialized = true
            lastNanos = timestampNanos
            x = value
            dx = 0.0
            return value
        }
        val dt = (timestampNanos - lastNanos) / 1e9
        lastNanos = timestampNanos
        if (dt <= 0.0) return x

        val rawDx = (value - x) / dt
        dx += alpha(derivativeCutoffHz, dt) * (rawDx - dx)
        val cutoff = minCutoffHz + beta * abs(dx)
        x += alpha(cutoff, dt) * (value - x)
        return x
    }

    private fun alpha(cutoffHz: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoffHz)
        return 1.0 / (1.0 + tau / dt)
    }
}

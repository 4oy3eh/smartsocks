package com.smartsocks.core.session

/**
 * Stamps samples by their index instead of by arrival time. Bluetooth delivers packets in bursts, so host receive
 * times inside a burst are equal or nearly so; `t = t0 + index * period` spaces them evenly instead.
 *
 * [t0] is the host time of the first packet. The sample clock is then slowly steered towards the host clock: when
 * it falls behind (device clock slower than nominal, lost packets) or runs ahead of the host by more than
 * [toleranceNanos], [t0] is nudged by a fraction of the excess, at most a quarter period per sample. Burst lead
 * within the tolerance is left alone. Stamps are strictly increasing, also across [reset]. Single-threaded.
 */
class SampleClock(
    private val periodNanos: Long,
    private val toleranceNanos: Long = maxOf(2 * periodNanos, DEFAULT_MIN_TOLERANCE_NANOS),
) {
    init {
        require(periodNanos > 0)
        require(toleranceNanos >= 0)
    }

    private var t0 = 0L
    private var index = 0L
    private var last = Long.MIN_VALUE

    /** Call after a reconnect: the next sample becomes index 0 again, anchored to the host clock. */
    fun reset() {
        index = 0
    }

    /** Timestamp for the next sample, which was received at [hostNanos]. */
    fun stamp(hostNanos: Long): Long {
        if (index == 0L) t0 = hostNanos
        val error = hostNanos - (t0 + index * periodNanos)
        val maxStep = maxOf(1L, periodNanos / 4)
        if (error > toleranceNanos) {
            t0 += ((error - toleranceNanos) / GAIN_DIVISOR).coerceIn(1L, maxStep)
        } else if (error < -toleranceNanos) {
            t0 -= ((-error - toleranceNanos) / GAIN_DIVISOR).coerceIn(1L, maxStep)
        }
        var t = t0 + index * periodNanos
        if (t <= last) t = last + 1
        index++
        last = t
        return t
    }

    private companion object {
        const val DEFAULT_MIN_TOLERANCE_NANOS = 50_000_000L
        const val GAIN_DIVISOR = 10L
    }
}

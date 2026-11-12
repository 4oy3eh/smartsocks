package com.smartsocks.core.session

/**
 * Stamps samples by their index instead of by arrival time. Bluetooth delivers packets in bursts, so host receive
 * times inside a burst are equal or nearly so; `t = t0 + index * period` spaces them evenly instead.
 *
 * [t0] is the host time of the first packet. It is then steered towards the host clock, but only by what the
 * **lower envelope** of `host - predicted` says: a packet can arrive late (stall, burst) but never early, so the
 * minimum over a window of [windowNanos] of host time is the drift without the latency. Latency spikes therefore do
 * not move the clock; a device clock that really differs from nominal does:
 *  - at the end of each window, if the minimum is outside [toleranceNanos], the excess is slewed in over the next
 *    samples (at most one period per sample forward, a quarter period backward, so stamps stay strictly increasing);
 *  - if it is outside [hardLimitNanos] (firmware ignoring the rate command, heavy loss) [t0] is re-anchored at once.
 *
 * Stamps are strictly increasing, also across [reset]: the first stamp after it is at least one period after the
 * last one before it. Single-threaded.
 */
class SampleClock(
    private val periodNanos: Long,
    private val toleranceNanos: Long = maxOf(2 * periodNanos, DEFAULT_MIN_TOLERANCE_NANOS),
    private val hardLimitNanos: Long = maxOf(DEFAULT_MIN_HARD_LIMIT_NANOS, 50 * periodNanos),
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
) {
    init {
        require(periodNanos > 0)
        require(toleranceNanos >= 0)
        require(hardLimitNanos >= toleranceNanos)
        require(windowNanos > 0)
    }

    private var t0 = 0L
    private var index = 0L
    private var last = Long.MIN_VALUE
    private var pending = 0L
    private var windowOpen = false
    private var windowStartHost = 0L
    private var windowMinAnchor = 0L

    /** Call after a reconnect: the next sample becomes index 0 again, anchored to the host clock. */
    fun reset() {
        index = 0
    }

    /** Timestamp for the next sample, which was received at [hostNanos]. */
    fun stamp(hostNanos: Long): Long {
        if (index == 0L) {
            t0 = if (last == Long.MIN_VALUE) hostNanos else maxOf(hostNanos, last + periodNanos)
            pending = 0
            windowOpen = false
        }
        // The anchor this packet implies for t0 if it had no latency; the smallest one in the window wins.
        val anchor = hostNanos - index * periodNanos
        if (!windowOpen) {
            windowOpen = true
            windowStartHost = hostNanos
            windowMinAnchor = anchor
        } else if (anchor < windowMinAnchor) {
            windowMinAnchor = anchor
        }
        if (hostNanos - windowStartHost >= windowNanos) {
            endWindow()
            windowOpen = false
        }
        if (pending != 0L) {
            val step = if (pending > 0) minOf(pending, periodNanos) else maxOf(pending, -(periodNanos / 4))
            t0 += step
            pending -= step
        }
        var t = t0 + index * periodNanos
        if (t <= last) t = last + 1
        index++
        last = t
        return t
    }

    private fun endWindow() {
        val error = windowMinAnchor - t0 // > 0: stamps lag the host, < 0: stamps lead it
        when {
            error > hardLimitNanos || error < -hardLimitNanos -> {
                t0 = windowMinAnchor
                pending = 0
            }

            error > toleranceNanos -> pending = error - toleranceNanos

            error < -toleranceNanos -> pending = error + toleranceNanos

            else -> pending = 0
        }
    }

    private companion object {
        const val DEFAULT_MIN_TOLERANCE_NANOS = 50_000_000L
        const val DEFAULT_MIN_HARD_LIMIT_NANOS = 1_000_000_000L
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L
    }
}

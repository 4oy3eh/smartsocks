package com.smartsocks.core.session

import kotlin.math.abs

/**
 * Stamps samples by their index instead of by arrival time. Bluetooth delivers packets in bursts, so host receive
 * times inside a burst are equal or nearly so; a timeline that advances by one period per sample spaces them evenly.
 *
 * The timeline starts at the host time of the first packet and is then steered towards the host clock, but only by
 * what the **lower envelope** of `host - timeline` says: a packet can arrive late (stall, burst) but never early, so
 * the minimum over a window of [windowNanos] of host time is the drift without the latency. Latency spikes therefore
 * do not move the clock; a device clock that really differs from nominal does:
 *  - at the end of each window the error (the minimum) is slewed out over the next samples: forward at most one
 *    period per sample, backward a quarter period (half while far off), so steps stay at least half a period. Inside
 *    [toleranceNanos] only a quarter of the error is taken per window, which still converges (two feet whose first
 *    packets had different latency end up together) without chasing jitter;
 *  - a forward error beyond [hardLimitNanos] (heavy loss) is jumped over at once;
 *  - the period itself is estimated: the slope of the lower envelope over consecutive windows gives the real period
 *    of the device, and once three consecutive windows agree that it differs from the current one by more than 2%
 *    (a firmware that ignores the rate command, a really slow device) the timeline advances by that estimate. The
 *    clock then does not have to fight a constant error, and a device faster than nominal does not produce runs of
 *    tiny steps.
 *
 * Stamps are strictly increasing, with steps of at least half the estimated period, also across [reset]: the first
 * stamp after it is at least one period after the last one before it. Single-threaded.
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

    /** Period the timeline currently advances by; starts at nominal. */
    private var estimate = periodNanos
    private var timeline = 0L
    private var started = false
    private var last = Long.MIN_VALUE

    /** Sum of all corrections applied to [timeline]; `timeline - applied` is the uncorrected, pure-period timeline. */
    private var applied = 0L
    private var pending = 0L
    private var fastBack = false

    private var windowOpen = false
    private var windowStartHost = 0L
    private var windowMin = 0L
    private var windowMinHost = 0L
    private var prevMin = 0L
    private var prevMinHost = 0L
    private var havePrev = false
    private val ratios = DoubleArray(RATIOS_NEEDED)
    private var ratioCount = 0

    /** Call after a reconnect: the next sample is anchored to the host clock again. */
    fun reset() {
        started = false
    }

    /** Timestamp for the next sample, which was received at [hostNanos]. */
    fun stamp(hostNanos: Long): Long {
        if (!started) {
            started = true
            timeline = if (last == Long.MIN_VALUE) hostNanos else maxOf(hostNanos, last + estimate)
            applied = 0
            pending = 0
            fastBack = false
            windowOpen = false
            havePrev = false
            ratioCount = 0
        } else {
            timeline += estimate
        }
        // What the uncorrected timeline says the host time was had this packet no latency; the smallest in the
        // window wins.
        val drift = hostNanos - (timeline - applied)
        if (!windowOpen) {
            windowOpen = true
            windowStartHost = hostNanos
            windowMin = drift
            windowMinHost = hostNanos
        } else if (drift < windowMin) {
            windowMin = drift
            windowMinHost = hostNanos
        }
        if (hostNanos - windowStartHost >= windowNanos) {
            endWindow()
            windowOpen = false
        }
        if (pending != 0L) {
            val step = if (pending > 0) {
                minOf(pending, estimate)
            } else {
                maxOf(pending, -(if (fastBack) estimate / 2 else estimate / 4))
            }
            timeline += step
            applied += step
            pending -= step
        }
        var t = timeline
        if (last != Long.MIN_VALUE && t < last + estimate / 2) t = last + estimate / 2
        last = t
        return t
    }

    private fun endWindow() {
        trackPeriod()
        val error = windowMin - applied // > 0: stamps lag the host, < 0: stamps lead it
        fastBack = false
        when {
            error > hardLimitNanos -> {
                timeline += error
                applied += error
                pending = 0
            }

            error < -hardLimitNanos -> {
                pending = error
                fastBack = true
            }

            error > toleranceNanos || error < -toleranceNanos -> pending = error

            else -> pending = error / 4
        }
    }

    /** Slope of the lower envelope between consecutive windows: `host - timeline` drifts by `1 - estimate/real`. */
    private fun trackPeriod() {
        if (havePrev && windowMinHost - prevMinHost >= windowNanos / 2) {
            val slope = (windowMin - prevMin).toDouble() / (windowMinHost - prevMinHost)
            if (slope < MAX_SLOPE) {
                if (ratioCount == RATIOS_NEEDED) {
                    for (k in 1 until RATIOS_NEEDED) ratios[k - 1] = ratios[k]
                    ratioCount--
                }
                ratios[ratioCount++] = 1.0 / (1.0 - slope)
            } else {
                ratioCount = 0 // a jump (lost packets), not a rate
            }
        }
        prevMin = windowMin
        prevMinHost = windowMinHost
        havePrev = true
        if (ratioCount < RATIOS_NEEDED) return
        var lo = ratios[0]
        var hi = ratios[0]
        var sum = 0.0
        for (r in ratios) {
            lo = minOf(lo, r)
            hi = maxOf(hi, r)
            sum += r
        }
        val mean = sum / RATIOS_NEEDED
        if (hi > lo * CONSISTENT_RATIO || mean !in MIN_RATIO..MAX_RATIO) return
        if (abs(mean - 1.0) <= SWITCH_FRACTION) return
        estimate = (estimate * mean).toLong().coerceAtLeast(2)
        // Slopes measured with the old estimate say nothing about the new one.
        havePrev = false
        ratioCount = 0
    }

    private companion object {
        const val DEFAULT_MIN_TOLERANCE_NANOS = 50_000_000L
        const val DEFAULT_MIN_HARD_LIMIT_NANOS = 1_000_000_000L
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L
        const val RATIOS_NEEDED = 3
        const val CONSISTENT_RATIO = 1.05
        const val SWITCH_FRACTION = 0.02
        const val MIN_RATIO = 0.25
        const val MAX_RATIO = 4.0
        const val MAX_SLOPE = 0.9
    }
}

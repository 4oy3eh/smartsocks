package com.smartsocks.core.sim

import com.smartsocks.core.session.monotonicClock
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The play position of one recording, shared by every [ReplaySockTransport] that plays it (both feet), so that they
 * stay in step whatever happens to either link: a foot that pauses, reconnects or catches up after a stall seeks to
 * where the clock is instead of going on from where it was.
 *
 * The position (ms of recording time, see [ReplaySockTransport] for the time map) runs at [speed] while any
 * transport streams; it stands still while none does, and starts over at 0 once the last one stopped at the end of
 * a recording that does not loop. [maxGapMillis] is part of the time map, so it is shared too: a silence of every
 * foot longer than this is cut to one sample period (null: keep every gap). [clockNanos]: a monotonic clock (a
 * virtual one in tests). Thread-safe.
 */
@OptIn(ExperimentalAtomicApi::class)
class ReplayClock(
    val speed: Double = 1.0,
    val maxGapMillis: Double? = 1_000.0,
    private val clockNanos: () -> Long = monotonicClock(),
) {
    init {
        require(speed > 0 && speed.isFinite()) { "speed must be positive" }
        require(maxGapMillis == null || maxGapMillis >= 0) { "maxGapMillis must not be negative" }
    }

    /** [active] transports streaming; the position is [baseMillis] at [epochNanos] (while active) or for good. */
    private class State(val active: Int, val epochNanos: Long, val baseMillis: Double)

    private val state = AtomicReference(State(0, 0, 0.0))

    /** The transports streaming now. */
    val active: Int get() = state.load().active

    /** Where the replay is, ms of recording time. */
    fun positionMillis(): Double = position(state.load(), clockNanos())

    private fun position(s: State, now: Long): Double =
        if (s.active == 0) s.baseMillis else s.baseMillis + (now - s.epochNanos) / 1e6 * speed

    /** A transport starts streaming: the first one sets the clock going. */
    internal fun acquire() {
        while (true) {
            val s = state.load()
            val next = if (s.active == 0) {
                State(1, clockNanos(), s.baseMillis)
            } else {
                State(s.active + 1, s.epochNanos, s.baseMillis)
            }
            if (state.compareAndSet(s, next)) return
        }
    }

    /** A transport stops streaming; [atEnd]: because its recording ended (and does not loop). */
    internal fun release(atEnd: Boolean) {
        while (true) {
            val s = state.load()
            if (s.active == 0) return
            val next = if (s.active == 1) {
                State(0, 0, if (atEnd) 0.0 else position(s, clockNanos()))
            } else {
                State(s.active - 1, s.epochNanos, s.baseMillis)
            }
            if (state.compareAndSet(s, next)) return
        }
    }
}

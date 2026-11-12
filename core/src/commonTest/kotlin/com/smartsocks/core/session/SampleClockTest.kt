package com.smartsocks.core.session

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleClockTest {
    private val period = 10_000_000L // 100 Hz

    @Test
    fun `burst arrivals get evenly spaced stamps`() {
        val clock = SampleClock(period)
        val stamps = mutableListOf<Long>()
        // 4 packets arrive together every 40 ms.
        for (burst in 0 until 50) repeat(4) { stamps += clock.stamp(1_000_000_000L + burst * 4 * period) }
        assertEquals(1_000_000_000L, stamps.first())
        for (i in 1 until stamps.size) assertEquals(period, stamps[i] - stamps[i - 1], "gap at $i")
    }

    @Test
    fun `clock that falls behind the host is steered back`() {
        val clock = SampleClock(period)
        var host = 0L
        var last = Long.MIN_VALUE
        var t = 0L
        repeat(2000) {
            host += 11_000_000L // device runs 10% slow
            t = clock.stamp(host)
            assertTrue(t > last)
            last = t
        }
        val error = host - t
        assertTrue(abs(error) < 100_000_000L, "error $error ns (uncorrected it would be 2000 ms)")
    }

    @Test
    fun `clock that runs ahead of the host is steered back and never goes backwards`() {
        val clock = SampleClock(period)
        var host = 0L
        var last = Long.MIN_VALUE
        var t = 0L
        repeat(2000) {
            host += 9_000_000L
            t = clock.stamp(host)
            assertTrue(t > last)
            last = t
        }
        val error = host - t
        assertTrue(abs(error) < 100_000_000L, "error $error ns")
    }

    @Test
    fun `reset re-anchors to the host clock but stays monotonic`() {
        val clock = SampleClock(period)
        var last = 0L
        repeat(10) { last = clock.stamp(it * period) }
        clock.reset()
        val first = clock.stamp(5_000_000_000L)
        assertEquals(5_000_000_000L, first)
        assertTrue(first > last)
        assertEquals(first + period, clock.stamp(5_000_000_000L))
        // Host time that went backwards still cannot produce a smaller stamp.
        clock.reset()
        assertTrue(clock.stamp(0L) > first + period)
    }
}

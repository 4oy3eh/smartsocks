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
        assertTrue(abs(error) < 300_000_000L, "error $error ns (uncorrected it would be 2000 ms)")
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
        assertTrue(abs(error) < 300_000_000L, "error $error ns")
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

    @Test
    fun `first stamp after reset is a full period after the last one`() {
        val clock = SampleClock(period)
        var last = 0L
        repeat(10) { last = clock.stamp(it * period) }
        clock.reset()
        // The host clock is behind the last stamp (stamps led it): no run of 1 ns steps may follow.
        val first = clock.stamp(0L)
        assertEquals(last + period, first)
        assertEquals(first + period, clock.stamp(0L))
    }

    @Test
    fun `device at half the nominal rate does not run away`() {
        val clock = SampleClock(period)
        var host = 0L
        var last = Long.MIN_VALUE
        var t = 0L
        repeat(3000) {
            // one minute at 50 Hz while 100 Hz was expected
            host += 2 * period
            t = clock.stamp(host)
            assertTrue(t > last)
            last = t
        }
        val error = host - t
        assertTrue(abs(error) < 1_500_000_000L, "error $error ns (uncorrected it would be 30 s)")
    }

    @Test
    fun `a stall followed by a burst of the buffered packets does not move the clock`() {
        val clock = SampleClock(period)
        var index = 0L
        fun sendAt(host: Long): Pair<Long, Long> = clock.stamp(host) to index++ * period
        var host = 0L
        repeat(300) {
            sendAt(host)
            host += period
        } // 3 s of normal flow
        host += 5_000_000_000L // 5 s without any packet ...
        repeat(500) { sendAt(host) } // ... then everything that piled up at once
        var lastPair = 0L to 0L
        repeat(300) {
            host += period
            lastPair = sendAt(host)
        }
        // Stamps are the device's own timeline (index * period), not shifted by the latency spike.
        val lead = lastPair.first - lastPair.second
        assertTrue(abs(lead) < 20_000_000L, "stamps off by $lead ns")
    }

    @Test
    fun `a gap with lost packets is re-anchored at once`() {
        val clock = SampleClock(period)
        var host = 0L
        repeat(300) {
            clock.stamp(host)
            host += period
        }
        host += 5_000_000_000L // packets lost, not delayed
        var t = 0L
        repeat(250) {
            // 2.5 s after the gap
            t = clock.stamp(host)
            host += period
        }
        val error = host - period - t
        assertTrue(abs(error) < 100_000_000L, "error $error ns")
    }

    @Test
    fun `long bursts do not make stamps lead the host`() {
        val clock = SampleClock(period)
        var maxLead = Long.MIN_VALUE
        // 40 packets (400 ms of data) arrive together every 400 ms.
        for (i in 0 until 4000) {
            val host = (i / 40 + 1) * 40 * period
            val t = clock.stamp(host)
            if (i > 1000) maxLead = maxOf(maxLead, t - host)
        }
        assertTrue(maxLead < 100_000_000L, "stamps lead the host by up to $maxLead ns")
    }
}

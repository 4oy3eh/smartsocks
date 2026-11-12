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
        // Evenly spaced, apart from the slow pull of the whole timeline towards the lower envelope.
        for (i in 1 until stamps.size) {
            val gap = stamps[i] - stamps[i - 1]
            assertTrue(gap in period * 3 / 4..period, "gap $gap at $i")
        }
        val inBurst = stamps.indices.count { it % 4 != 0 && stamps[it] - stamps[it - 1] == period }
        assertTrue(inBurst > 100, "bursts must not be smeared")
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
        assertTrue(abs(error) < 100_000_000L, "error $error ns (uncorrected it would be 30 s)")
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

    private fun assertFasterDeviceIsHandled(factor: Double) {
        val clock = SampleClock(period)
        val actual = (period / factor).toLong()
        var host = 0L
        var last = Long.MIN_VALUE
        var minStep = Long.MAX_VALUE
        var maxLateError = 0L
        val total = 30_000_000_000L / actual
        for (i in 0 until total) {
            host += actual
            val t = clock.stamp(host)
            if (last != Long.MIN_VALUE) minStep = minOf(minStep, t - last)
            last = t
            if (host > 20_000_000_000L) maxLateError = maxOf(maxLateError, abs(host - t))
        }
        assertTrue(minStep >= actual / 2, "x$factor: step of $minStep ns, actual period $actual ns")
        assertTrue(maxLateError < 500_000_000L, "x$factor: stamps off the host by $maxLateError ns after 20 s")
    }

    @Test
    fun `device twice as fast as nominal gets sane steps and tracks the host`() = assertFasterDeviceIsHandled(2.0)

    @Test
    fun `device one and a half times as fast as nominal gets sane steps and tracks the host`() =
        assertFasterDeviceIsHandled(1.5)

    @Test
    fun `two clocks with different first packet latency converge`() {
        val a = SampleClock(period)
        val b = SampleClock(period)
        var diff = Long.MAX_VALUE
        for (i in 0 until 1500) { // 15 s
            val host = i * period
            val ta = a.stamp(host)
            // The first packet of b was 40 ms late (inside the tolerance), the rest are on time.
            val tb = b.stamp(if (i == 0) host + 40_000_000L else host)
            diff = abs(ta - tb)
            if (i == 1000) assertTrue(diff < 20_000_000L, "feet still ${diff / 1_000_000} ms apart after 10 s")
        }
        assertTrue(diff < 20_000_000L)
    }
}

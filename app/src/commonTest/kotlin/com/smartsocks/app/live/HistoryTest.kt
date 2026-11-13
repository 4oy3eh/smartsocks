package com.smartsocks.app.live

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private val ms = 1_000_000L

    private fun state(t: Long, total: Double = 6.0, cop: Point? = Point(0.1, 0.2), foot: Foot = Foot.LEFT): FootState {
        val loads = DoubleArray(6) { total / 6 + it }
        return FootState(
            foot = foot,
            timestampNanos = t,
            resistanceOhms = DoubleArray(6) { 1e5 },
            loads = loads,
            totalLoad = total,
            shares = DoubleArray(6),
            cop = cop,
            rawCop = cop,
            baselineSource = BaselineSource.NONE,
        )
    }

    /** Samples every 10 ms (100 Hz) from [fromMs], total = index. */
    private fun FootHistory.fill(count: Int, fromMs: Long = 0, stepMs: Long = 10) {
        for (k in 0 until count) append(state((fromMs + k * stepMs) * ms, total = k.toDouble()))
    }

    @Test
    fun windowHoldsTheSamplesOfTheSpanPlusOneBeforeIt() {
        val h = FootHistory(capacity = 1024)
        h.fill(200) // 0 .. 1990 ms
        val w = HistoryWindow(512)
        w.copy(h, nowNanos = 1990 * ms, spanNanos = 1000 * ms)
        // (990, 1990] are 100 samples, plus 990 itself to enter from the left edge.
        assertEquals(101, w.size)
        assertEquals(-1.0f, w.t[0], 1e-6f)
        assertEquals(0f, w.t[w.size - 1])
        assertEquals(99f, w.total[0])
        assertEquals(199f, w.total[w.size - 1])
        assertTrue(w.breaks[0], "nothing to join the first point to")
        assertFalse(w.breaks[1])
        for (i in 1 until w.size) assertTrue(w.t[i] > w.t[i - 1])
    }

    @Test
    fun samplesNewerThanNowAreLeftOut() {
        val h = FootHistory(capacity = 64)
        h.fill(10)
        val w = HistoryWindow(64)
        w.copy(h, nowNanos = 45 * ms, spanNanos = 1000 * ms)
        assertEquals(5, w.size) // 0..40 ms
        assertEquals(4f, w.total[4])
    }

    @Test
    fun wrapAroundKeepsTheNewestCapacitySamplesInOrder() {
        val h = FootHistory(capacity = 100)
        h.fill(1234)
        assertEquals(100, h.size)
        assertEquals(1233 * 10 * ms, h.latestNanos)
        val w = HistoryWindow(100)
        w.copy(h, nowNanos = 1233 * 10 * ms, spanNanos = 100_000 * ms)
        // A reader takes at most half the ring: the writer's headroom.
        assertEquals(50, w.size)
        assertEquals(1184f, w.total[0])
        assertEquals(1233f, w.total[49])
        for (i in 1 until w.size) assertEquals(1f, w.total[i] - w.total[i - 1])
        assertEquals(h.readableSamples, w.size)
    }

    @Test
    fun windowNeverExceedsItsOwnCapacity() {
        val h = FootHistory(capacity = 1000)
        h.fill(400)
        val w = HistoryWindow(30)
        w.copy(h, nowNanos = 399 * 10 * ms, spanNanos = 10_000 * ms)
        assertEquals(30, w.size)
        assertEquals(399f, w.total[29], "the newest samples win")
    }

    @Test
    fun copIsNaNInTheAirAndContactFollowsIt() {
        val h = FootHistory(capacity = 16)
        h.append(state(0, cop = Point(0.5, -0.25)))
        h.append(state(10 * ms, cop = null))
        val w = HistoryWindow(16)
        w.copy(h, 10 * ms, 1000 * ms)
        assertEquals(0.5f, w.copX[0])
        assertEquals(-0.25f, w.copY[0])
        assertTrue(w.contact[0])
        assertTrue(w.copX[1].isNaN())
        assertTrue(w.copY[1].isNaN())
        assertFalse(w.contact[1])
        assertEquals(6f, w.loads[5][0], 1e-6f) // 6/6 + 5
    }

    @Test
    fun longPausesAndMarkedBreaksStartNewSegments() {
        val h = FootHistory(capacity = 64, maxGapNanos = 100 * ms)
        h.fill(3) // 0, 10, 20
        h.append(state(500 * ms)) // after a gap
        h.append(state(510 * ms))
        h.markBreak() // a reconnect
        h.append(state(520 * ms))
        val w = HistoryWindow(64)
        w.copy(h, 520 * ms, 10_000 * ms)
        assertEquals(listOf(true, false, false, true, false, true), (0 until w.size).map { w.breaks[it] })
    }

    @Test
    fun timeGoingBackwardsClearsTheHistory() {
        val h = FootHistory(capacity = 64)
        h.fill(10, fromMs = 1000)
        h.append(state(5 * ms, total = 42.0))
        assertEquals(1, h.size)
        val w = HistoryWindow(64)
        w.copy(h, 5 * ms, 1000 * ms)
        assertEquals(1, w.size)
        assertEquals(42f, w.total[0])
        assertTrue(w.breaks[0])
    }

    @Test
    fun clearHidesEverythingUntilNewSamplesArrive() {
        val h = FootHistory(capacity = 64)
        h.fill(10)
        h.clear()
        assertEquals(0, h.size)
        assertNull(h.latestNanos)
        val w = HistoryWindow(64)
        w.copy(h, 90 * ms, 1000 * ms)
        assertEquals(0, w.size)
        h.append(state(200 * ms))
        w.copy(h, 200 * ms, 1000 * ms)
        assertEquals(1, w.size)
    }

    @Test
    fun emptyHistoryAndEmptyWindow() {
        val w = HistoryWindow(8)
        w.copy(FootHistory(capacity = 8), 0, 1000 * ms)
        assertEquals(0, w.size)
        val h = FootHistory(capacity = 8)
        h.fill(3)
        w.copy(h, nowNanos = 5_000 * ms, spanNanos = 1000 * ms) // all older than the window except the one before
        assertEquals(1, w.size)
        assertTrue(w.t[0] < -1f)
    }

    @Test
    fun aWriterLappingTheCopyIsDroppedIncludingTheSlotItMayBeWriting() {
        val w = HistoryWindow(16)
        var next = 20

        // The reader takes at most 8 of 16: samples 12..19. 7 more during the copy: the writer's next slot is
        // sample 11's, which was not copied. Nothing is lost.
        val h = FootHistory(capacity = 16)
        h.fill(20)
        w.beforeRecheck = { repeat(7) { h.append(state(next++ * 10 * ms, total = -1.0)) } }
        w.copy(h, nowNanos = 19 * 10 * ms, spanNanos = 100_000 * ms)
        assertEquals(8, w.size)
        assertEquals(12f, w.total[0])
        assertEquals(19f, w.total[7])

        // 8 more: the slot of sample 12 is the one the writer fills next (it may be half written right now).
        val h2 = FootHistory(capacity = 16)
        h2.fill(20)
        next = 20
        w.beforeRecheck = { repeat(8) { h2.append(state(next++ * 10 * ms, total = -1.0)) } }
        w.copy(h2, nowNanos = 19 * 10 * ms, spanNanos = 100_000 * ms)
        assertEquals(7, w.size)
        assertEquals(13f, w.total[0], "the suspect first sample is dropped")
        assertEquals(19f, w.total[6])
        assertTrue(w.breaks[0])
        assertTrue(w.truncated)
        assertTrue((0 until w.size).none { w.total[it] < 0f }, "nothing the racing writer wrote was taken")
    }

    @Test
    fun maximaLeaveOutTheSampleBeforeTheWindow() {
        val h = FootHistory(capacity = 64)
        h.append(state(0, total = 900.0)) // an old peak, e.g. the last sample before a disconnect
        h.append(state(5_000 * ms, total = 30.0))
        h.append(state(5_010 * ms, total = 40.0))
        val w = HistoryWindow(64)
        w.copy(h, nowNanos = 5_010 * ms, spanNanos = 1_000 * ms)
        assertEquals(3, w.size, "the old sample is still copied, so that the line enters from the left")
        assertEquals(1, w.firstInside)
        assertEquals(40f, w.maxTotal())
        assertEquals(40f / 6 + 5, w.maxLoad(), 1e-4f)

        w.copy(h, nowNanos = 20_000 * ms, spanNanos = 1_000 * ms) // only the sample before the window is left
        assertEquals(1, w.size)
        assertTrue(w.maxTotal().isNaN(), "nothing inside the window: the scale may shrink")
    }

    @Test
    fun aWindowWhoseSamplesAreGoneIsTruncated() {
        val h = FootHistory(capacity = 64) // readable: 32 samples, 0.32 s at 100 Hz
        h.fill(200)
        val w = HistoryWindow(64)
        // Live: the newest 0.2 s are all there.
        w.copy(h, nowNanos = 1_990 * ms, spanNanos = 200 * ms)
        assertFalse(w.truncated)
        // 1 s: more than the readable part.
        w.copy(h, nowNanos = 1_990 * ms, spanNanos = 1_000 * ms)
        assertTrue(w.truncated)
        // A frozen moment that has been overwritten since (paused, then another window chosen).
        w.copy(h, nowNanos = 500 * ms, spanNanos = 200 * ms)
        assertEquals(0, w.size)
        assertTrue(w.truncated)
        // Not full yet: the start of the recording is not "missing".
        val young = FootHistory(capacity = 64)
        young.fill(10)
        w.copy(young, nowNanos = 90 * ms, spanNanos = 1_000 * ms)
        assertFalse(w.truncated)
    }

    @Test
    fun breakRequestsAreNeverLost() {
        val h = FootHistory(capacity = 64)
        h.fill(3)
        h.markBreak()
        h.markBreak() // two requests before the next sample: one break
        h.append(state(30 * ms))
        h.append(state(40 * ms))
        h.markBreak()
        h.append(state(50 * ms))
        val w = HistoryWindow(64)
        w.copy(h, 50 * ms, 10_000 * ms)
        assertEquals(listOf(true, false, false, true, false, true), (0 until w.size).map { w.breaks[it] })
    }

    @Test
    fun bothFeetAreCutAtTheSameNow() {
        val history = LiveHistory(capacity = 256)
        // The right foot started later and its last sample is a little older.
        for (k in 0 until 100) history.left.append(state(k * 10 * ms, foot = Foot.LEFT))
        for (k in 30 until 98) history.right.append(state(k * 10 * ms + 3 * ms, foot = Foot.RIGHT))
        val frame = ChartFrame(capacity = 128)
        frame.update(history, nowNanos = 1000 * ms, spanSeconds = 5)
        assertEquals(1000 * ms, frame.nowNanos)
        assertEquals(-0.01f, frame.left.t[frame.left.size - 1], 1e-6f) // 990 ms
        assertEquals(-0.027f, frame.right.t[frame.right.size - 1], 1e-6f) // 973 ms
        assertEquals(-0.697f, frame.right.t[0], 1e-6f) // 303 ms
        assertEquals(5f, frame.spanSeconds)
    }

    @Test
    fun maximaIgnoreAFootWithoutData() {
        val history = LiveHistory(capacity = 64)
        for (k in 0 until 10) history.left.append(state(k * 10 * ms, total = k * 10.0))
        val frame = ChartFrame(capacity = 32)
        frame.update(history, nowNanos = 90 * ms, spanSeconds = 5)
        assertEquals(0, frame.right.size)
        assertTrue(frame.right.maxTotal().isNaN())
        assertEquals(90f, frame.maxTotal(), "the right foot's missing data must not hide the left's peak")
        assertEquals(90f / 6 + 5, frame.left.maxLoad(), 1e-4f)
        frame.update(LiveHistory(capacity = 8), 0, 5)
        assertTrue(frame.maxTotal().isNaN())
    }
}

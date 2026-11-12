package com.smartsocks.app.live

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSnapshotTest {
    private fun state(t: Long, cop: Point?, maxLoad: Double = 10.0): FootState {
        val loads = DoubleArray(6) { if (it == 0) maxLoad else 1.0 }
        return FootState(
            foot = Foot.LEFT,
            timestampNanos = t,
            resistanceOhms = DoubleArray(6) { 1e5 },
            loads = loads,
            totalLoad = loads.sum(),
            shares = DoubleArray(6),
            cop = cop,
            rawCop = cop,
            baselineSource = BaselineSource.NONE,
        )
    }

    private val ms = 1_000_000L

    @Test
    fun trailAddsEachSampleOnceAndAgesItOutAfterTheWindow() {
        val trail = CopTrail(windowNanos = 1_000 * ms)
        val s = state(1, Point(0.1, 0.2))
        assertEquals(1, trail.update(0, s).size)
        assertEquals(1, trail.update(16 * ms, s).size, "same sample: not added again")

        val points = trail.update(500 * ms, state(2, Point(0.3, 0.4)))
        assertEquals(2, points.size)
        assertEquals(0.5f, points[0].age, 1e-6f)
        assertEquals(0f, points[1].age)
        assertEquals(0.3, points[1].x)

        // Foot in the air: nothing new, the old points fade out.
        assertEquals(1, trail.update(1_000 * ms, state(3, null)).size)
        assertTrue(trail.update(1_500 * ms, state(4, null)).isEmpty())
    }

    @Test
    fun aNewStepStartsANewSegment() {
        val trail = CopTrail(windowNanos = 1_000 * ms)
        trail.update(0, state(1, Point(0.0, -1.0)))
        trail.update(10 * ms, state(2, Point(0.0, 1.0)))
        trail.update(20 * ms, state(3, null)) // lifted
        val points = trail.update(30 * ms, state(4, Point(0.0, -1.0)))
        assertEquals(listOf(true, false, true), points.map { it.startsSegment })
    }

    @Test
    fun trailIsClearedWhenTheFootStopsStreamingAndIsBounded() {
        val trail = CopTrail(windowNanos = 1_000 * ms, capacity = 4)
        for (i in 0 until 10) trail.update(i * ms, state(i.toLong(), Point(i.toDouble(), 0.0)))
        val points = trail.update(10 * ms, state(9, Point(9.0, 0.0)))
        assertEquals(listOf(6.0, 7.0, 8.0, 9.0), points.map { it.x })

        assertTrue(trail.update(11 * ms, null).isEmpty())
        assertEquals(1, trail.update(12 * ms, state(9, Point(9.0, 0.0))).size, "a cleared trail accepts samples")
    }

    @Test
    fun loadScaleFollowsThePeakOfBothFeetAndDecaysToTheFloor() {
        val scale = LoadScale(floor = 40.0, halfLifeNanos = 1_000 * ms)
        assertEquals(40.0, scale.update(0, state(0, null, maxLoad = 5.0), null))
        assertEquals(200.0, scale.update(0, state(0, null, maxLoad = 5.0), state(0, null, maxLoad = 200.0)))
        assertEquals(100.0, scale.update(1_000 * ms), 1e-9)
        assertEquals(40.0, scale.update(10_000 * ms))
    }
}

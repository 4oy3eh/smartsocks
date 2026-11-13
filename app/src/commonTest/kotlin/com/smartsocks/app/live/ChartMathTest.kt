package com.smartsocks.app.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartMathTest {
    private val ms = 1_000_000L

    @Test
    fun niceNumbers() {
        assertEquals(50f, NiceNumbers.ceilMax(41f))
        assertEquals(60f, NiceNumbers.ceilMax(55f))
        assertEquals(100f, NiceNumbers.ceilMax(81f))
        assertEquals(150f, NiceNumbers.ceilMax(110f))
        assertEquals(1f, NiceNumbers.ceilMax(1f))
        assertEquals(0.3f, NiceNumbers.ceilMax(0.26f), 1e-6f)
        assertEquals(2500f, NiceNumbers.ceilMax(2200f))
        assertEquals(100f, NiceNumbers.tickStep(300f))
        assertEquals(20f, NiceNumbers.tickStep(80f))
        assertEquals(10f, NiceNumbers.tickStep(50f))
        assertEquals(50f, NiceNumbers.tickStep(150f))
        assertEquals(25f, NiceNumbers.ceilStep(21f))
        assertEquals(1000f, NiceNumbers.tickStep(2500f, maxTicks = 3), "short plot: 0, 1000, 2000")
    }

    @Test
    fun autoScaleGrowsAtOnceButShrinksOnlyAfterADelay() {
        val scale = AutoScale(floor = 20f, shrinkDelayNanos = 1000 * ms)
        assertEquals(20f, scale.max)
        assertEquals(20f, scale.update(Float.NaN, 0), "no data: the floor")
        assertEquals(400f, scale.update(330f, 10 * ms), "330 + 10 % headroom fits 400")
        // A slightly smaller peak: no change at all (no jitter).
        assertEquals(400f, scale.update(300f, 20 * ms))
        // Much smaller: only once it has stayed small for the delay.
        assertEquals(400f, scale.update(100f, 30 * ms))
        assertEquals(400f, scale.update(100f, 900 * ms))
        assertEquals(150f, scale.update(100f, 1030 * ms))
    }

    @Test
    fun autoScaleShrinkTimerRestartsWhenTheDataComesBack() {
        val scale = AutoScale(floor = 20f, shrinkDelayNanos = 1000 * ms)
        scale.update(330f, 0)
        scale.update(50f, 100 * ms)
        scale.update(300f, 600 * ms) // a step: big again
        assertEquals(400f, scale.update(50f, 1200 * ms), "the timer restarted at 1200 ms")
        assertEquals(400f, scale.update(50f, 2100 * ms))
        assertEquals(60f, scale.update(50f, 2200 * ms))
    }

    @Test
    fun autoScaleResetFitsAtOnce() {
        val scale = AutoScale(floor = 20f, shrinkDelayNanos = 1000 * ms)
        scale.update(330f, 0)
        assertEquals(150f, scale.reset(100f), "no waiting for the shrink delay")
        assertEquals(20f, scale.reset(Float.NaN))
        assertEquals(20f, scale.update(5f, 10 * ms))
    }

    @Test
    fun autoScaleNeverGoesBelowTheFloor() {
        val scale = AutoScale(floor = 50f, shrinkDelayNanos = 0)
        scale.update(400f, 0)
        scale.update(1f, 1)
        assertEquals(50f, scale.update(1f, 2))
    }

    private class Recorder : LineSink {
        val ops = mutableListOf<String>()

        override fun moveTo(x: Float, y: Float) {
            ops += "M $x $y"
        }

        override fun lineTo(x: Float, y: Float) {
            ops += "L $x $y"
        }
    }

    private fun reduce(points: List<Triple<Float, Float, Boolean>>): List<String> {
        val r = Recorder()
        val reducer = LineReducer()
        reducer.start(r)
        for ((x, y, b) in points) reducer.point(x, y, b)
        reducer.finish()
        return r.ops
    }

    @Test
    fun sparsePointsArePassedThrough() {
        val ops = reduce(listOf(Triple(0.5f, 1f, true), Triple(2.5f, 3f, false), Triple(4.5f, 2f, false)))
        assertEquals(listOf("M 0.5 1.0", "L 0.5 1.0", "L 2.5 3.0", "L 4.5 2.0"), ops)
    }

    @Test
    fun densePointsKeepFirstExtremesAndLastPerColumn() {
        // Ten samples inside pixel column 3: first 5, then a dip to 1, a peak at 9, last 4.
        val ys = listOf(5f, 4f, 1f, 3f, 9f, 6f, 5f, 5f, 4f, 4f)
        val pts = ys.mapIndexed { i, y -> Triple(3f + i * 0.09f, y, i == 0) }
        val ops = reduce(pts)
        assertEquals(4, ops.size)
        assertTrue(ops[0].startsWith("M 3.0 5.0"))
        assertTrue(ops[1].endsWith(" 1.0"), "$ops")
        assertTrue(ops[2].endsWith(" 9.0"), "$ops")
        assertTrue(ops[3].endsWith(" 4.0"), "$ops")
    }

    @Test
    fun peakBeforeDipKeepsTheirOrder() {
        val ys = listOf(5f, 9f, 1f, 5f)
        val ops = reduce(ys.mapIndexed { i, y -> Triple(7f + i * 0.2f, y, false) })
        assertEquals(listOf(5f, 9f, 1f, 5f), ops.map { it.substringAfterLast(' ').toFloat() })
    }

    @Test
    fun nanAndBreaksLeaveGaps() {
        val ops = reduce(
            listOf(
                Triple(0f, 1f, true),
                Triple(1f, 2f, false),
                Triple(2f, Float.NaN, false),
                Triple(3f, 4f, false),
                Triple(4f, 5f, false),
                Triple(5f, 6f, true),
            ),
        )
        assertEquals(
            listOf(
                "M 0.0 1.0",
                "L 0.0 1.0",
                "L 1.0 2.0",
                "M 3.0 4.0",
                "L 3.0 4.0",
                "L 4.0 5.0",
                "M 5.0 6.0",
                "L 5.0 6.0",
            ),
            ops,
        )
    }

    @Test
    fun thousandsOfSamplesBecomeAFewPointsPerColumn() {
        val r = Recorder()
        val reducer = LineReducer()
        reducer.start(r)
        // 6000 samples (30 s at 200 Hz) over 300 px.
        for (i in 0 until 6000) reducer.point(i * 0.05f, (i % 37).toFloat(), i == 0)
        reducer.finish()
        assertTrue(r.ops.size <= 4 * 300 + 1, "${r.ops.size}")
    }

    @Test
    fun sensorNamesFollowTheLegacyLayout() {
        assertEquals(
            listOf(
                "Forefoot medial",
                "Forefoot lateral",
                "Midfoot medial",
                "Midfoot lateral",
                "Heel medial",
                "Heel lateral",
            ),
            SensorNames.Default.full,
        )
        assertEquals(
            listOf("Fore med", "Fore lat", "Mid med", "Mid lat", "Heel med", "Heel lat"),
            SensorNames.Default.short,
        )
    }
}

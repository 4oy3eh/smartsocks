package com.smartsocks.core.analytics

import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.model.SensorLayout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val EPS = 1e-9

/** Resistance that gives exactly [microSiemens] of conductance. */
private fun ohmsFor(microSiemens: Double) = 1e6 / microSiemens

private fun frame(foot: Foot, vararg microSiemens: Double, t: Long = 0) =
    SensorFrame(foot, t, DoubleArray(6) { ohmsFor(microSiemens[it]) })

private fun assertPoint(expected: Point, actual: Point?, eps: Double = 1e-6) {
    assertNotNull(actual)
    assertTrue(abs(expected.x - actual.x) < eps && abs(expected.y - actual.y) < eps, "expected $expected, was $actual")
}

class LoadModelTest {
    @Test
    fun `load is conductance above baseline`() {
        val baseline = Baseline(DoubleArray(6) { ohmsFor(10.0) })
        val loads = LoadModel.loads(frame(Foot.LEFT, 110.0, 10.0, 5.0, 60.0, 10.0, 10.0), baseline)
        assertEquals(100.0, loads[0], EPS)
        assertEquals(0.0, loads[1], EPS)
        assertEquals(0.0, loads[2], EPS) // below baseline is clamped, never negative
        assertEquals(50.0, loads[3], EPS)
    }

    @Test
    fun `zero resistance does not produce infinity`() {
        val loads = LoadModel.loads(SensorFrame(Foot.LEFT, 0, DoubleArray(6)), null)
        assertTrue(loads.all { it.isFinite() })
    }
}

class CenterOfPressureTest {
    private val positions = SensorLayout.Legacy.positions(Foot.LEFT)

    @Test
    fun `single loaded sensor puts COP on that sensor`() {
        for (i in 0 until 6) {
            val loads = DoubleArray(6).also { it[i] = 300.0 }
            assertPoint(positions[i], CenterOfPressure.compute(loads, positions, 1.0)?.position)
        }
    }

    @Test
    fun `equal load is the centre`() {
        assertPoint(Point(0.0, 0.0), CenterOfPressure.compute(DoubleArray(6) { 50.0 }, positions, 1.0)?.position)
    }

    @Test
    fun `COP stays between loaded sensors - legacy formula left the foot here`() {
        val loads = doubleArrayOf(100.0, 0.0, 100.0, 0.0, 0.0, 0.0)
        // Legacy: x = 0.259 + 0.259 = 0.518, outside the insole.
        assertPoint(Point(0.259, 0.483), CenterOfPressure.compute(loads, positions, 1.0)?.position)
    }

    @Test
    fun `COP does not depend on how hard you press`() {
        val loads = doubleArrayOf(10.0, 20.0, 5.0, 0.0, 40.0, 25.0)
        val a = CenterOfPressure.compute(loads, positions, 1.0)?.position
        val b = CenterOfPressure.compute(DoubleArray(6) { loads[it] * 7 }, positions, 1.0)?.position
        assertPoint(a!!, b)
    }

    @Test
    fun `no COP when the foot is in the air`() {
        assertNull(CenterOfPressure.compute(DoubleArray(6) { 1.0 }, positions, 20.0))
        assertNull(CenterOfPressure.compute(DoubleArray(6), positions, 0.0))
    }

    @Test
    fun `right foot layout is mirrored`() {
        val left = SensorLayout.Legacy.positions(Foot.LEFT)
        val right = SensorLayout.Legacy.positions(Foot.RIGHT)
        for (i in 0 until 6) assertEquals(Point(-left[i].x, left[i].y), right[i])
    }
}

class BaselineCalibratorTest {
    @Test
    fun `median ignores a spike`() {
        val calibrator = BaselineCalibrator(5)
        val values = listOf(10.0, 11.0, 9_000.0, 10.0, 12.0)
        values.forEachIndexed { i, v ->
            val done = calibrator.add(SensorFrame(Foot.LEFT, 0, DoubleArray(6) { v }))
            assertEquals(i == values.lastIndex, done)
        }
        assertEquals(11.0, calibrator.result().unloadedOhms[0])
    }
}

class FootProcessorTest {
    @Test
    fun `calibration then load gives contact, shares and COP`() {
        val processor = FootProcessor(Foot.LEFT, settings = ProcessorSettings(contactThreshold = 20.0))
        processor.startCalibration(3)
        repeat(3) { processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0)) }
        assertNotNull(processor.baseline)
        assertNull(processor.calibrationProgress)

        val lifted = processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0))
        assertEquals(false, lifted.inContact)
        assertEquals(0.0, lifted.shares.sum())

        val heel = processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 105.0, 105.0, t = 10_000_000))
        assertTrue(heel.inContact)
        assertEquals(1.0, heel.shares.sum(), EPS)
        assertEquals(0.5, heel.shares[4], EPS)
        assertPoint(Point(0.0, -0.966), heel.rawCop)
    }
}

class OneEuroFilterTest {
    @Test
    fun `constant signal passes unchanged`() {
        val f = OneEuroFilter()
        repeat(100) { assertEquals(3.0, f.filter(3.0, it * 10_000_000L), EPS) }
    }

    @Test
    fun `step converges and noise is reduced`() {
        val f = OneEuroFilter(minCutoffHz = 1.0, beta = 0.0)
        f.filter(0.0, 0)
        var y = 0.0
        for (i in 1..300) y = f.filter(1.0, i * 10_000_000L) // 3 s at 100 Hz
        assertEquals(1.0, y, 1e-3)

        val g = OneEuroFilter(minCutoffHz = 1.0, beta = 0.0)
        var maxDeviation = 0.0
        for (i in 0..300) {
            val noisy = if (i % 2 == 0) 0.1 else -0.1
            val out = g.filter(noisy, i * 10_000_000L)
            if (i > 50) maxDeviation = maxOf(maxDeviation, abs(out))
        }
        assertTrue(maxDeviation < 0.03, "max deviation $maxDeviation")
    }
}

class BalanceCalculatorTest {
    private fun state(foot: Foot, total: Double, cop: Point?) =
        FootState(foot, 0, DoubleArray(6), DoubleArray(6), total, DoubleArray(6), cop, cop, true)

    @Test
    fun `splits load and weights combined COP`() {
        val b = BalanceCalculator.compute(
            state(Foot.LEFT, 300.0, Point(0.0, 0.0)),
            state(Foot.RIGHT, 100.0, Point(0.0, 1.0)),
            stanceHalfWidth = 1.0,
        )
        assertNotNull(b)
        assertEquals(0.75, b.left, EPS)
        assertEquals(0.25, b.right, EPS)
        assertPoint(Point(-0.5, 0.25), b.combinedCop)
    }

    @Test
    fun `one foot only`() {
        val b = BalanceCalculator.compute(null, state(Foot.RIGHT, 100.0, Point(0.1, 0.2)), 1.0)
        assertNotNull(b)
        assertEquals(1.0, b.right, EPS)
        assertPoint(Point(1.1, 0.2), b.combinedCop)
    }

    @Test
    fun `nothing loaded`() {
        assertNull(BalanceCalculator.compute(null, null))
    }
}

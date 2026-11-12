package com.smartsocks.core.analytics

import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.model.SensorLayout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `zero resistance is clamped to the 100 ohm floor`() {
        val loads = LoadModel.loads(SensorFrame(Foot.LEFT, 0, DoubleArray(6)), null)
        assertTrue(loads.all { it == 1e6 / 100.0 }, "loads were ${loads.toList()}")
        val glitch = LoadModel.loads(SensorFrame(Foot.LEFT, 0, DoubleArray(6) { 50.0 }), null)
        assertEquals(loads[0], glitch[0], EPS)
    }

    @Test
    fun `open circuit has no load, with or without baseline`() {
        val open = SensorFrame(Foot.LEFT, 0, DoubleArray(6) { Double.POSITIVE_INFINITY })
        assertTrue(LoadModel.loads(open, null).all { it == 0.0 })
        assertTrue(LoadModel.loads(open, Baseline(DoubleArray(6) { ohmsFor(10.0) })).all { it == 0.0 })
        val calibrator = BaselineCalibrator(3)
        repeat(3) { calibrator.add(open) }
        val baseline = calibrator.result()
        assertTrue(baseline.unloadedOhms.all { it == Double.POSITIVE_INFINITY })
        assertTrue(LoadModel.loads(open, baseline).all { it == 0.0 })
        // A finite reading above an open-circuit baseline is all load.
        assertEquals(10.0, LoadModel.loads(frame(Foot.LEFT, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0), baseline)[0], EPS)
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

    @Test
    fun `median of mixed finite and infinite samples`() {
        fun median(vararg values: Double): Double {
            val c = BaselineCalibrator(values.size)
            for (v in values) c.add(SensorFrame(Foot.LEFT, 0, DoubleArray(6) { v }))
            return c.result().unloadedOhms[0]
        }
        val inf = Double.POSITIVE_INFINITY
        assertEquals(30.0, median(10.0, 20.0, 30.0, inf, inf))
        assertEquals(inf, median(10.0, 20.0, inf, inf, inf))
        val baseline = Baseline(DoubleArray(6) { median(10_000.0, 20_000.0, 30_000.0, inf, inf) })
        val loads = LoadModel.loads(frame(Foot.LEFT, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0), baseline)
        assertEquals(100.0 - 1e6 / 30_000.0, loads[0], EPS)
    }

    @Test
    fun `sample count must be positive`() {
        assertFailsWith<IllegalArgumentException> { BaselineCalibrator(0) }
        assertFailsWith<IllegalArgumentException> { BaselineCalibrator(-3) }
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

    @Test
    fun `startCalibration validates and cancelCalibration keeps the baseline`() {
        val processor = FootProcessor(Foot.LEFT)
        assertFailsWith<IllegalArgumentException> { processor.startCalibration(0) }
        processor.startCalibration(2)
        processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0))
        processor.cancelCalibration()
        assertNull(processor.calibrationProgress)
        assertNull(processor.baseline)

        processor.startCalibration(1)
        processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0))
        assertNotNull(processor.baseline)
        processor.startCalibration(5)
        processor.cancelCalibration()
        assertNotNull(processor.baseline)
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

    @Test
    fun `a foot in the air counts as zero load however noisy`() {
        val air = state(Foot.RIGHT, 5.0, null)
        val left = state(Foot.LEFT, 1.0, null)
        assertNull(BalanceCalculator.compute(left, air))

        val b = BalanceCalculator.compute(state(Foot.LEFT, 100.0, Point(0.0, 0.0)), air)
        assertNotNull(b)
        assertEquals(1.0, b.left, EPS)
        assertEquals(0.0, b.right, EPS)
    }
}

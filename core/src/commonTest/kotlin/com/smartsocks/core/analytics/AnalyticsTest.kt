package com.smartsocks.core.analytics

import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.FootRegion
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.model.SensorLayout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        // Legacy: x = 0.259 + 0.259 = 0.518, outside the insole. Channels 1 and 3 are on the same side.
        assertPoint(Point(positions[0].x, 0.483), CenterOfPressure.compute(loads, positions, 1.0)?.position)
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

    @Test
    fun `sides and rows come from the positions`() {
        val layout = SensorLayout.Legacy
        val medial = (0 until 6).filter { layout.isMedial(it) }
        assertEquals(if (SensorLayout.ODD_CHANNELS_MEDIAL) listOf(0, 2, 4) else listOf(1, 3, 5), medial)
        assertEquals(
            listOf(FootRegion.FORE, FootRegion.FORE, FootRegion.MID, FootRegion.MID, FootRegion.HEEL, FootRegion.HEEL),
            (0 until 6).map { layout.region(it) },
        )
        // Medial faces the other foot: +x on the left insole, -x on the right one.
        for (i in medial) {
            assertTrue(layout.positions(Foot.LEFT)[i].x > 0)
            assertTrue(layout.positions(Foot.RIGHT)[i].x < 0)
        }
        // Any layout: the side follows the sign of x on the left insole, not the channel number.
        val flipped = layout.mirrored()
        assertEquals((0 until 6).map { !layout.isMedial(it) }, (0 until 6).map { flipped.isMedial(it) })
        assertEquals(SensorLayout.legacy(!SensorLayout.ODD_CHANNELS_MEDIAL), flipped)
        assertEquals(0.966, layout.rowY(FootRegion.FORE), 1e-12)
        assertEquals(0.0, layout.rowY(FootRegion.MID), 1e-12)
    }

    @Test
    fun `rows are found by position, whatever the units and channel order`() {
        // Millimetres, rows not level, channels in another order: heel first.
        val mm = SensorLayout(
            listOf(
                Point(-25.0, -110.0),
                Point(22.0, -105.0),
                Point(30.0, 8.0),
                Point(-28.0, -6.0),
                Point(-30.0, 118.0),
                Point(35.0, 125.0),
            ),
        )
        assertEquals(
            listOf(FootRegion.HEEL, FootRegion.HEEL, FootRegion.MID, FootRegion.MID, FootRegion.FORE, FootRegion.FORE),
            (0 until 6).map { mm.region(it) },
        )
        assertEquals(listOf(false, true, true, false, false, true), (0 until 6).map { mm.isMedial(it) })
        assertEquals(121.5, mm.rowY(FootRegion.FORE), 1e-12)
        assertEquals(-107.5, mm.rowY(FootRegion.HEEL), 1e-12)
    }

    @Test
    fun `a row needs one medial and one lateral sensor`() {
        val positions = SensorLayout.Legacy.positions(Foot.LEFT)
        val sameSide = positions.toMutableList().apply { this[1] = Point(-this[1].x, this[1].y) }
        assertFailsWith<IllegalArgumentException> { SensorLayout(sameSide) }
        val onTheLine = positions.toMutableList().apply { this[0] = Point(0.0, this[0].y) }
        assertFailsWith<IllegalArgumentException> { SensorLayout(onTheLine) }
        assertFailsWith<IllegalArgumentException> { SensorLayout(positions.take(5)) }
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
        val processor = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 0.0))
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

    @Test
    fun `updateSettings keeps the calibrated baseline and applies new thresholds`() {
        val processor = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 0.0))
        processor.startCalibration(1)
        processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0))
        val baseline = processor.baseline
        val press = frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 35.0, 35.0, t = 10_000_000)
        assertTrue(processor.process(press).inContact, "60 µS is above the default 20 µS")

        val strict = ProcessorSettings(contactOnThreshold = 100.0, contactOffThreshold = 80.0, autoZeroSeconds = 0.0)
        processor.updateSettings(strict)
        assertEquals(strict, processor.settings)
        assertEquals(baseline, processor.baseline)
        val after = processor.process(press)
        assertFalse(after.inContact, "60 µS is below the new off threshold")
        assertEquals(BaselineSource.CALIBRATED, after.baselineSource)
        assertEquals(60.0, after.totalLoad, 1e-6)
    }

    @Test
    fun `updateSettings keeps a calibration in progress and can switch auto-zero off`() {
        val processor = FootProcessor(Foot.LEFT)
        processor.startCalibration(2)
        processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0))
        processor.updateSettings(ProcessorSettings(autoZeroSeconds = 0.0, copMinCutoffHz = 3.0))
        assertEquals(0.5, processor.calibrationProgress)
        processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, t = 10_000_000))
        assertNotNull(processor.baseline)
        processor.setBaseline(null)
        val state = processor.process(frame(Foot.LEFT, 5.0, 5.0, 5.0, 5.0, 5.0, 5.0, t = 20_000_000))
        assertEquals(BaselineSource.NONE, state.baselineSource)
    }
}

class AutoZeroTest {
    private fun lifted(t: Long) = frame(Foot.LEFT, 8.0, 8.0, 8.0, 8.0, 8.0, 8.0, t = t)

    private fun loaded(t: Long, microSiemens: Double = 308.0) =
        frame(Foot.LEFT, microSiemens, microSiemens, microSiemens, 8.0, 8.0, 8.0, t = t)

    /** [seconds] of 100 Hz stride-like data: half a second loaded, half lifted. Returns the last state. */
    private fun walk(p: FootProcessor, startNanos: Long, seconds: Int): FootState {
        var state: FootState? = null
        for (i in 0 until seconds * 100) {
            val t = startNanos + i * 10_000_000L
            state = p.process(if ((i / 50) % 2 == 0) loaded(t) else lifted(t))
        }
        return state!!
    }

    @Test
    fun `walking teaches the baseline from the swing phase`() {
        val p = FootProcessor(Foot.LEFT)
        assertEquals(BaselineSource.NONE, p.process(lifted(0)).baselineSource)
        walk(p, 10_000_000L, 3)
        val stance = p.process(loaded(3_100_000_000))
        assertEquals(BaselineSource.AUTO, stance.baselineSource)
        // Stance: 300 uS above the 8 uS leakage on three sensors.
        assertEquals(900.0, stance.totalLoad, 1e-6)
        assertTrue(stance.inContact)
    }

    @Test
    fun `baseline follows the leakage and forgets old minima after the window`() {
        val p = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 4.0))

        // Strides with 2 uS leakage, then a stride with 8 uS leakage.
        fun leak(us: Double, t: Long) = frame(Foot.LEFT, us, us, us, us, us, us, t = t)
        for (i in 0 until 100) {
            p.process(
                if ((i / 25) % 2 ==
                    0
                ) {
                    leak(300.0, i * 10_000_000L)
                } else {
                    leak(2.0, i * 10_000_000L)
                },
            )
        }
        p.process(leak(300.0, 1_000_000_000))
        // The old 2 uS minimum is still inside the window ...
        assertEquals(36.0, p.process(leak(8.0, 1_010_000_000)).totalLoad, 1e-6)
        // ... and gone 5 s later (a swing keeps the baseline learning).
        p.process(leak(300.0, 5_500_000_000))
        assertEquals(0.0, p.process(leak(8.0, 5_510_000_000)).totalLoad, 1e-6)
    }

    @Test
    fun `walking removes leakage only up to the cap`() {
        val p = FootProcessor(Foot.LEFT)
        var last: FootState? = null
        for (i in 0 until 400) { // 100 uS preload when lifted, 400 uS when loaded
            val us = if ((i / 50) % 2 == 0) 400.0 else 100.0
            last = p.process(frame(Foot.LEFT, us, us, us, us, us, us, t = i * 10_000_000L))
        }
        val cap = ProcessorSettings().autoZeroMaxMicroSiemens
        assertTrue(last!!.loads.all { abs(it - (100.0 - cap)) < 1e-6 }, "loads ${last.loads.toList()}")
    }

    @Test
    fun `quiet standing keeps loads and COP unchanged`() {
        val p = FootProcessor(Foot.LEFT)
        val g = doubleArrayOf(40.0, 40.0, 20.0, 20.0, 300.0, 300.0)
        val first = p.process(frame(Foot.LEFT, *g, t = 0))
        var last = first
        for (i in 1..2000) last = p.process(frame(Foot.LEFT, *g, t = i * 10_000_000L)) // 20 s
        assertEquals(first.totalLoad, last.totalLoad, 1e-6)
        for (i in g.indices) assertEquals(g[i], last.loads[i], 1e-6)
        assertPoint(first.rawCop!!, last.rawCop, 1e-9)
        assertEquals(BaselineSource.NONE, last.baselineSource)
    }

    @Test
    fun `standing after walking keeps the baseline learned while walking`() {
        val p = FootProcessor(Foot.LEFT)
        walk(p, 0, 10)
        var last = p.process(loaded(10_000_000_000L))
        for (i in 1..2000) last = p.process(loaded(10_000_000_000L + i * 10_000_000L)) // 20 s standing
        assertEquals(BaselineSource.AUTO, last.baselineSource)
        assertEquals(900.0, last.totalLoad, 1e-6)
    }

    @Test
    fun `updateSettings keeps the learned auto-zero baseline, re-capped`() {
        val p = FootProcessor(Foot.LEFT)
        walk(p, 0, 5)
        p.updateSettings(ProcessorSettings(autoZeroMaxMicroSiemens = 3.0, contactOnThreshold = 25.0))
        val state = p.process(lifted(5_000_000_000L))
        assertEquals(BaselineSource.AUTO, state.baselineSource)
        // 8 µS of leakage per sensor, only 3 µS of it is removed now.
        assertEquals(6 * 5.0, state.totalLoad, 1e-6)
    }

    @Test
    fun `explicit calibration overrides auto-zero`() {
        val p = FootProcessor(Foot.LEFT)
        walk(p, 0, 3) // auto baseline = the 8 uS leakage
        val auto = p.process(loaded(3_000_000_000))
        assertEquals(900.0, auto.totalLoad, 1e-6)
        // An explicit baseline that differs from the rolling minimum: 50 uS everywhere.
        p.setBaseline(Baseline(DoubleArray(6) { ohmsFor(50.0) }))
        val state = p.process(loaded(3_010_000_000))
        assertEquals(BaselineSource.CALIBRATED, state.baselineSource)
        assertEquals(3 * 258.0, state.totalLoad, 1e-6)
        assertTrue(abs(state.totalLoad - auto.totalLoad) > 100.0)
    }

    @Test
    fun `auto-zero can be disabled`() {
        val p = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 0.0))
        val s = p.process(lifted(0))
        assertEquals(BaselineSource.NONE, s.baselineSource)
        assertEquals(48.0, s.totalLoad, 1e-6)
    }

    @Test
    fun `contact has hysteresis`() {
        val p = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 0.0))
        fun total(us: Double, t: Long) = p.process(frame(Foot.LEFT, us, 0.0, 0.0, 0.0, 0.0, 0.0, t = t))
        assertFalse(total(15.0, 0).inContact) // below on
        assertTrue(total(25.0, 10_000_000).inContact) // above on
        assertTrue(total(15.0, 20_000_000).inContact) // between off and on: stays
        assertNotNull(total(15.0, 30_000_000).cop)
        assertFalse(total(5.0, 40_000_000).inContact) // below off
        assertNull(total(5.0, 50_000_000).cop)
        assertFalse(total(15.0, 60_000_000).inContact) // and does not come back below on
    }

    @Test
    fun `settings validate the hysteresis band`() {
        assertFailsWith<IllegalArgumentException> {
            ProcessorSettings(contactOnThreshold = 5.0, contactOffThreshold = 10.0)
        }
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
        FootState(foot, 0, DoubleArray(6), DoubleArray(6), total, DoubleArray(6), cop, cop, BaselineSource.CALIBRATED)

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

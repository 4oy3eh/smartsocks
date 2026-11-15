package com.smartsocks.core.analytics

import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.RawPacket
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalanceGainTest {
    private val second = 1_000_000_000L
    private val duration = 10 * second

    @Test
    fun `gains for a ratio even out the insoles and keep the overall level`() {
        val g = FootGains.forRatio(1.12)
        assertEquals(1 / 1.12, g.right / g.left, 1e-12, "the right insole reads 12 % more: it is turned down")
        assertEquals(1.0, g.left * g.right, 1e-12)
        assertEquals(FootGains.NEUTRAL, FootGains.forRatio(1.0))
        assertTrue(FootGains.NEUTRAL.isNeutral)
        assertFalse(g.isNeutral)
        assertEquals(g.left, g.of(Foot.LEFT))
        assertEquals(g.right, g.of(Foot.RIGHT))
        assertFailsWith<IllegalArgumentException> { FootGains(0.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { FootGains(1.0, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { FootGains.forRatio(-1.0) }
    }

    private fun state(
        foot: Foot,
        nanos: Long,
        raw: Double,
        gain: Double = 1.0,
        contact: Boolean = true,
        source: BaselineSource = BaselineSource.CALIBRATED,
        version: Int = 1,
    ) = FootState(
        foot,
        nanos,
        DoubleArray(SENSOR_COUNT),
        DoubleArray(SENSOR_COUNT) { raw * gain / SENSOR_COUNT },
        raw * gain,
        DoubleArray(SENSOR_COUNT),
        if (contact) Point(0.0, 0.0) else null,
        null,
        source,
        gain,
        version,
    )

    /**
     * Both feet at 100 Hz (the right one stamped 3 ms later) for the whole measurement: a person of [weight] µS
     * (in left-insole units) with [leftShare] of it on the left foot on average, shifting ±[amplitude] of it every
     * [periodSeconds]; the right insole reads [rightToLeft] times the left one. [adjust] may change any sample.
     */
    private fun measure(
        rightToLeft: Double,
        leftShare: Double = 0.5,
        amplitude: Double = 0.2,
        periodSeconds: Double = 4.0,
        weight: Double = 1000.0,
        limits: BalanceLimits = BalanceLimits(),
        adjust: (FootState) -> FootState = { it },
    ): BalanceCalibrator {
        val c = BalanceCalibrator(duration, limits)
        var k = 0
        while (true) {
            val t = k * 10_000_000L
            val share = leftShare + amplitude * sin(2 * PI * k / 100.0 / periodSeconds)
            c.add(adjust(state(Foot.LEFT, t, weight * share)))
            val done = c.add(adjust(state(Foot.RIGHT, t + 3_000_000, rightToLeft * weight * (1 - share))))
            k++
            if (done) return c
        }
    }

    private fun calibrated(c: BalanceCalibrator) = assertIs<BalanceResult.Calibrated>(c.result())

    private fun rejected(c: BalanceCalibrator) = assertIs<BalanceResult.Rejected>(c.result())

    @Test
    fun `weight shifting recovers the sensitivity ratio either way round`() {
        for (ratio in listOf(1.12, 1 / 1.12, 1.4, 0.7)) {
            val result = calibrated(measure(ratio))
            assertEquals(ratio, result.rightToLeft, ratio * 1e-3, "ratio $ratio")
            assertEquals(FootGains.forRatio(ratio).left, result.gains.left, 2e-3)
            assertTrue(result.correlation < -0.99, "${result.correlation}")
            assertTrue(result.swing in 0.3..0.45, "${result.swing}")
        }
    }

    @Test
    fun `the result does not depend on the average stance`() {
        val even = calibrated(measure(1.12)).rightToLeft
        for (share in listOf(0.6, 0.4)) {
            val result = calibrated(measure(1.12, leftShare = share, amplitude = 0.15))
            assertEquals(even, result.rightToLeft, 2e-3, "a habitual $share stance")
        }
    }

    @Test
    fun `gains in effect, even changed during the measurement, do not matter`() {
        val plain = calibrated(measure(1.12)).rightToLeft
        val changed = calibrated(
            measure(1.12) { s ->
                val gain = if (s.timestampNanos > 5 * second) 0.8 else 1.25
                state(s.foot, s.timestampNanos, s.totalLoad, gain = gain)
            },
        )
        assertEquals(plain, changed.rightToLeft, 1e-9)
    }

    @Test
    fun `the lead-in is discarded`() {
        // Settling in for the first second: both feet loaded more and less together (as if sitting down).
        val result = calibrated(
            measure(1.12) { s ->
                if (s.timestampNanos < second) {
                    state(s.foot, s.timestampNanos, s.totalLoad * (1 + s.timestampNanos.toDouble() / second))
                } else {
                    s
                }
            },
        )
        assertEquals(1.12, result.rightToLeft, 2e-3)
    }

    @Test
    fun `standing still is refused`() {
        val result = rejected(measure(1.12, amplitude = 0.01))
        assertEquals(BalanceRejection.NO_SWING, result.reason)
        assertTrue(result.message.startsWith("Shift your weight further"), result.message)
    }

    @Test
    fun `bouncing instead of shifting is refused`() {
        // The total load goes up and down with both feet in step, plus some shifting.
        val c = BalanceCalibrator(duration)
        var k = 0
        while (true) {
            val t = k * 10_000_000L
            val bounce = 1 + 0.4 * sin(2 * PI * k / 100.0)
            val share = 0.5 + 0.1 * sin(2 * PI * k / 400.0)
            c.add(state(Foot.LEFT, t, 800.0 * share * bounce))
            val done = c.add(state(Foot.RIGHT, t, 800.0 * (1 - share) * bounce))
            k++
            if (done) break
        }
        assertEquals(BalanceRejection.NOT_ANTICORRELATED, rejected(c).reason)
    }

    @Test
    fun `a lifted foot ends the measurement at once`() {
        val c = measure(1.12) { s ->
            if (s.foot == Foot.RIGHT &&
                s.timestampNanos > 3 * second
            ) {
                state(s.foot, s.timestampNanos, 0.0, contact = false)
            } else {
                s
            }
        }
        assertTrue(c.failedEarly)
        assertTrue(c.progress < 0.35, "${c.progress}")
        val result = rejected(c)
        assertEquals(BalanceRejection.NOT_IN_CONTACT, result.reason)
        assertEquals("Keep both feet on the ground: the right foot was lifted", result.message)
    }

    @Test
    fun `a foot without a zero point or with a zero point that changes is refused`() {
        val none = rejected(
            measure(1.12) { s ->
                if (s.foot ==
                    Foot.LEFT
                ) {
                    state(s.foot, s.timestampNanos, s.totalLoad, source = BaselineSource.NONE)
                } else {
                    s
                }
            },
        )
        assertEquals(BalanceRejection.NO_BASELINE, none.reason)
        assertEquals(BalanceCalibrator.NO_BASELINE_MESSAGE, none.message)

        val step = measure(1.12) { s ->
            val version = if (s.timestampNanos > 4 * second) 2 else 1
            state(s.foot, s.timestampNanos, s.totalLoad, source = BaselineSource.AUTO, version = version)
        }
        assertTrue(step.failedEarly)
        assertEquals(BalanceRejection.BASELINE_CHANGED, rejected(step).reason)
        // Auto-zero is fine as long as it stays put.
        calibrated(measure(1.12) { state(it.foot, it.timestampNanos, it.totalLoad, source = BaselineSource.AUTO) })
    }

    @Test
    fun `too little load and an implausible ratio are refused`() {
        assertEquals(BalanceRejection.LOW_LOAD, rejected(measure(1.12, weight = 60.0)).reason)
        val implausible = rejected(measure(2.5))
        assertEquals(BalanceRejection.IMPLAUSIBLE_RATIO, implausible.reason)
        assertTrue("2.5 times" in implausible.message, implausible.message)
        assertEquals(BalanceRejection.IMPLAUSIBLE_RATIO, rejected(measure(1 / 2.5)).reason)
    }

    @Test
    fun `a ratio within one percent needs no correction`() {
        val result = calibrated(measure(1.005))
        assertEquals(FootGains.NEUTRAL, result.gains)
        assertEquals(1.005, result.rightToLeft, 1e-3)
        assertFalse(calibrated(measure(1.02)).gains.isNeutral)
    }

    @Test
    fun `progress and one foot alone`() {
        val c = BalanceCalibrator(duration)
        assertEquals(0.0, c.progress)
        for (k in 0 until 300) assertFalse(c.add(state(Foot.LEFT, k * 10_000_000L, 400.0)))
        assertEquals(0.0, c.progress, "the right foot has not covered anything")
        assertEquals(BalanceRejection.TOO_FEW_SAMPLES, rejected(c).reason)
        val half = BalanceCalibrator(duration)
        for (k in 0 until 500) {
            half.add(state(Foot.LEFT, k * 10_000_000L, 400.0))
            half.add(state(Foot.RIGHT, k * 10_000_000L, 400.0))
        }
        assertEquals(0.5, half.progress, 0.01)
        assertFailsWith<IllegalArgumentException> { BalanceCalibrator(second) }
    }

    private fun frame(foot: Foot, ohms: DoubleArray, nanos: Long = 0) = SensorFrame(foot, nanos, ohms)

    @Test
    fun `the gain scales loads and totals but not the foot's own COP or shares`() {
        val ohms = doubleArrayOf(5_000.0, 8_000.0, 20_000.0, 12_000.0, 4_000.0, 6_000.0)
        val plain = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 0.0))
        val gained = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 0.0)).apply { gain = 1.3 }
        val a = plain.process(frame(Foot.LEFT, ohms))
        val b = gained.process(frame(Foot.LEFT, ohms))
        assertEquals(1.0, a.gain)
        assertEquals(1.3, b.gain)
        for (i in 0 until SENSOR_COUNT) {
            assertEquals(a.loads[i] * 1.3, b.loads[i], 1e-9)
            assertEquals(a.shares[i], b.shares[i], 1e-12)
        }
        assertEquals(a.totalLoad * 1.3, b.totalLoad, 1e-9)
        assertEquals(assertNotNull(a.rawCop).x, assertNotNull(b.rawCop).x, 1e-12)
        assertEquals(a.rawCop!!.y, b.rawCop!!.y, 1e-12)
        assertEquals(a.cop!!.x, b.cop!!.x, 1e-12)
        assertFailsWith<IllegalArgumentException> { gained.gain = 0.0 }
        assertFailsWith<IllegalArgumentException> { gained.gain = Double.POSITIVE_INFINITY }
    }

    @Test
    fun `contact thresholds apply to the calibrated load`() {
        // 15 µS on one sensor: below the default 20 µS touch-down level.
        val ohms = DoubleArray(SENSOR_COUNT) { if (it == 0) 1e6 / 15.0 else Double.POSITIVE_INFINITY }
        val settings = ProcessorSettings(autoZeroSeconds = 0.0)
        assertNull(FootProcessor(Foot.RIGHT, settings = settings).process(frame(Foot.RIGHT, ohms)).cop)
        val boosted = FootProcessor(Foot.RIGHT, settings = settings).apply { gain = 1.5 }
        val state = boosted.process(frame(Foot.RIGHT, ohms))
        assertTrue(state.inContact, "22.5 calibrated µS is above the threshold")
        assertEquals(22.5, state.totalLoad, 1e-6)
    }

    @Test
    fun `the baseline version follows changes of the zero point`() {
        val p = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 0.0))
        val ohms = DoubleArray(SENSOR_COUNT) { 10_000.0 }
        val v0 = p.process(frame(Foot.LEFT, ohms)).baselineVersion
        assertEquals(v0, p.process(frame(Foot.LEFT, ohms, 10)).baselineVersion, "nothing changed")
        p.setBaseline(Baseline(DoubleArray(SENSOR_COUNT) { 1e6 }))
        val v1 = p.process(frame(Foot.LEFT, ohms, 20)).baselineVersion
        assertTrue(v1 != v0)
        p.setBaseline(Baseline(DoubleArray(SENSOR_COUNT) { 5e5 }))
        val v2 = p.process(frame(Foot.LEFT, ohms, 30)).baselineVersion
        assertTrue(v2 != v1, "another explicit baseline")
        p.gain = 1.2
        assertEquals(v2, p.process(frame(Foot.LEFT, ohms, 40)).baselineVersion, "a gain is not a zero point")

        // Auto-zero: learned once the foot is lifted, then steady while it stays the same.
        val auto = FootProcessor(Foot.LEFT, settings = ProcessorSettings(autoZeroSeconds = 1.0))
        val lifted = DoubleArray(SENSOR_COUNT) { Double.POSITIVE_INFINITY }
        val before = auto.process(frame(Foot.LEFT, ohms, 0)).baselineVersion
        val learned = auto.process(frame(Foot.LEFT, lifted, 10_000_000)).baselineVersion
        assertTrue(learned != before)
        var t = 20_000_000L
        repeat(50) {
            assertEquals(learned, auto.process(frame(Foot.LEFT, if (it % 10 < 5) ohms else lifted, t)).baselineVersion)
            t += 10_000_000
        }
    }

    /** Both feet of the simulator, insoles of different sensitivity, frames at 100 Hz, zero points calibrated. */
    private class SimPair(rightSensitivity: Double, mode: SimMode, leftShare: Double = 0.5) {
        private val range = MeasurementRange.K64
        private val config =
            SimConfig(mode = mode, gainSpread = 0.0, stanceLeftShare = leftShare, shiftAmplitude = 0.15)
        val sims = mapOf(
            Foot.LEFT to SimulatedSockTransport(Foot.LEFT, config),
            Foot.RIGHT to SimulatedSockTransport(Foot.RIGHT, config.copy(sensitivity = rightSensitivity)),
        )
        val processors = Foot.entries.associateWith {
            FootProcessor(it).apply { setBaseline(Baseline(DoubleArray(SENSOR_COUNT) { Double.POSITIVE_INFINITY })) }
        }
        var k = 0

        fun state(foot: Foot): FootState {
            val packet = RawPacket(sims.getValue(foot).packetAt(k / 100.0, range))
            val ohms = DoubleArray(SENSOR_COUNT) { range.countsToOhms(packet.count(it)) }
            return processors.getValue(foot).process(SensorFrame(foot, k * 10_000_000L, ohms))
        }

        fun calibrate(): BalanceResult {
            val c = BalanceCalibrator(10 * 1_000_000_000L)
            while (true) {
                c.add(state(Foot.LEFT))
                val done = c.add(state(Foot.RIGHT))
                k++
                if (done) return c.result()
            }
        }

        /** Mean left share of the balance over [samples], in [mode]. */
        fun meanLeftShare(mode: SimMode, samples: Int): Double {
            for (sim in sims.values) sim.mode = mode
            var sum = 0.0
            repeat(samples) {
                sum += assertNotNull(BalanceCalculator.compute(state(Foot.LEFT), state(Foot.RIGHT))).left
                k++
            }
            return sum / samples
        }

        fun apply(gains: FootGains) {
            processors.getValue(Foot.LEFT).gain = gains.left
            processors.getValue(Foot.RIGHT).gain = gains.right
        }
    }

    @Test
    fun `the simulator's insoles read their true stance once calibrated, even or not`() {
        for (share in listOf(0.5, 0.6)) {
            val pair = SimPair(rightSensitivity = 1.12, mode = SimMode.Shifting, leftShare = share)
            val before = pair.meanLeftShare(SimMode.Standing, 1000)
            assertTrue(before < share - 0.02, "uncalibrated, the more sensitive right insole takes more: $before")
            pair.k = 0
            for (sim in pair.sims.values) sim.mode = SimMode.Shifting
            val result = assertIs<BalanceResult.Calibrated>(pair.calibrate())
            assertEquals(1.12, result.rightToLeft, 0.02, "stance $share")
            pair.apply(result.gains)
            assertEquals(share, pair.meanLeftShare(SimMode.Standing, 1000), 0.01, "stance $share stays as it is")
        }
    }
}

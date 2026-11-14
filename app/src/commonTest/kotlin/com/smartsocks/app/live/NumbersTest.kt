package com.smartsocks.app.live

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.CenterOfPressure
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.session.StreamStats
import com.smartsocks.core.settings.DeviceChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NumbersTest {
    private fun status(state: LinkState, rate: LinkRate = LinkRate(98, 100)) =
        FootStatus(Foot.RIGHT, DeviceChoice.Simulator(), "Simulated Right", state, rate)

    private val state = FootState(
        foot = Foot.RIGHT,
        timestampNanos = 0,
        resistanceOhms = doubleArrayOf(1234.0, 56_780.0, 123_456.0, Double.POSITIVE_INFINITY, 9_999.0, 100_000.0),
        loads = doubleArrayOf(100.0, 50.04, 25.0, 0.0, 12.5, 12.46),
        totalLoad = 200.0,
        shares = doubleArrayOf(0.5, 0.25, 0.125, 0.0, 0.0625, 0.0625),
        cop = Point(0.123, -0.456),
        rawCop = Point(0.1, -0.4),
        baselineSource = BaselineSource.AUTO,
    )

    @Test
    fun streamingFootIsFormattedForReading() {
        val n = FootNumbers.of(
            FootView(status(LinkState.Streaming), state, emptyList()),
            StreamStats(packets = 1000, droppedBytes = 7, packetsPerSecond = 98.2, nominalHz = 100),
            balanceShare = 0.517,
        )
        assertTrue(n.live)
        assertEquals(listOf("1.23", "56.8", "123", "open", "10.00", "100"), n.sensors.map { it.resistance })
        assertEquals(listOf("100.0", "50.0", "25.0", "0.0", "12.5", "12.5"), n.sensors.map { it.load })
        assertEquals(0.5f, n.sensors[0].share)
        assertEquals(SensorNames.Default.labels[0], n.sensors[0].name)
        assertEquals(if (SensorLayout.ODD_CHANNELS_MEDIAL) "1 · Fore medial" else "1 · Fore lateral", n.sensors[0].name)
        assertEquals("200.0 µS", n.total)
        assertEquals("52%", n.share)
        assertEquals("−0.12", n.copX, "the right foot's medial side is −x: shown medial positive")
        assertEquals("−0.46", n.copY)
        assertEquals("Yes", n.contact)
        assertEquals("Auto-zero", n.baseline)
        assertEquals("98/s · 100 Hz", n.rate)
        assertEquals("7 bytes", n.dropped)
        assertFalse(n.rateMismatch)
    }

    @Test
    fun footInTheAirHasNoCop() {
        val air = FootState(
            Foot.RIGHT, 0, state.resistanceOhms, DoubleArray(6), 0.0, DoubleArray(6), null, null,
            BaselineSource.CALIBRATED,
        )
        val n = FootNumbers.of(FootView(status(LinkState.Streaming), air, emptyList()), null, balanceShare = null)
        assertEquals("No", n.contact)
        assertEquals(FootNumbers.NONE, n.copX)
        assertEquals(FootNumbers.NONE, n.share)
        assertEquals("Calibrated", n.baseline)
        assertEquals(FootNumbers.NONE, n.dropped)
        assertTrue(n.sensors.all { it.share == 0f })
    }

    @Test
    fun disconnectedFootShowsDashesAndItsState() {
        val disconnected = status(LinkState.Disconnected("Link closed"))
        val n = FootNumbers.of(FootView(disconnected, null, emptyList()), null, balanceShare = null)
        assertFalse(n.live)
        assertEquals(disconnected, n.status)
        assertTrue(n.sensors.all { it.resistance == FootNumbers.NONE && it.load == FootNumbers.NONE })
        assertEquals(FootNumbers.NONE, n.total)
        assertEquals(FootNumbers.NONE, n.rate)
    }

    @Test
    fun rateMismatchIsFlagged() {
        val n = FootNumbers.of(
            FootView(status(LinkState.Streaming, LinkRate(64, 200, rateMismatch = true)), state, emptyList()),
            StreamStats(nominalHz = 200, rateMismatch = true),
            balanceShare = 0.5,
        )
        assertTrue(n.rateMismatch)
        assertEquals("64/s · 200 Hz", n.rate)
    }

    @Test
    fun equalValuesCompareEqualSoTheViewDoesNotRedraw() {
        val view = FootView(status(LinkState.Streaming), state, emptyList())
        assertEquals(FootNumbers.of(view, null, 0.5), FootNumbers.of(view, null, 0.5))
    }

    @Test
    fun copXIsMedialPositiveOnBothFeet() {
        val left = FootState(
            Foot.LEFT, 0, state.resistanceOhms, state.loads, 200.0, state.shares, Point(0.123, 0.0), null,
            BaselineSource.AUTO,
        )
        val leftStatus = FootStatus(Foot.LEFT, DeviceChoice.Simulator(), "Simulated Left", LinkState.Streaming)
        assertEquals("+0.12", FootNumbers.of(FootView(leftStatus, left, emptyList()), null, null).copX)
        assertEquals(1.0, FootNumbers.medialSign(Foot.LEFT))
        assertEquals(-1.0, FootNumbers.medialSign(Foot.RIGHT))
    }

    @Test
    fun weightOnTheMedialSensorsShowsAsMedialCopOnBothFeet() {
        val layout = SensorLayout.Legacy
        val loads = DoubleArray(6) { if (layout.isMedial(it)) 100.0 else 0.0 }
        for (foot in Foot.entries) {
            val cop = CenterOfPressure.compute(loads, layout.positions(foot), 0.0)!!.position
            val footState = FootState(
                foot, 0, state.resistanceOhms, loads, loads.sum(), DoubleArray(6), cop, cop, BaselineSource.AUTO,
            )
            val footStatus = FootStatus(foot, DeviceChoice.Simulator(), "Sim", LinkState.Streaming)
            val copX = FootNumbers.of(FootView(footStatus, footState, emptyList()), null, null).copX
            assertTrue(copX.startsWith("+"), "$foot: $copX")
        }
    }

    @Test
    fun sensorsAreColouredOnTheSharedScale() {
        val view = FootView(status(LinkState.Streaming), state, emptyList())
        val n = FootNumbers.of(view, null, 0.5, fullScaleLoad = 100.0)
        // sqrt(load / 100): 100 → 1, 25 → 0.5, 0 → 0, 12.5 → 0.354 (in steps of 1/64).
        assertEquals(listOf(1f, 0.703125f, 0.5f, 0f, 0.359375f, 0.359375f), n.sensors.map { it.level })
        assertTrue(FootNumbers.of(view, null, 0.5).sensors.all { it.level == 0f }, "no scale: no colour")
        val off = FootNumbers.of(FootView(status(LinkState.Connecting), null, emptyList()), null, null, 100.0)
        assertTrue(off.sensors.all { it.level == 0f })
        assertEquals(0f, loadLevel(Double.NaN, 100.0))
        assertEquals(1f, loadLevel(500.0, 100.0))
        assertEquals(0f, loadLevel(-3.0, 100.0))
    }

    @Test
    fun signedValuesAndResistances() {
        assertEquals("0.00", FootNumbers.signed(-0.001))
        assertEquals("+1.00", FootNumbers.signed(0.999))
        assertEquals("open", FootNumbers.kiloOhms(Double.NaN))
        assertEquals("0.00", FootNumbers.kiloOhms(0.0))
    }
}

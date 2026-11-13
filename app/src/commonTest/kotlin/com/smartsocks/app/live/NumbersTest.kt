package com.smartsocks.app.live

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
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
        assertEquals("Forefoot medial", n.sensors[0].name)
        assertEquals("200.0 µS", n.total)
        assertEquals("52%", n.share)
        assertEquals("+0.12", n.copX)
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
    fun signedValuesAndResistances() {
        assertEquals("0.00", FootNumbers.signed(-0.001))
        assertEquals("+1.00", FootNumbers.signed(0.999))
        assertEquals("open", FootNumbers.kiloOhms(Double.NaN))
        assertEquals("0.00", FootNumbers.kiloOhms(0.0))
    }
}

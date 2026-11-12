package com.smartsocks.core.session

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SockSessionTest {
    private fun TestScope.session(
        sim: SimulatedSockTransport,
        settings: StreamSettings = StreamSettings(SampleRate.HZ_100, MeasurementRange.K64),
    ) = SockSession(sim.foot, sim, settings, clockNanos = { testScheduler.currentTime * 1_000_000 })

    private fun sim(foot: Foot = Foot.LEFT, config: SimConfig = SimConfig(connectDelayMillis = 100)) =
        SimulatedSockTransport(foot, config)

    @Test
    fun `connects, configures and streams`() = runTest {
        val sim = sim()
        val session = session(sim)
        assertEquals(ConnectionState.Idle, session.connection.value)

        backgroundScope.launch { session.run() }
        runCurrent()
        assertEquals(ConnectionState.Connecting, session.connection.value)

        advanceTimeBy(3_000)
        assertEquals(ConnectionState.Streaming, session.connection.value)
        assertEquals(SockCommand.startSequence(SampleRate.HZ_100, MeasurementRange.K64), sim.receivedCommands)
        assertNotNull(session.latest.value)
        assertEquals(100.0, session.stats.value.packetsPerSecond, 5.0)
        assertEquals(0, session.stats.value.droppedBytes)
    }

    @Test
    fun `walking produces contact and swing`() = runTest {
        val session = session(sim())
        val contact = mutableListOf<Boolean>()
        backgroundScope.launch { session.states.collect { contact += it.inContact } }
        backgroundScope.launch { session.run() }
        advanceTimeBy(5_000)
        assertTrue(contact.size > 400)
        assertTrue(contact.any { it } && contact.any { !it }, "expected both stance and swing")
    }

    @Test
    fun `calibrates with the foot lifted`() = runTest {
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted))
        val session = session(sim)
        backgroundScope.launch { session.run() }
        advanceTimeBy(500)

        session.calibrate(samples = 50)
        assertEquals(0.0, session.calibrationProgress.value)
        advanceTimeBy(1_000)
        assertNull(session.calibrationProgress.value)
        val lifted = session.latest.filterNotNull().first()
        assertTrue(lifted.calibrated)
        assertTrue(!lifted.inContact)

        sim.mode = SimMode.Standing
        advanceTimeBy(500)
        assertTrue(session.latest.value!!.inContact)
    }

    @Test
    fun `reports link loss`() = runTest {
        val session = session(sim(config = SimConfig(connectDelayMillis = 0, disconnectAfterPackets = 50)))
        backgroundScope.launch { session.run() }
        advanceTimeBy(5_000)
        assertEquals(ConnectionState.Disconnected("Link closed"), session.connection.value)
    }

    @Test
    fun `reports connection failure`() = runTest {
        val session = session(sim(config = SimConfig(failToConnect = true)))
        session.run()
        val state = session.connection.value
        assertIs<ConnectionState.Disconnected>(state)
        assertEquals("Simulated connection failure", state.reason)
    }

    @Test
    fun `cancelling stops the device and closes the link`() = runTest {
        val sim = sim()
        val session = session(sim)
        val job = launch { session.run() }
        advanceTimeBy(1_000)
        job.cancel()
        runCurrent()
        assertEquals(ConnectionState.Disconnected(null), session.connection.value)
        assertEquals(SockCommand.Stop, sim.receivedCommands.last())
    }

    @Test
    fun `two feet stream independently and balance adds up`() = runTest {
        val left = session(sim(Foot.LEFT, SimConfig(connectDelayMillis = 0, mode = SimMode.Standing)))
        val right = session(sim(Foot.RIGHT, SimConfig(connectDelayMillis = 0, mode = SimMode.Standing)))
        backgroundScope.launch { left.run() }
        backgroundScope.launch { right.run() }
        advanceTimeBy(2_000)

        val balance = balanceOf(left, right).filterNotNull().first()
        assertEquals(1.0, balance.left + balance.right, 1e-9)
        assertTrue(balance.left in 0.3..0.7, "standing should be roughly even, was ${balance.left}")
        assertNotNull(balance.combinedCop)

        val both = latestOf(listOf(left, right)).first()
        assertEquals(setOf(Foot.LEFT, Foot.RIGHT), both.keys)
    }
}

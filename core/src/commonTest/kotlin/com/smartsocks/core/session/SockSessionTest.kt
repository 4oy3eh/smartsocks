package com.smartsocks.core.session

import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SockSessionTest {
    /** K256 so that an unloaded FSR (~100 kOhm) is within range and baseline subtraction is really exercised. */
    private fun TestScope.session(
        transport: SockTransport,
        foot: Foot = Foot.LEFT,
        settings: StreamSettings = StreamSettings(SampleRate.HZ_100, MeasurementRange.K256),
        processorSettings: ProcessorSettings = ProcessorSettings(),
    ) = SockSession(
        foot,
        transport,
        settings,
        processorSettings = processorSettings,
        clockNanos = { testScheduler.currentTime * 1_000_000 },
    )

    private fun sim(foot: Foot = Foot.LEFT, config: SimConfig = SimConfig(connectDelayMillis = 100)) =
        SimulatedSockTransport(foot, config)

    /** A transport like a real RFCOMM socket: read() ignores cancellation and only returns once close() is called. */
    private class BlockingTransport : SockTransport {
        override val displayName = "blocking"
        val closed = CompletableDeferred<Unit>()

        override suspend fun open() = Unit

        override suspend fun read(buffer: ByteArray): Int {
            withContext(NonCancellable) { closed.await() }
            return -1
        }

        override suspend fun write(data: ByteArray) = Unit

        override suspend fun close() {
            closed.complete(Unit)
        }
    }

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
        assertEquals(SockCommand.startSequence(SampleRate.HZ_100, MeasurementRange.K256), sim.receivedCommands)
        assertNotNull(session.latest.value)
        assertEquals(100.0, session.stats.value.packetsPerSecond, 5.0)
        assertEquals(0, session.stats.value.droppedBytes)
        assertTrue(session.stats.value.packets > 200)
    }

    @Test
    fun `walking produces contact and swing`() = runTest {
        // Uncalibrated, the leakage of six sensors adds up to ~60 uS, so the contact threshold has to sit above it.
        val session = session(sim(), processorSettings = ProcessorSettings(contactThreshold = 100.0))
        val contact = mutableListOf<Boolean>()
        backgroundScope.launch { session.states.collect { contact += it.inContact } }
        backgroundScope.launch { session.run() }
        advanceTimeBy(5_000)
        assertTrue(contact.size > 400)
        assertTrue(contact.any { it } && contact.any { !it }, "expected both stance and swing")
    }

    @Test
    fun `calibration removes the leakage of a lifted foot`() = runTest {
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted))
        val session = session(sim)
        backgroundScope.launch { session.run() }
        advanceTimeBy(500)

        val before = session.latest.value!!
        assertFalse(before.calibrated)
        assertTrue(before.loads.all { it > 1.0 }, "leakage should show as load: ${before.loads.toList()}")

        assertTrue(session.calibrate(samples = 50))
        assertEquals(0.0, session.calibrationProgress.value)
        advanceTimeBy(1_000)
        assertNull(session.calibrationProgress.value)
        val lifted = session.latest.filterNotNull().first()
        assertTrue(lifted.calibrated)
        assertTrue(lifted.loads.all { it < 1.0 }, "lifted loads: ${lifted.loads.toList()}")
        assertFalse(lifted.inContact)

        sim.mode = SimMode.Standing
        advanceTimeBy(500)
        assertTrue(session.latest.value!!.inContact)
    }

    @Test
    fun `calibrate outside streaming is refused and leaves nothing pending`() = runTest {
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Standing))
        val session = session(sim)
        assertFalse(session.calibrate(10))
        assertNull(session.calibrationProgress.value)

        backgroundScope.launch { session.run() }
        advanceTimeBy(1_000)
        // The refused request must not have been applied to the loaded foot.
        assertFalse(session.latest.value!!.calibrated)
        assertNull(session.calibrationProgress.value)
    }

    @Test
    fun `a link drop mid-calibration clears progress and the pending request`() = runTest {
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted, disconnectAfterPackets = 40))
        val session = session(sim)
        val job = launch { session.run() }
        advanceTimeBy(200)
        assertEquals(ConnectionState.Streaming, session.connection.value)
        assertTrue(session.calibrate(samples = 1_000))
        advanceTimeBy(2_000)
        job.join()
        assertEquals(ConnectionState.Disconnected("Link closed"), session.connection.value)
        assertNull(session.calibrationProgress.value)
        assertNull(session.latest.value)
        assertFalse(session.calibrate(10))
        assertNull(session.calibrationProgress.value)
    }

    @Test
    fun `reconnect keeps the baseline`() = runTest {
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted))
        val session = session(sim)
        val first = launch { session.run() }
        advanceTimeBy(500)
        assertTrue(session.calibrate(samples = 50))
        advanceTimeBy(1_000)
        assertTrue(session.latest.value!!.calibrated)
        first.cancelAndJoin()
        assertNull(session.latest.value)

        val second = launch { session.run() }
        advanceTimeBy(1_000)
        assertEquals(ConnectionState.Streaming, session.connection.value)
        val state = session.latest.value!!
        assertTrue(state.calibrated)
        assertTrue(state.loads.all { it < 1.0 }, "loads: ${state.loads.toList()}")
        second.cancelAndJoin()
    }

    @Test
    fun `second concurrent run is rejected`() = runTest {
        val session = session(sim(config = SimConfig(connectDelayMillis = 0)))
        backgroundScope.launch { session.run() }
        advanceTimeBy(500)
        assertFailsWith<IllegalStateException> { session.run() }
        assertEquals(ConnectionState.Streaming, session.connection.value)
    }

    @Test
    fun `burst delivery still gives evenly spaced timestamps`() = runTest {
        // 50 ms bursts of 5 packets: host time is identical inside a burst.
        val session = session(sim(config = SimConfig(connectDelayMillis = 0, chunkMillis = 50)))
        val stamps = mutableListOf<Long>()
        backgroundScope.launch { session.states.collect { stamps += it.timestampNanos } }
        backgroundScope.launch { session.run() }
        advanceTimeBy(3_000)
        assertTrue(stamps.size > 200)
        for (i in 1 until stamps.size) assertEquals(10_000_000L, stamps[i] - stamps[i - 1], "gap at $i")
    }

    @Test
    fun `reports link loss`() = runTest {
        val session = session(sim(config = SimConfig(connectDelayMillis = 0, disconnectAfterPackets = 50)))
        backgroundScope.launch { session.run() }
        advanceTimeBy(5_000)
        assertEquals(ConnectionState.Disconnected("Link closed"), session.connection.value)
        assertEquals(50, session.stats.value.packets)
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
        job.cancelAndJoin()
        assertEquals(ConnectionState.Disconnected(null), session.connection.value)
        assertEquals(SockCommand.Stop, sim.receivedCommands.last())
        // The transport really is closed: a read reports end of stream.
        assertEquals(-1, sim.read(ByteArray(64)))
    }

    @Test
    fun `cancelling closes a transport whose read ignores cancellation`() = runTest {
        val transport = BlockingTransport()
        val session = SockSession(
            Foot.LEFT,
            transport,
            clockNanos = { testScheduler.currentTime * 1_000_000 },
            commandGapMillis = 0,
        )
        val job = launch { session.run() }
        advanceTimeBy(100)
        assertEquals(ConnectionState.Streaming, session.connection.value)
        assertFalse(transport.closed.isCompleted)

        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(transport.closed.isCompleted)
        assertEquals(ConnectionState.Disconnected(null), session.connection.value)
    }

    @Test
    fun `two feet stream independently and balance adds up`() = runTest {
        val left = session(sim(Foot.LEFT, SimConfig(connectDelayMillis = 0, mode = SimMode.Standing)), Foot.LEFT)
        val right = session(sim(Foot.RIGHT, SimConfig(connectDelayMillis = 0, mode = SimMode.Standing)), Foot.RIGHT)
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

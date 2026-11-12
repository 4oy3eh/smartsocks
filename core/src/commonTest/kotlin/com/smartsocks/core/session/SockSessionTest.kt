package com.smartsocks.core.session

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketEncoder
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedLinkException
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.sim.preloadedInsole
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertContentEquals
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
        val writes = mutableListOf<ByteArray>()

        override suspend fun open() = Unit

        override suspend fun read(buffer: ByteArray): Int {
            withContext(NonCancellable) { closed.await() }
            return -1
        }

        override suspend fun write(data: ByteArray) {
            check(!closed.isCompleted) { "write on a closed socket" }
            writes += data
        }

        override suspend fun close() {
            closed.complete(Unit)
        }
    }

    /** open() blocks like a real connect(): ignores cancellation, fails once close() is called. */
    private class BlockingOpenTransport(private val onOpen: () -> Unit = {}) : SockTransport {
        override val displayName = "blocking open"
        val closed = CompletableDeferred<Unit>()

        override suspend fun open() {
            onOpen()
            withContext(NonCancellable) { closed.await() }
            throw SimulatedLinkException("socket closed")
        }

        override suspend fun read(buffer: ByteArray): Int = -1

        override suspend fun write(data: ByteArray) = Unit

        override suspend fun close() {
            closed.complete(Unit)
        }
    }

    /** Streams one packet every 1/hz s whatever it was told. */
    private class FixedRateTransport(private val hz: Int) : SockTransport {
        override val displayName = "fixed rate"
        private val packet = PacketEncoder.encode(IntArray(8))
        private val closed = CompletableDeferred<Unit>()

        override suspend fun open() = Unit

        override suspend fun read(buffer: ByteArray): Int {
            if (closed.isCompleted) return -1
            delay(1000L / hz)
            packet.copyInto(buffer)
            return packet.size
        }

        override suspend fun write(data: ByteArray) = Unit

        override suspend fun close() {
            closed.complete(Unit)
        }
    }

    /** Cancellable reads, but the STOP write is slow (400 ms) and the order of events is recorded. */
    private class SlowStopTransport : SockTransport {
        override val displayName = "slow stop"
        val events = mutableListOf<String>()
        private var streaming = false

        override suspend fun open() = Unit

        override suspend fun read(buffer: ByteArray): Int {
            streaming = true
            delay(1_000_000)
            return -1
        }

        override suspend fun write(data: ByteArray) {
            if (streaming && data.contentEquals(SockCommand.Stop.encode())) {
                events += "stop-begin"
                delay(400)
                events += "stop-done"
            }
        }

        override suspend fun close() {
            events += "close"
        }
    }

    private class CloseHook(private val inner: SockTransport, private val hook: () -> Unit) :
        SockTransport by inner {
        override suspend fun close() {
            hook()
            inner.close()
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
        val session = session(sim())
        val contact = mutableListOf<Boolean>()
        backgroundScope.launch { session.states.collect { contact += it.inContact } }
        backgroundScope.launch { session.run() }
        advanceTimeBy(5_000)
        assertTrue(contact.size > 400)
        assertTrue(contact.any { it } && contact.any { !it }, "expected both stance and swing")
    }

    @Test
    fun `calibration removes the leakage of a lifted foot`() = runTest {
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted).preloadedInsole())
        // No auto-zero: the leakage must show as load until the explicit calibration removes it.
        val session = session(sim, processorSettings = ProcessorSettings(autoZeroSeconds = 0.0))
        backgroundScope.launch { session.run() }
        advanceTimeBy(500)

        val before = session.latest.value!!
        assertEquals(BaselineSource.NONE, before.baselineSource)
        assertTrue(before.loads.all { it > 1.0 }, "leakage should show as load: ${before.loads.toList()}")

        assertTrue(session.calibrate(samples = 50))
        advanceTimeBy(20) // the reader picks the request up with the next packet
        assertNotNull(session.calibrationProgress.value)
        advanceTimeBy(1_000)
        assertNull(session.calibrationProgress.value)
        val lifted = session.latest.filterNotNull().first()
        assertEquals(BaselineSource.CALIBRATED, lifted.baselineSource)
        assertTrue(lifted.loads.all { it < 1.0 }, "lifted loads: ${lifted.loads.toList()}")
        assertFalse(lifted.inContact)

        sim.mode = SimMode.Standing
        advanceTimeBy(500)
        assertTrue(session.latest.value!!.inContact)
    }

    @Test
    fun `processor settings change live and keep the calibration`() = runTest {
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted).preloadedInsole())
        val session = session(sim, processorSettings = ProcessorSettings(autoZeroSeconds = 0.0))
        // Before the first run: applied when it starts.
        val strict = ProcessorSettings(contactOnThreshold = 1e6, contactOffThreshold = 1e6, autoZeroSeconds = 0.0)
        session.updateProcessorSettings(strict)
        assertEquals(strict, session.processorSettings.value)
        backgroundScope.launch { session.run() }
        advanceTimeBy(500)
        assertTrue(session.calibrate(samples = 20))
        advanceTimeBy(500)
        sim.mode = SimMode.Standing
        advanceTimeBy(500)
        assertFalse(session.latest.value!!.inContact, "a million µS is never reached")
        assertEquals(BaselineSource.CALIBRATED, session.latest.value!!.baselineSource)

        session.updateProcessorSettings(ProcessorSettings(autoZeroSeconds = 0.0))
        advanceTimeBy(100)
        val state = session.latest.value!!
        assertTrue(state.inContact)
        assertEquals(BaselineSource.CALIBRATED, state.baselineSource)
        assertEquals(ConnectionState.Streaming, session.connection.value)
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
    fun `a device ignoring the rate command is flagged and its stamps follow the host`() = runTest {
        val transport = FixedRateTransport(hz = 50) // session asks for 100 Hz
        val session = SockSession(
            Foot.LEFT,
            transport,
            StreamSettings(SampleRate.HZ_100, MeasurementRange.K64),
            clockNanos = { testScheduler.currentTime * 1_000_000 },
            commandGapMillis = 0,
        )
        val last = LongArray(1)
        backgroundScope.launch { session.states.collect { last[0] = it.timestampNanos } }
        backgroundScope.launch { session.run() }
        advanceTimeBy(60_000)
        assertTrue(session.stats.value.rateMismatch)
        assertEquals(100, session.stats.value.nominalHz)
        assertEquals(50.0, session.stats.value.packetsPerSecond, 2.0)
        val lag = testScheduler.currentTime * 1_000_000 - last[0]
        assertTrue(lag in 0..1_500_000_000L, "stamps lag the host by $lag ns after a minute")
    }

    @Test
    fun `a healthy stream is not flagged`() = runTest {
        val session = session(sim(config = SimConfig(connectDelayMillis = 0)))
        backgroundScope.launch { session.run() }
        advanceTimeBy(5_000)
        assertFalse(session.stats.value.rateMismatch)
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
        val sim = sim(config = SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted).preloadedInsole())
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
        // Even, apart from the slow pull towards the lower envelope (a burst's last packet has the least latency).
        for (i in 1 until stamps.size) {
            val gap = stamps[i] - stamps[i - 1]
            assertTrue(gap in 7_500_000L..10_000_000L, "gap $gap at $i")
        }
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
        // STOP reached the device before the socket was closed.
        assertContentEquals(SockCommand.Stop.encode(), transport.writes.last())
    }

    @Test
    fun `a slow STOP write is not cut off by the watcher closing the socket`() = runTest {
        val transport = SlowStopTransport()
        val session = SockSession(
            Foot.LEFT,
            transport,
            clockNanos = { testScheduler.currentTime * 1_000_000 },
            commandGapMillis = 0,
        )
        val job = launch { session.run() }
        advanceTimeBy(100)
        assertEquals(ConnectionState.Streaming, session.connection.value)
        withTimeout(5_000) { job.cancelAndJoin() }
        assertEquals(listOf("stop-begin", "stop-done", "close"), transport.events)
    }

    @Test
    fun `cancelling during a blocking open reports a plain cancellation`() = runTest {
        val transport = BlockingOpenTransport()
        val session = SockSession(Foot.LEFT, transport, clockNanos = { testScheduler.currentTime * 1_000_000 })
        val job = launch { session.run() }
        advanceTimeBy(100)
        assertEquals(ConnectionState.Connecting, session.connection.value)

        withTimeout(5_000) { job.cancelAndJoin() }
        assertTrue(transport.closed.isCompleted)
        assertEquals(ConnectionState.Disconnected(null), session.connection.value)
    }

    @Test
    fun `cancel before the watcher is dispatched still closes a blocking open`() = runTest {
        lateinit var job: Job
        val transport = BlockingOpenTransport(onOpen = { job.cancel() })
        val session = SockSession(Foot.LEFT, transport, clockNanos = { testScheduler.currentTime * 1_000_000 })
        job = launch { session.run() }
        withTimeout(5_000) { job.join() }
        assertTrue(transport.closed.isCompleted)
        assertEquals(ConnectionState.Disconnected(null), session.connection.value)
    }

    @Test
    fun `an auto-reconnect collector can restart run on Disconnected`() = runTest {
        val session = session(sim(config = SimConfig(connectDelayMillis = 0, disconnectAfterPackets = 30)))
        var restarts = 0
        var streamings = 0
        val testDispatcher = coroutineContext[ContinuationInterceptor]!!
        // Unconfined: the collector runs inside the assignment of Disconnected, like on a multithreaded dispatcher.
        backgroundScope.launch(Dispatchers.Unconfined) {
            session.connection.collect {
                if (it == ConnectionState.Streaming) streamings++
                if (it is ConnectionState.Disconnected && restarts < 1) {
                    restarts++
                    // Starts inline, inside the assignment of Disconnected, then continues on virtual time.
                    launch(testDispatcher, start = CoroutineStart.UNDISPATCHED) { session.run() }
                }
            }
        }
        backgroundScope.launch { session.run() }
        advanceTimeBy(3_000)
        assertEquals(1, restarts, "restarts")
        assertEquals(2, streamings, "streamings")
        assertEquals(30, session.stats.value.packets)
    }

    @Test
    fun `run can be called again after open failed`() = runTest {
        val inner = sim(config = SimConfig(connectDelayMillis = 0))
        var attempts = 0
        val flaky = object : SockTransport by inner {
            override suspend fun open() {
                if (attempts++ == 0) throw SimulatedLinkException("first try fails")
                inner.open()
            }
        }
        val session = session(flaky)
        session.run()
        assertEquals(ConnectionState.Disconnected("first try fails"), session.connection.value)

        backgroundScope.launch { session.run() }
        advanceTimeBy(500)
        assertEquals(ConnectionState.Streaming, session.connection.value)
    }

    @Test
    fun `calibrate during link teardown is refused and leaves no progress behind`() = runTest {
        lateinit var session: SockSession
        var accepted: Boolean? = null
        val transport = CloseHook(sim(config = SimConfig(connectDelayMillis = 0))) {
            accepted = session.calibrate(50)
        }
        session = session(transport)
        val job = launch { session.run() }
        advanceTimeBy(500)
        job.cancelAndJoin()
        assertEquals(false, accepted)
        assertNull(session.calibrationProgress.value)
    }

    @Test
    fun `a calibration requested right before cancel leaves no progress behind`() = runTest {
        val session = session(sim(config = SimConfig(connectDelayMillis = 0)))
        val job = launch { session.run() }
        advanceTimeBy(500)
        assertTrue(session.calibrate(50))
        job.cancelAndJoin()
        assertNull(session.calibrationProgress.value)
        assertEquals(ConnectionState.Disconnected(null), session.connection.value)
    }

    @Test
    fun `two feet stream independently and balance adds up`() = runTest {
        val standing = SimConfig(connectDelayMillis = 0, mode = SimMode.Standing)
        val left = session(sim(Foot.LEFT, standing), Foot.LEFT)
        val right = session(sim(Foot.RIGHT, standing), Foot.RIGHT)
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

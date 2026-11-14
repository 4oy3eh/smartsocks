package com.smartsocks.app.live

import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.ConnectionState
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Automatic reconnect after a drop or a failed open, see [LiveController] and [ReconnectBackoff]. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveControllerReconnectTest {
    private class Fixture(val settings: SettingsRepository, val socks: TestSocks, val controller: LiveController) {
        val left get() = controller.left
        val state get() = controller.left.status.value.state
    }

    /** The left foot only (the right one has no device), auto-connected; no jitter. */
    private fun TestScope.fixture(initial: AppSettings = AppSettings(right = DeviceChoice.None)): Fixture {
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial)), backgroundScope)
        val socks = TestSocks({ testScheduler.currentTime })
        val controller = LiveController(settings, socks, backgroundScope, reconnectDelayMillis = noJitter) {
            testScheduler.currentTime * 1_000_000
        }
        return Fixture(settings, socks, controller)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    /** Every distinct state of the left foot from now on. */
    private fun TestScope.statesOf(f: Fixture): List<LinkState> {
        val states = mutableListOf<LinkState>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            f.left.status.collect { if (states.lastOrNull() != it.state) states += it.state }
        }
        return states
    }

    @Test
    fun backoffDoublesFromOneSecondUpToThirtyWithJitterBelowTheCap() {
        val full = (1..9).map { ReconnectBackoff.delayMillis(it, 0.0) }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000, 30_000), full)
        assertEquals(30_000L, ReconnectBackoff.delayMillis(Int.MAX_VALUE, 0.0), "no overflow")
        assertEquals(900L, ReconnectBackoff.delayMillis(1, 1.0))
        assertEquals(27_000L, ReconnectBackoff.delayMillis(7, 1.0))
        assertEquals(3_800L, ReconnectBackoff.delayMillis(3, 0.5))
        assertEquals(30_000L, ReconnectBackoff.delayMillis(6, -5.0), "random is clamped")
        assertFailsWith<IllegalArgumentException> { ReconnectBackoff.delayMillis(0, 0.0) }

        assertEquals(1, ReconnectBackoff.secondsLeft(900))
        assertEquals(1, ReconnectBackoff.secondsLeft(1_000))
        assertEquals(2, ReconnectBackoff.secondsLeft(1_001))
        assertEquals(30, ReconnectBackoff.secondsLeft(30_000))
        assertEquals(1, ReconnectBackoff.secondsLeft(0))
    }

    @Test
    fun onlyTriesThatNeverStreamMakeTheWaitGrow() {
        var p = ReconnectBackoff.Progress()
        p = p.next(null)
        assertEquals(ReconnectBackoff.Progress(1, 1), p)
        p = p.next(null).next(null)
        assertEquals(ReconnectBackoff.Progress(3, 3), p)
        p = p.next(2_000) // streamed briefly
        assertEquals(ReconnectBackoff.Progress(4, 3), p)
        p = p.next(ReconnectBackoff.STABLE_MILLIS)
        assertEquals(ReconnectBackoff.Progress(1, 1), p)
        assertEquals(ReconnectBackoff.Progress(1, 1), ReconnectBackoff.Progress().next(500))
        repeat(20) { p = p.next(null) }
        assertEquals(ReconnectBackoff.MAX_STEP, p.step)
        assertEquals(21, p.attempt)
        assertEquals(ReconnectBackoff.MAX_MILLIS, ReconnectBackoff.delayMillis(p.step, 0.0))
    }

    @Test
    fun onRealThreadsAFootThatRetriesIsNeverShownAsDown() = runTest {
        // Real time on Dispatchers.Default: the status's inputs change on different threads. Links of ~10 packets
        // that drop again and again, retried after 20 ms.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(AppSettings())), scope)
        val socks =
            TestSocks({ 0L }, dropAfterPackets = 10, config = SimConfig(connectDelayMillis = 5, chunkMillis = 10))
        val controller = LiveController(settings, socks, scope, reconnectDelayMillis = { 20L })
        val bad = MutableStateFlow(emptyList<String>())
        val watching = MutableStateFlow(true)
        val watchers = Foot.entries.map { foot ->
            scope.launch {
                var started = false
                controller.link(foot).status.collect {
                    val s = it.state
                    if (s != LinkState.Idle) started = true
                    val down = s is LinkState.Disconnected || s == LinkState.Idle
                    if (started && down && watching.value) bad.update { list -> list + "$foot $s" }
                }
            }
        }
        withContext(Dispatchers.Default) { delay(3_000) }
        watching.value = false // shutting down shows the feet as down, rightly
        controller.shutdown()
        watchers.forEach { it.cancel() }
        scope.cancel()
        for (foot in Foot.entries) {
            val opens = socks.latest(foot).opens.size
            assertTrue(opens >= 6, "$foot: only $opens connects")
        }
        assertEquals(emptyList(), bad.value)
    }

    @Test
    fun aRetryNowRightAfterTheDropIsNotLost() = runTest {
        val f = fixture()
        advance(1_000)
        val session = assertNotNull(f.left.session.value)
        // Reacts the moment the session says it is down: before the controller starts the countdown.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            session.connection.first { it is ConnectionState.Disconnected }
            f.left.retryNow()
        }
        f.socks.latest(Foot.LEFT).drop()
        advance(450)
        assertEquals(2, f.socks.latest(Foot.LEFT).opens.size, "tried at once, not after 1 s")
        assertEquals(LinkState.Streaming, f.state)
    }

    @Test
    fun bluetoothSwitchedOffDuringTheWaitEndsTheRetries() = runTest {
        val sock = DeviceChoice.Bluetooth("00:16:A4:00:00:01", "Block 3")
        val f = fixture(AppSettings(left = sock, right = DeviceChoice.None))
        f.socks.failOpen = true
        advance(5_000) // waiting 4 s for attempt 3
        assertIs<LinkState.Reconnecting>(f.state)
        val opens = f.socks.latest(Foot.LEFT).opens.size
        f.socks.problem = DeviceResult.Unavailable("Bluetooth is off", UnavailableReason.BluetoothOff)
        advance(60_000)
        assertEquals(UnavailableReason.BluetoothOff, assertIs<LinkState.Unavailable>(f.state).reason)
        assertEquals(opens, f.socks.socks.sumOf { it.opens.size }, "not retried")
        assertTrue(f.left.status.value.pendingConnect)
    }

    @Test
    fun aDeviceCheckThatThrowsIsAReasonNotToConnectNotACrash() = runTest {
        val settings = SettingsRepository(
            InMemorySettingsStore(SettingsCodec.encode(AppSettings(right = DeviceChoice.None))),
            backgroundScope,
        )
        val socks = TestSocks({ testScheduler.currentTime })
        var broken = false
        val devices = object : DeviceFactory by socks {
            override fun check(foot: Foot, choice: DeviceChoice): DeviceResult.Unavailable? {
                if (broken) error("adapter gone")
                return null
            }
        }
        val controller = LiveController(settings, devices, backgroundScope, reconnectDelayMillis = noJitter) {
            testScheduler.currentTime * 1_000_000
        }
        advance(1_000)
        assertEquals(LinkState.Streaming, controller.left.status.value.state)
        broken = true
        socks.latest(Foot.LEFT).drop()
        advance(5_000)
        assertEquals(LinkState.Unavailable("adapter gone"), controller.left.status.value.state)
        // The foot still works: a device change builds a new session.
        broken = false
        settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(preloaded = true)) }
        advance(1_000)
        assertEquals(LinkState.Idle, controller.left.status.value.state)
    }

    @Test
    fun aDropReconnectsTheSameSessionAfterASecond() = runTest {
        val f = fixture()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.state)
        val session = f.left.session.value
        val sock = f.socks.latest(Foot.LEFT)

        sock.drop()
        advance(50)
        assertEquals(LinkState.Reconnecting(attempt = 1, secondsLeft = 1, lastError = "Link closed"), f.state)
        assertTrue(f.left.wantsConnection, "the intent stands")
        assertFalse(sock.isOpen, "nothing holds the port while waiting")
        assertEquals(1, sock.opens.size)

        advance(1_000) // the retry is under way (the simulator takes 300 ms to connect)
        assertEquals(LinkState.Connecting, f.state)
        advance(500)
        assertEquals(LinkState.Streaming, f.state)
        assertSame(session, f.left.session.value)
        assertEquals(2, sock.opens.size)
        assertEquals(1, f.socks.socks.size, "same transport, reopened")
    }

    @Test
    fun failedOpensBackOffUpToThirtySecondsAndCountDownEverySecond() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        val states = statesOf(f)
        advance(100_000)
        val sock = f.socks.latest(Foot.LEFT)
        // Each open fails after the simulator's 300 ms connect delay; the wait starts then.
        val waits = sock.opens.zipWithNext { a, b -> b - a - 300 }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000), waits)

        val reconnecting = states.filterIsInstance<LinkState.Reconnecting>()
        assertEquals((1..8).toList(), reconnecting.map { it.attempt }.distinct())
        assertEquals(listOf(4, 3, 2, 1), reconnecting.filter { it.attempt == 3 }.map { it.secondsLeft })
        assertEquals(30, reconnecting.first { it.attempt == 6 }.secondsLeft)
        assertTrue(reconnecting.all { it.lastError == "No answer" })
        // Never shown as down while retrying.
        val shown = states.dropWhile { it == LinkState.Idle }
        assertTrue(shown.none { it is LinkState.Disconnected || it == LinkState.Idle }, "$shown")
        // Between the waits the retry shows as connecting.
        val i = states.indexOfFirst { it is LinkState.Reconnecting && it.attempt == 2 }
        assertEquals(LinkState.Connecting, states[i - 1])
        assertFalse(sock.isOpen)
    }

    @Test
    fun theBackoffStartsOverAfterALinkThatStayedUp() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        advance(6_000) // opens at 0, 1.3 s, 3.6 s fail: now waiting until 7.9 s for attempt 3
        assertEquals(3, assertIs<LinkState.Reconnecting>(f.state).attempt)
        f.socks.failOpen = false
        advance(2_500) // attempt 3 connects
        assertEquals(LinkState.Streaming, f.state)

        // A short link neither resets the wait nor lets it grow: attempt 4 waits 4 s, like attempt 3.
        advance(3_000)
        f.socks.latest(Foot.LEFT).drop()
        advance(50)
        assertEquals(LinkState.Reconnecting(4, 4, "Link closed"), f.state)
        advance(5_000)
        assertEquals(LinkState.Streaming, f.state)
        f.socks.latest(Foot.LEFT).drop()
        advance(50)
        assertEquals(LinkState.Reconnecting(5, 4, "Link closed"), f.state)
        advance(5_000)
        assertEquals(LinkState.Streaming, f.state)

        // Streaming for 10 s or more: back to 1 s.
        advance(ReconnectBackoff.STABLE_MILLIS + 500)
        f.socks.latest(Foot.LEFT).drop()
        advance(50)
        assertEquals(LinkState.Reconnecting(1, 1, "Link closed"), f.state)
    }

    @Test
    fun aUserDisconnectCallsTheRetriesOff() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        advance(2_000)
        assertIs<LinkState.Reconnecting>(f.state)
        val opens = f.socks.latest(Foot.LEFT).opens.size

        f.left.toggle() // the chip's tap
        advance(100)
        assertFalse(f.left.wantsConnection)
        assertEquals(LinkState.Disconnected("No answer"), f.state)
        assertTrue(f.state.canConnect)
        advance(120_000)
        assertEquals(opens, f.socks.latest(Foot.LEFT).opens.size, "no more attempts")

        // Connecting again starts over: at once, then 1 s.
        f.left.connect()
        advance(400)
        assertEquals(LinkState.Reconnecting(1, 1, "No answer"), f.state)
    }

    @Test
    fun aDeviceChangeEndsTheRetriesOfTheOldSessionAndConnectsTheNewOneAtOnce() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        advance(5_000) // waiting for attempt 3
        val old = f.socks.latest(Foot.LEFT)
        val oldSession = f.left.session.value
        val oldOpens = old.opens.size

        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(preloaded = true)) }
        advance(1)
        val new = f.socks.latest(Foot.LEFT)
        assertNotSame(old, new)
        assertNotSame(oldSession, f.left.session.value)
        assertEquals(1, new.opens.size, "the new sock is opened right away")
        advance(400)
        assertEquals(LinkState.Reconnecting(1, 1, "No answer"), f.state, "the count starts over")
        advance(60_000)
        assertEquals(oldOpens, old.opens.size, "the old sock is left alone")
        assertFalse(old.isOpen)
    }

    @Test
    fun aStreamChangeWhileReconnectingRebuildsAndConnectsAtOnce() = runTest {
        val f = fixture()
        advance(1_000)
        f.socks.failOpen = true
        f.socks.latest(Foot.LEFT).drop()
        advance(50)
        assertIs<LinkState.Reconnecting>(f.state)
        f.socks.failOpen = false
        f.settings.update { it.copy(stream = StreamSettings(SampleRate.HZ_50)) }
        advance(500)
        assertEquals(LinkState.Streaming, f.state)
        assertEquals(2, f.socks.socks.size)
    }

    @Test
    fun aPauseStopsTheRetriesAndResumingTriesAtOnce() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        advance(5_000) // waiting 4 s for attempt 3
        val sock = f.socks.latest(Foot.LEFT)
        val opens = sock.opens.size

        f.controller.setPaused(true)
        advance(60_000)
        assertEquals(opens, sock.opens.size, "no attempts while paused")
        assertFalse(f.state is LinkState.Reconnecting)
        assertTrue(f.left.wantsConnection, "the intent survives the pause")

        f.socks.failOpen = false
        f.controller.setPaused(false)
        advance(1)
        assertEquals(opens + 1, sock.opens.size, "resumed: tried at once")
        advance(500)
        assertEquals(LinkState.Streaming, f.state)
    }

    @Test
    fun switchingTheSettingOffEndsTheRetries() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        advance(5_000)
        assertIs<LinkState.Reconnecting>(f.state)
        val opens = f.socks.latest(Foot.LEFT).opens.size

        f.settings.update { it.copy(autoReconnect = false) }
        advance(10)
        assertEquals(LinkState.Disconnected("No answer"), f.state)
        assertFalse(f.left.wantsConnection)
        advance(60_000)
        assertEquals(opens, f.socks.latest(Foot.LEFT).opens.size)
    }

    @Test
    fun withTheSettingOffADropOrAFailedOpenStaysDown() = runTest {
        val f = fixture(AppSettings(right = DeviceChoice.None, autoReconnect = false))
        advance(1_000)
        f.socks.latest(Foot.LEFT).drop()
        advance(60_000)
        assertEquals(LinkState.Disconnected("Link closed"), f.state)
        assertFalse(f.left.wantsConnection)

        f.socks.failOpen = true
        f.left.connect()
        advance(60_000)
        assertEquals(LinkState.Disconnected("No answer"), f.state)
        assertEquals(2, f.socks.latest(Foot.LEFT).opens.size)
    }

    @Test
    fun aReasonThatNeedsTheUserIsNotRetried() = runTest {
        val sock = DeviceChoice.Bluetooth("00:16:A4:00:00:01", "Block 3")
        val f = fixture(AppSettings(left = sock, right = DeviceChoice.None))
        advance(1_000)
        assertEquals(LinkState.Streaming, f.state)

        // Bluetooth goes off: the link drops, and the check before the retry finds out why.
        f.socks.problem = DeviceResult.Unavailable("Bluetooth is off", UnavailableReason.BluetoothOff)
        f.socks.latest(Foot.LEFT).drop()
        advance(60_000)
        val state = assertIs<LinkState.Unavailable>(f.state)
        assertEquals(UnavailableReason.BluetoothOff, state.reason)
        assertTrue(f.left.status.value.pendingConnect, "connects once fixed, as before")
        assertEquals(1, f.socks.socks.sumOf { it.opens.size }, "never retried meanwhile")

        f.socks.problem = null
        f.controller.recheckDevices()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.state)
    }

    @Test
    fun retryNowSkipsTheWait() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        advance(5_000) // waiting 4 s for attempt 3 (from 3.9 s)
        assertEquals(LinkState.Reconnecting(3, 3, "No answer"), f.state)
        val sock = f.socks.latest(Foot.LEFT)
        val opens = sock.opens.size

        f.socks.failOpen = false
        f.left.retryNow()
        advance(1)
        assertEquals(opens + 1, sock.opens.size)
        advance(500)
        assertEquals(LinkState.Streaming, f.state)

        // Not reconnecting: ignored (no stale request skips a later wait either).
        f.left.retryNow()
        advance(10)
        assertEquals(LinkState.Streaming, f.state)
        f.socks.failOpen = true
        sock.drop()
        advance(50)
        val waiting = assertIs<LinkState.Reconnecting>(f.state)
        advance(waiting.secondsLeft * 1_000L - 100)
        assertIs<LinkState.Reconnecting>(f.state, "the wait is not skipped")
    }

    @Test
    fun bothFeetReconnectIndependently() = runTest {
        val f = fixture(AppSettings())
        advance(1_000)
        f.socks.latest(Foot.LEFT).drop()
        advance(50)
        assertIs<LinkState.Reconnecting>(f.controller.left.status.value.state)
        assertEquals(LinkState.Streaming, f.controller.right.status.value.state)
        advance(1_500)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
    }

    @Test
    fun shutdownWhileWaitingClosesEverything() = runTest {
        val f = fixture()
        f.socks.failOpen = true
        advance(2_000)
        assertIs<LinkState.Reconnecting>(f.state)
        f.controller.shutdown()
        advance(60_000)
        assertEquals(2, f.socks.latest(Foot.LEFT).opens.size)
        assertFalse(f.socks.latest(Foot.LEFT).isOpen)
    }
}

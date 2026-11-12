package com.smartsocks.app.live

import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.simulatorTransport
import com.smartsocks.app.platform.unsupportedOnThisPlatform
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveControllerTest {
    /**
     * Records every transport it hands out and every open/close on them ("open L1", "close L1", ...); serial ports
     * are unsupported, like on Android.
     */
    private class RecordingDevices : DeviceFactory {
        val created = mutableListOf<Pair<Foot, SimulatedSockTransport>>()
        val events = mutableListOf<String>()

        override fun create(foot: Foot, choice: DeviceChoice): DeviceResult = when (choice) {
            is DeviceChoice.Simulator -> {
                val sim = simulatorTransport(foot, choice)
                created += foot to sim
                val name = foot.name.take(1) + created.count { it.first == foot }
                DeviceResult.Ready(Logging(name, sim, events), simulator = sim)
            }

            else -> unsupportedOnThisPlatform(choice, "Test")
        }

        fun latest(foot: Foot): SimulatedSockTransport = created.last { it.first == foot }.second
    }

    private class Logging(val name: String, val inner: SockTransport, val events: MutableList<String>) :
        SockTransport by inner {
        override suspend fun open() {
            events += "open $name"
            inner.open()
        }

        override suspend fun close() {
            if (events.lastOrNull() != "close $name") events += "close $name"
            inner.close()
        }
    }

    private class Fixture(
        val settings: SettingsRepository,
        val devices: RecordingDevices,
        val controller: LiveController,
    )

    private fun TestScope.controller(settings: SettingsRepository, devices: DeviceFactory) =
        LiveController(settings, devices, backgroundScope) { testScheduler.currentTime * 1_000_000 }

    private fun TestScope.repository(initial: AppSettings) =
        SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial)), backgroundScope)

    private fun TestScope.fixture(initial: AppSettings = AppSettings()): Fixture {
        val settings = repository(initial)
        val devices = RecordingDevices()
        return Fixture(settings, devices, controller(settings, devices))
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun autoConnectStreamsBothFeetFromTheStoredSettings() = runTest {
        val f = fixture(AppSettings(left = DeviceChoice.Simulator(SimMode.Standing), autoConnect = true))
        advance(2_000) // the rate is measured over the first full second of streaming

        for (foot in Foot.entries) {
            val status = f.controller.link(foot).status.value
            assertEquals(LinkState.Streaming, status.state, "$foot")
            assertNotNull(f.controller.link(foot).latest, "$foot")
            assertTrue(status.rate.packetsPerSecond in 95..105, "${status.rate}")
        }
        assertEquals(DeviceChoice.Simulator(SimMode.Standing), f.controller.left.status.value.device)
        assertEquals(SimMode.Standing, f.devices.latest(Foot.LEFT).mode)
        assertEquals("Simulated Left", f.controller.left.status.value.deviceName)
        assertEquals(2, f.devices.created.size)
    }

    @Test
    fun withoutAutoConnectFeetWaitUntilConnected() = runTest {
        val f = fixture(AppSettings(autoConnect = false))
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)
        assertNull(f.controller.left.latest)

        f.controller.left.toggle()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        assertEquals(LinkState.Idle, f.controller.right.status.value.state)
    }

    @Test
    fun disconnectStopsTheStreamAndConnectResumesIt() = runTest {
        val f = fixture()
        advance(1_000)
        val session = f.controller.left.session.value

        f.controller.left.toggle()
        advance(100)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)
        assertNull(f.controller.left.latest)
        assertEquals(SockCommand.Stop, f.devices.latest(Foot.LEFT).receivedCommands.last())
        assertEquals(LinkState.Streaming, f.controller.right.status.value.state)

        f.controller.left.connect()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        assertSame(session, f.controller.left.session.value, "reconnect reuses the session")
    }

    @Test
    fun aDeviceChangeRestartsOnlyThatFoot() = runTest {
        val f = fixture()
        advance(1_000)
        val left = f.controller.left.session.value
        val right = f.controller.right.session.value

        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(preloaded = true)) }
        advance(1_000)

        assertNotSame(left, f.controller.left.session.value)
        assertSame(right, f.controller.right.session.value)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        assertEquals(3, f.devices.created.size)
        assertEquals(SockCommand.Stop, f.devices.created[0].second.receivedCommands.last())
    }

    @Test
    fun aSimulatorModeChangeIsAppliedWithoutReconnecting() = runTest {
        val f = fixture()
        advance(1_000)
        val session = f.controller.left.session.value

        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(SimMode.Lifted)) }
        advance(100)

        assertSame(session, f.controller.left.session.value)
        assertEquals(SimMode.Lifted, f.devices.latest(Foot.LEFT).mode)
        assertEquals(DeviceChoice.Simulator(SimMode.Lifted), f.controller.left.status.value.device)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        assertEquals(2, f.devices.created.size)
    }

    @Test
    fun otherSettingsDoNotTouchTheSessions() = runTest {
        val f = fixture()
        advance(1_000)
        val left = f.controller.left.session.value
        f.settings.update { it.copy(theme = AppTheme.DARK, autoConnect = false) }
        advance(1_000)
        assertSame(left, f.controller.left.session.value)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
    }

    @Test
    fun streamSettingsRestartBothFeetAndKeepThemConnected() = runTest {
        val f = fixture()
        advance(1_000)
        f.settings.update { it.copy(stream = StreamSettings(rate = SampleRate.HZ_50)) }
        advance(2_000)

        for (foot in Foot.entries) {
            val status = f.controller.link(foot).status.value
            assertEquals(LinkState.Streaming, status.state)
            assertEquals(50, status.rate.nominalHz)
            assertEquals(SampleRate.HZ_50, f.devices.latest(foot).rate)
        }
        assertEquals(4, f.devices.created.size)
    }

    @Test
    fun anUnsupportedDeviceIsAnErrorStateNotACrash() = runTest {
        val f = fixture(AppSettings(left = DeviceChoice.SerialPort("COM5")))
        advance(1_000)

        val state = f.controller.left.status.value.state
        assertIs<LinkState.Unavailable>(state)
        assertEquals("Serial port COM5 is not supported on Test", state.message)
        assertNull(f.controller.left.session.value)
        f.controller.left.connect() // nothing to connect: ignored
        assertEquals(false, f.controller.left.wantsConnection)
        advance(1_000)
        assertIs<LinkState.Unavailable>(f.controller.left.status.value.state)
        assertEquals(LinkState.Streaming, f.controller.right.status.value.state)

        // Auto-connect did not stick to the unusable foot: switching it to a usable device does not connect it.
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator()) }
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)
        f.controller.left.connect()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
    }

    @Test
    fun noDeviceAndAFailingFactoryAreReported() = runTest {
        val controller = controller(repository(AppSettings(left = DeviceChoice.None))) { _, _ -> error("boom") }
        advance(1_000)
        assertEquals(LinkState.NoDevice, controller.left.status.value.state)
        assertEquals(LinkState.Unavailable("boom"), controller.right.status.value.state)
    }

    @Test
    fun aLinkDropIsReportedAndConnectTriesAgain() = runTest {
        val transports = mutableListOf<SimulatedSockTransport>()
        val controller = controller(repository(AppSettings())) { foot, _ ->
            DeviceResult.Ready(
                SimulatedSockTransport(foot, SimConfig(disconnectAfterPackets = 20)).also { transports += it },
            )
        }
        advance(2_000)
        assertEquals(LinkState.Disconnected("Link closed"), controller.left.status.value.state)
        assertTrue(controller.left.status.value.state.canConnect)
        val starts = transports[0].receivedCommands.count { it == SockCommand.Start }

        controller.left.connect()
        advance(2_000)
        assertEquals(starts + 1, transports[0].receivedCommands.count { it == SockCommand.Start })
        assertEquals(LinkState.Disconnected("Link closed"), controller.left.status.value.state)
    }

    @Test
    fun aDroppedLinkIsNotReconnectedByALaterSettingsChange() = runTest {
        var drop = true
        val settings = repository(AppSettings())
        val controller = controller(settings) { foot, _ ->
            DeviceResult.Ready(SimulatedSockTransport(foot, SimConfig(disconnectAfterPackets = if (drop) 20 else null)))
        }
        advance(2_000)
        assertEquals(LinkState.Disconnected("Link closed"), controller.left.status.value.state)
        assertEquals(false, controller.left.wantsConnection)

        drop = false
        settings.update { it.copy(stream = StreamSettings(rate = SampleRate.HZ_50)) }
        advance(2_000)
        assertEquals(LinkState.Idle, controller.left.status.value.state, "new session, not connected")
        controller.left.toggle()
        advance(1_000)
        assertEquals(LinkState.Streaming, controller.left.status.value.state)
    }

    @Test
    fun statusChangesOnlyWithWhatIsShownNotWithEveryStatsUpdate() = runTest {
        val f = fixture()
        val emissions = mutableListOf<FootStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.controller.left.status.collect { emissions += it }
        }
        advance(6_000)
        // Stats are published every 10 packets (~60 times here); the status only on state and whole-rate changes.
        assertTrue(emissions.size <= 8, "${emissions.size} emissions: $emissions")
        assertEquals(LinkState.Streaming, emissions.last().state)
        assertEquals(100, emissions.last().rate.nominalHz)
        assertTrue(emissions.last().rate.packetsPerSecond in 95..105)
    }

    @Test
    fun pausingClosesActiveLinksAndResumingReconnectsOnlyThose() = runTest {
        val f = fixture()
        advance(1_000)
        f.controller.left.disconnect()
        advance(100)

        f.controller.setPaused(true)
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.right.status.value.state)
        assertNull(f.controller.right.latest)
        assertEquals(SockCommand.Stop, f.devices.latest(Foot.RIGHT).receivedCommands.last())
        assertTrue(f.controller.right.wantsConnection, "the intent survives the pause")

        f.controller.setPaused(false)
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.right.status.value.state)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)
        assertEquals(2, f.devices.created.size, "same sessions, no new transports")
    }

    @Test
    fun pausedBeforeStartDoesNotAutoConnectUntilResumed() = runTest {
        val f = fixture()
        f.controller.setPaused(true)
        advance(2_000)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)
        assertTrue(f.devices.created.all { (_, sim) -> sim.receivedCommands.isEmpty() })

        f.controller.setPaused(false)
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
    }

    @Test
    fun aQuickDoubleTapCancelsInsteadOfReconnecting() = runTest {
        val f = fixture(AppSettings(autoConnect = false))
        advance(1_000)
        f.controller.left.toggle()
        f.controller.left.toggle()
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)
        assertTrue(f.devices.latest(Foot.LEFT).receivedCommands.isEmpty())

        f.controller.left.toggle()
        advance(100) // connecting (the simulator takes 300 ms)
        assertEquals(LinkState.Connecting, f.controller.left.status.value.state)
        f.controller.left.toggle()
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)
    }

    @Test
    fun processorSettingsApplyToTheRunningSessions() = runTest {
        val f = fixture()
        advance(1_000)
        val left = f.controller.left.session.value
        val processor = ProcessorSettings(contactOnThreshold = 50.0, contactOffThreshold = 30.0)
        f.settings.update { it.copy(processor = processor) }
        advance(1_000)

        assertSame(left, f.controller.left.session.value)
        assertEquals(processor, assertNotNull(left).processorSettings.value)
        assertEquals(processor, assertNotNull(f.controller.right.session.value).processorSettings.value)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        assertEquals(2, f.devices.created.size)
    }

    @Test
    fun theOldTransportIsClosedBeforeTheNewOneIsOpened() = runTest {
        val f = fixture()
        advance(1_000)
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(preloaded = true)) }
        advance(1_000)
        val left = f.devices.events.filter { it.endsWith("L1") || it.endsWith("L2") }
        assertEquals(listOf("open L1", "close L1", "open L2"), left)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
    }

    @Test
    fun aDeviceChangeWhileConnectingOrConfiguringSwitchesCleanly() = runTest {
        val f = fixture()
        advance(100)
        assertEquals(LinkState.Connecting, f.controller.left.status.value.state)
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(preloaded = true)) }
        advance(330) // the new simulator is connecting, then configuring
        assertEquals(LinkState.Configuring, f.controller.left.status.value.state)
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator()) }
        advance(1_000)

        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        val left = f.devices.events.filter { it.contains(" L") }
        assertEquals(listOf("open L1", "close L1", "open L2", "close L2", "open L3"), left)
        assertEquals(
            SockCommand.Stop,
            f.devices.created.filter {
                it.first == Foot.LEFT
            }[1].second.receivedCommands.last(),
        )
    }

    @Test
    fun shutdownWhileConnectingClosesTheTransports() = runTest {
        val f = fixture()
        advance(100)
        assertEquals(LinkState.Connecting, f.controller.left.status.value.state)
        f.controller.shutdown()
        assertEquals(listOf("open L1", "close L1"), f.devices.events.filter { it.contains(" L") })
        assertEquals(listOf("open R1", "close R1"), f.devices.events.filter { it.contains(" R") })
        assertNull(f.controller.left.session.value)
        assertNull(f.controller.right.session.value)
    }

    @Test
    fun theFrameLoopSleepsUntilAStatusChanges() = runTest {
        val f = fixture(AppSettings(autoConnect = false))
        advance(1_000)
        val idle = f.controller.sample(0)
        assertFalse(f.controller.isAnimating(idle))
        val woke = async { f.controller.awaitStatusChange(idle) }
        advance(1_000)
        assertFalse(woke.isCompleted)

        f.controller.left.connect()
        advance(1_000)
        assertTrue(woke.isCompleted)
        assertTrue(f.controller.isAnimating(f.controller.sample(1_000_000_000)))
    }

    @Test
    fun theSnapshotCarriesBothFeetBalanceAndTrails() = runTest {
        val standing = DeviceChoice.Simulator(SimMode.Standing)
        val f = fixture(AppSettings(left = standing, right = standing))
        advance(1_000)
        var snapshot = f.controller.sample(0)
        repeat(30) {
            advance(16)
            snapshot = f.controller.sample((it + 1) * 16_000_000L)
        }

        assertNotNull(snapshot.left.state)
        assertNotNull(snapshot.right.state)
        assertTrue(snapshot.left.trail.size > 10)
        val balance = assertNotNull(snapshot.balance)
        assertEquals(1.0, balance.left + balance.right, 1e-9)
        assertNotNull(balance.combinedCop)
        assertTrue(snapshot.fullScaleLoad >= 40.0)

        f.controller.left.disconnect()
        advance(100)
        snapshot = f.controller.sample(1_000_000_000L)
        assertNull(snapshot.left.state)
        assertTrue(snapshot.left.trail.isEmpty())
        assertEquals(1.0, assertNotNull(snapshot.balance).right)
    }

    @Test
    fun shutdownStopsBothSessions() = runTest {
        val f = fixture()
        advance(1_000)
        f.controller.shutdown()
        for (foot in Foot.entries) {
            assertEquals(SockCommand.Stop, f.devices.latest(foot).receivedCommands.last())
            assertNull(f.controller.link(foot).session.value)
        }
        assertEquals(listOf("close L1", "close R1", "open L1", "open R1"), f.devices.events.sorted())
    }
}

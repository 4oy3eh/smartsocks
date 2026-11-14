package com.smartsocks.app.live

import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.platform.unsupportedOnThisPlatform
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.sim.preloadedInsole
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Calibration, device availability and device conflicts through the [LiveController], as the settings use it. */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveControllerSettingsTest {
    /**
     * Simulators for every device kind except "none"; a Bluetooth device is subject to [problem], like on Android
     * with the permission missing or the adapter off. [dropAfterPackets] makes the links drop.
     */
    private class Devices(var problem: DeviceResult.Unavailable? = null, val dropAfterPackets: Long? = null) :
        DeviceFactory {
        val created = mutableListOf<Pair<Foot, SimulatedSockTransport>>()

        /** Every [create] call, also those that report [problem]. */
        val attempts = mutableListOf<Foot>()

        override fun create(foot: Foot, choice: DeviceChoice): DeviceResult {
            attempts += foot
            if (choice is DeviceChoice.Bluetooth) problem?.let { return it }
            val config = when (choice) {
                is DeviceChoice.Simulator -> SimConfig(mode = choice.mode).let {
                    if (choice.preloaded) it.preloadedInsole() else it
                }

                is DeviceChoice.Bluetooth, is DeviceChoice.SerialPort -> SimConfig(mode = SimMode.Lifted)

                DeviceChoice.None -> return unsupportedOnThisPlatform(choice, "Test")
            }
            val sim = SimulatedSockTransport(foot, config.copy(disconnectAfterPackets = dropAfterPackets))
            created += foot to sim
            return DeviceResult.Ready(sim)
        }

        override fun check(foot: Foot, choice: DeviceChoice): DeviceResult.Unavailable? =
            if (choice is DeviceChoice.Bluetooth) problem else null
    }

    private class Fixture(val settings: SettingsRepository, val devices: Devices, val controller: LiveController)

    private fun TestScope.fixture(initial: AppSettings, devices: Devices = Devices()): Fixture {
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial)), backgroundScope)
        val controller = LiveController(settings, devices, backgroundScope, reconnectDelayMillis = noJitter) {
            testScheduler.currentTime * 1_000_000
        }
        return Fixture(settings, devices, controller)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private val liftedPreloaded = DeviceChoice.Simulator(SimMode.Lifted, preloaded = true)

    /** No auto-zero: only an explicit calibration removes the preload. */
    private val noAutoZero = ProcessorSettings(autoZeroSeconds = 0.0)

    /** A range wide enough that the preload of an unloaded insole shows (at 64 kOhm it saturates to no load). */
    private val wide = StreamSettings(range = MeasurementRange.K256)

    private val sock = DeviceChoice.Bluetooth("00:16:A4:00:00:01", "Block 3")
    private val permissionNeeded =
        DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded)

    @Test
    fun calibrationRunsThroughTheControllerAndReportsProgressAndSuccess() = runTest {
        val f = fixture(AppSettings(left = liftedPreloaded, stream = wide, processor = noAutoZero))
        val left = f.controller.left
        advance(1_000)
        assertEquals(LinkState.Streaming, left.status.value.state)
        assertEquals(BaselineSource.NONE, assertNotNull(left.latest).baselineSource)
        assertTrue(assertNotNull(left.latest).loads.all { it > 1.0 }, "the preload shows as load")
        assertFalse(left.calibrated.value)

        assertTrue(left.calibrate())
        assertEquals(CalibrationState.Measuring(0.0), left.calibration.value)
        advance(1_000) // half of the 2 s
        val measuring = assertIs<CalibrationState.Measuring>(left.calibration.value)
        assertTrue(measuring.progress in 0.4..0.6, "$measuring")
        assertFalse(left.calibrated.value)

        advance(1_500)
        assertEquals(CalibrationState.Done, left.calibration.value)
        assertTrue(left.calibrated.value)
        val state = assertNotNull(left.latest)
        assertEquals(BaselineSource.CALIBRATED, state.baselineSource)
        assertTrue(state.loads.all { it < 1.0 }, "the preload is gone: ${state.loads.toList()}")
        assertFalse(f.controller.right.calibrated.value, "the other foot is independent")
    }

    @Test
    fun calibrationIsRefusedWhileNotStreaming() = runTest {
        val f = fixture(AppSettings(autoConnect = false))
        advance(1_000)
        assertFalse(f.controller.left.calibrate())
        assertEquals(CalibrationState.Failed(LiveController.NOT_STREAMING), f.controller.left.calibration.value)

        val none = fixture(AppSettings(left = DeviceChoice.None))
        advance(1_000)
        assertFalse(none.controller.left.calibrate())
        assertIs<CalibrationState.Failed>(none.controller.left.calibration.value)
    }

    @Test
    fun aLinkDropDuringCalibrationIsAFailureAndKeepsThePreviousState() = runTest {
        // ~1.2 s of packets at 100 Hz after streaming starts.
        val f = fixture(AppSettings(left = liftedPreloaded, autoReconnect = false), Devices(dropAfterPackets = 120))
        val left = f.controller.left
        advance(800)
        assertEquals(LinkState.Streaming, left.status.value.state)
        assertTrue(left.calibrate())
        advance(3_000)
        assertEquals(CalibrationState.Failed("The link dropped during calibration"), left.calibration.value)
        assertFalse(left.calibrated.value)
        assertIs<LinkState.Disconnected>(left.status.value.state)
    }

    @Test
    fun aDeviceChangeDuringCalibrationIsAFailure() = runTest {
        val f = fixture(AppSettings(left = liftedPreloaded))
        advance(1_000)
        assertTrue(f.controller.left.calibrate())
        advance(500)
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(SimMode.Lifted)) }
        advance(100)
        assertIs<CalibrationState.Failed>(f.controller.left.calibration.value)
        assertFalse(f.controller.left.calibrated.value)

        // Shown until the next rebuild, then stale.
        f.settings.update { it.copy(stream = StreamSettings(SampleRate.HZ_50)) }
        advance(1_500)
        assertEquals(CalibrationState.Idle, f.controller.left.calibration.value)
    }

    @Test
    fun clearingDuringMeasuringGoesIdleAndStaysIdle() = runTest {
        val f = fixture(AppSettings(left = liftedPreloaded, stream = wide, processor = noAutoZero))
        val left = f.controller.left
        advance(1_000)
        assertTrue(left.calibrate())
        advance(800)
        assertIs<CalibrationState.Measuring>(left.calibration.value)

        left.clearCalibration()
        assertEquals(CalibrationState.Idle, left.calibration.value)
        advance(3_000)
        assertEquals(CalibrationState.Idle, left.calibration.value, "the old watcher must not bring it back")
        assertFalse(left.calibrated.value)
        assertEquals(BaselineSource.NONE, assertNotNull(left.latest).baselineSource)
    }

    @Test
    fun aClearedCalibrationDoesNotComeBackWithTheNextSession() = runTest {
        val f = fixture(AppSettings(left = liftedPreloaded, stream = wide, processor = noAutoZero))
        val left = f.controller.left
        advance(1_000)
        left.calibrate()
        advance(2_500)
        assertTrue(left.calibrated.value)
        left.clearCalibration()
        advance(100)

        f.settings.update { it.copy(stream = it.stream.copy(rate = SampleRate.HZ_50)) }
        advance(1_500)
        assertFalse(left.calibrated.value)
        assertEquals(BaselineSource.NONE, assertNotNull(left.latest).baselineSource)
    }

    @Test
    fun aStaleFailureIsClearedWhenTheSessionIsRebuilt() = runTest {
        val f = fixture(AppSettings(autoConnect = false))
        advance(1_000)
        assertFalse(f.controller.left.calibrate())
        assertIs<CalibrationState.Failed>(f.controller.left.calibration.value)
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(preloaded = true)) }
        advance(500)
        assertEquals(CalibrationState.Idle, f.controller.left.calibration.value)
    }

    @Test
    fun clearingTheCalibrationBringsThePreloadBack() = runTest {
        val f = fixture(AppSettings(left = liftedPreloaded, stream = wide, processor = noAutoZero))
        val left = f.controller.left
        advance(1_000)
        left.calibrate()
        advance(2_500)
        assertTrue(left.calibrated.value)

        left.clearCalibration()
        assertFalse(left.calibrated.value)
        assertEquals(CalibrationState.Idle, left.calibration.value)
        advance(100)
        val state = assertNotNull(left.latest)
        assertEquals(BaselineSource.NONE, state.baselineSource)
        assertTrue(state.loads.all { it > 1.0 }, "${state.loads.toList()}")
        assertEquals(LinkState.Streaming, left.status.value.state)
    }

    @Test
    fun theCalibrationSurvivesARateChangeButNotARangeOrDeviceChange() = runTest {
        val f = fixture(AppSettings(left = liftedPreloaded, stream = wide, processor = noAutoZero))
        val left = f.controller.left
        advance(1_000)
        left.calibrate()
        advance(2_500)
        val session = left.session.value

        f.settings.update { it.copy(stream = it.stream.copy(rate = SampleRate.HZ_50)) }
        advance(1_500)
        assertNotSame(session, left.session.value)
        assertTrue(left.calibrated.value)
        assertEquals(BaselineSource.CALIBRATED, assertNotNull(left.latest).baselineSource)
        assertEquals(CalibrationState.Done, left.calibration.value, "still the calibration in effect")

        f.settings.update { it.copy(stream = StreamSettings(SampleRate.HZ_50, MeasurementRange.K64)) }
        advance(1_500)
        assertFalse(left.calibrated.value, "a baseline from another range does not apply")
        assertEquals(BaselineSource.NONE, assertNotNull(left.latest).baselineSource)
        assertEquals(CalibrationState.Idle, left.calibration.value)

        f.settings.update { it.copy(stream = StreamSettings(SampleRate.HZ_50, MeasurementRange.K256)) }
        advance(1_500)
        assertTrue(left.calibrated.value, "back at the range it was measured at")

        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(SimMode.Lifted)) }
        advance(1_500)
        assertFalse(left.calibrated.value, "another insole")
    }

    @Test
    fun aMissingPermissionIsReportedAndTheFootConnectsOnceGranted() = runTest {
        val devices = Devices(problem = permissionNeeded)
        val f = fixture(AppSettings(left = sock), devices)
        advance(1_000)
        val state = assertIs<LinkState.Unavailable>(f.controller.left.status.value.state)
        assertEquals(UnavailableReason.PermissionNeeded, state.reason)
        assertEquals("Bluetooth permission needed", state.message)
        assertTrue(f.controller.left.wantsConnection, "auto-connect is kept for a fixable problem")
        assertTrue(f.controller.left.status.value.pendingConnect)
        assertEquals(LinkState.Streaming, f.controller.right.status.value.state)
        assertEquals(1, devices.attempts.count { it == Foot.LEFT })

        f.controller.recheckDevices() // still missing: one more attempt, nothing changes
        advance(5_000)
        assertIs<LinkState.Unavailable>(f.controller.left.status.value.state)
        assertEquals(2, devices.attempts.count { it == Foot.LEFT }, "no retry loop")

        val right = f.controller.right.session.value
        devices.problem = null
        f.controller.recheckDevices()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        assertFalse(f.controller.left.status.value.pendingConnect)
        assertSame(right, f.controller.right.session.value, "a usable foot is not rebuilt")
        assertEquals(3, devices.attempts.count { it == Foot.LEFT })
        assertEquals(1, devices.attempts.count { it == Foot.RIGHT })
    }

    @Test
    fun aPendingConnectCanBeDroppedAndIsDroppedByChoosingAnotherDevice() = runTest {
        val devices = Devices(problem = permissionNeeded)
        val f = fixture(AppSettings(left = sock), devices)
        advance(1_000)
        assertTrue(f.controller.left.status.value.pendingConnect)
        f.controller.left.disconnect() // "Don't connect" / "Not now"
        advance(100)
        assertFalse(f.controller.left.status.value.pendingConnect)
        devices.problem = null
        f.controller.recheckDevices()
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state, "granted later: not connected by surprise")

        // Waiting again (auto-connect), then the user switches to the simulator: it must not connect by itself.
        devices.problem = permissionNeeded
        val g = fixture(AppSettings(left = sock), devices)
        advance(1_000)
        assertTrue(g.controller.left.wantsConnection)
        g.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator()) }
        advance(1_000)
        assertEquals(LinkState.Idle, g.controller.left.status.value.state)
        assertFalse(g.controller.left.wantsConnection)
    }

    @Test
    fun aStreamChangeKeepsThePendingConnectOfTheSameBlockedSock() = runTest {
        val devices = Devices(problem = permissionNeeded)
        val f = fixture(AppSettings(left = sock), devices)
        advance(1_000)
        f.settings.update { it.copy(stream = StreamSettings(SampleRate.HZ_50)) }
        advance(1_000)
        assertTrue(f.controller.left.wantsConnection)
        devices.problem = null
        f.controller.recheckDevices()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
    }

    @Test
    fun theCalibrationSurvivesBluetoothBeingSwitchedOffAndOnAgain() = runTest {
        val devices = Devices()
        val f = fixture(AppSettings(left = sock), devices)
        val left = f.controller.left
        advance(1_000)
        assertTrue(left.calibrate())
        advance(2_500)
        assertTrue(left.calibrated.value)

        left.disconnect()
        advance(100)
        devices.problem = DeviceResult.Unavailable("Bluetooth is off", UnavailableReason.BluetoothOff)
        left.connect()
        advance(500)
        assertEquals(UnavailableReason.BluetoothOff, assertIs<LinkState.Unavailable>(left.status.value.state).reason)

        devices.problem = null
        f.controller.recheckDevices()
        advance(1_500)
        assertEquals(LinkState.Streaming, left.status.value.state)
        assertTrue(left.calibrated.value, "the zero point of the same sock is kept")
        assertEquals(BaselineSource.CALIBRATED, assertNotNull(left.latest).baselineSource)
    }

    @Test
    fun theCalibrationBelongsToTheSockNotToItsDisplayName() = runTest {
        val f = fixture(AppSettings(left = sock, right = DeviceChoice.SerialPort("COM5")))
        advance(1_000)
        for (foot in Foot.entries) assertTrue(f.controller.link(foot).calibrate())
        advance(2_500)
        assertTrue(f.controller.left.calibrated.value && f.controller.right.calibrated.value)

        f.settings.update {
            it.withDevice(Foot.LEFT, DeviceChoice.Bluetooth(sock.address.lowercase(), "Renamed"))
                .withDevice(Foot.RIGHT, DeviceChoice.SerialPort("com5"))
        }
        advance(1_500)
        assertEquals(2, f.devices.created.count { it.first == Foot.LEFT }, "rebuilt with the new name")
        assertTrue(f.controller.left.calibrated.value)
        assertTrue(f.controller.right.calibrated.value)

        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Bluetooth("00:16:A4:00:00:09", "Block 3")) }
        advance(1_500)
        assertFalse(f.controller.left.calibrated.value, "another sock with the same name")
    }

    @Test
    fun bluetoothSwitchedOffIsCaughtBeforeConnecting() = runTest {
        val devices = Devices()
        val f = fixture(AppSettings(left = sock, autoConnect = false), devices)
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.left.status.value.state)

        devices.problem = DeviceResult.Unavailable("Bluetooth is off", UnavailableReason.BluetoothOff)
        f.controller.left.connect()
        advance(1_000)
        val state = assertIs<LinkState.Unavailable>(f.controller.left.status.value.state)
        assertEquals(UnavailableReason.BluetoothOff, state.reason)
        val sim = devices.created.first { it.first == Foot.LEFT }.second
        assertTrue(sim.receivedCommands.isEmpty(), "the link was never opened")

        devices.problem = null
        f.controller.recheckDevices()
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state, "the user's connect is carried out")
    }

    @Test
    fun anUnsupportedDeviceIsNotRetriedByARecheck() = runTest {
        val devices = Devices(problem = DeviceResult.Unavailable("This device has no Bluetooth"))
        val f = fixture(AppSettings(left = sock), devices)
        advance(1_000)
        assertEquals(
            UnavailableReason.Unsupported,
            assertIs<LinkState.Unavailable>(f.controller.left.status.value.state).reason,
        )
        assertFalse(f.controller.left.wantsConnection)
        devices.problem = null
        f.controller.recheckDevices()
        advance(1_000)
        assertIs<LinkState.Unavailable>(f.controller.left.status.value.state)
    }

    @Test
    fun theSameSockForBothFeetLeavesTheRightFootUnavailable() = runTest {
        val f = fixture(AppSettings(left = DeviceChoice.SerialPort("COM5"), right = DeviceChoice.SerialPort("com5")))
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        val state = assertIs<LinkState.Unavailable>(f.controller.right.status.value.state)
        assertEquals(UnavailableReason.InUse, state.reason)
        assertEquals(1, f.devices.created.size, "no transport for the right foot")

        // The left foot moves away: the right one becomes usable (not connected by surprise).
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator()) }
        advance(1_000)
        assertEquals(LinkState.Idle, f.controller.right.status.value.state)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
    }
}

package com.smartsocks.app.recording

import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.TestSocks
import com.smartsocks.app.live.noJitter
import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.platform.simulatorTransport
import com.smartsocks.app.platform.unsupportedOnThisPlatform
import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundRecordingTest {
    private val simulators = DeviceFactory { foot, choice ->
        when (choice) {
            is DeviceChoice.Simulator -> DeviceResult.Ready(simulatorTransport(foot, choice))

            // A "real" sock that is a simulator underneath: what matters here is the device kind.
            is DeviceChoice.Bluetooth -> DeviceResult.Ready(simulatorTransport(foot, DeviceChoice.Simulator()))

            else -> unsupportedOnThisPlatform(choice, "Test")
        }
    }

    /** Records what was asked; the test reports back like the platform would. */
    private class FakeKeepAlive : KeepAlive {
        val calls = mutableListOf<String>()
        var lastRequest = 0L

        override fun start(type: KeepAliveType, request: Long) {
            calls += "start $type"
            lastRequest = request
        }

        override fun stop() {
            calls += "stop"
        }
    }

    private class Fixture(
        val live: LiveController,
        val recorder: RecordingController,
        val background: BackgroundRecording,
        val keepAlive: FakeKeepAlive,
        val store: MemorySessionStore,
        val settings: SettingsRepository,
    )

    /** Both feet on walking simulators (virtual time) unless [devices] says otherwise, auto-connect on. */
    private fun TestScope.fixture(
        initial: AppSettings = AppSettings(),
        bluetoothPermission: Boolean = true,
        devices: DeviceFactory = simulators,
    ): Fixture {
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial)), backgroundScope)
        val live =
            LiveController(
                settings,
                devices,
                backgroundScope,
                initiallyPaused = true,
                reconnectDelayMillis = noJitter,
            ) {
                testScheduler.currentTime * 1_000_000
            }
        val store = MemorySessionStore()
        val recorder =
            RecordingController(live, store, settings, backgroundScope, StandardTestDispatcher(testScheduler))
        val keepAlive = FakeKeepAlive()
        val background =
            BackgroundRecording(live, recorder, settings, keepAlive, { bluetoothPermission }, backgroundScope)
        return Fixture(live, recorder, background, keepAlive, store, settings)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun Fixture.streaming(foot: Foot) = live.link(foot).status.value.state == LinkState.Streaming

    private fun Fixture.bothStreaming() = streaming(Foot.LEFT) && streaming(Foot.RIGHT)

    private fun Fixture.noneStreaming() = !streaming(Foot.LEFT) && !streaming(Foot.RIGHT)

    /** In the foreground with both feet streaming, then recording with the keep-alive granted. */
    private fun TestScope.recordingKeptAlive(
        initial: AppSettings = AppSettings(),
        devices: DeviceFactory = simulators,
        type: KeepAliveType = KeepAliveType.SpecialUse,
    ): Fixture {
        val f = fixture(initial, devices = devices)
        f.background.onForeground()
        advance(1_000)
        assertTrue(f.bothStreaming())
        assertTrue(f.recorder.start())
        runCurrent()
        assertEquals(listOf("start $type"), f.keepAlive.calls)
        f.background.onKeepAliveStarted(f.keepAlive.lastRequest)
        runCurrent()
        return f
    }

    @Test
    fun policyPausesInTheBackgroundUnlessARecordingIsKeptAliveWithLinksUp() {
        val decide = BackgroundPolicy::decide
        // foreground, recording, keptAlive, linksUp
        assertEquals(BackgroundAction.Run, decide(true, false, false, false))
        assertEquals(BackgroundAction.Run, decide(true, true, false, false))
        assertEquals(BackgroundAction.Pause, decide(false, false, false, true))
        assertEquals(BackgroundAction.Pause, decide(false, false, true, true))
        assertEquals(BackgroundAction.StopForBackground, decide(false, true, false, true))
        assertEquals(BackgroundAction.StopLinksLost, decide(false, true, true, false))
        assertEquals(BackgroundAction.RunRecorded, decide(false, true, true, true))
    }

    @Test
    fun keepAliveTypeIsConnectedDeviceOnlyForARealSockWithThePermission() {
        val sim = DeviceChoice.Simulator(SimMode.Walking)
        val bt = DeviceChoice.Bluetooth("00:11:22:33:44:55", "Sock")
        assertEquals(KeepAliveType.SpecialUse, keepAliveType(listOf(sim, sim), bluetoothPermission = true))
        assertEquals(KeepAliveType.ConnectedDevice, keepAliveType(listOf(bt), bluetoothPermission = true))
        assertEquals(KeepAliveType.ConnectedDevice, keepAliveType(listOf(sim, bt), bluetoothPermission = true))
        assertEquals(KeepAliveType.SpecialUse, keepAliveType(listOf(sim, bt), bluetoothPermission = false))
        assertEquals(KeepAliveType.ConnectedDevice, keepAliveType(listOf(DeviceChoice.SerialPort("COM5")), true))
        assertEquals(KeepAliveType.SpecialUse, keepAliveType(emptyList(), bluetoothPermission = true))
    }

    @Test
    fun startsPausedUntilTheForeground() = runTest {
        val f = fixture()
        val states = mutableListOf<LinkState>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(f.live.left.status, f.live.right.status) { l, r -> listOf(l.state, r.state) }
                .collect { states += it }
        }
        advance(2_000)
        assertTrue(f.live.paused.value)
        assertTrue(f.noneStreaming())
        assertTrue(states.none { it.isActive }, "nothing even connects before the foreground: $states")
        f.background.onForeground()
        advance(1_000)
        assertFalse(f.live.paused.value)
        assertTrue(f.bothStreaming())
    }

    @Test
    fun notRecordingTheFeetPauseInTheBackgroundAndResumeInTheForeground() = runTest {
        val f = fixture()
        f.background.onForeground()
        advance(1_000)
        assertTrue(f.bothStreaming())
        f.background.onBackground()
        advance(500)
        assertTrue(f.live.paused.value)
        assertTrue(f.noneStreaming())
        assertTrue(f.keepAlive.calls.isEmpty(), "no service without a recording")
        f.background.onForeground()
        advance(1_000)
        assertTrue(f.bothStreaming())
    }

    @Test
    fun aKeptAliveRecordingGoesOnInTheBackgroundUntilStoppedFromTheNotification() = runTest {
        val f = recordingKeptAlive()
        val id = assertIs<RecordingState.Recording>(f.recorder.state.value).id
        advance(1_000)
        f.background.onBackground()
        advance(30_000)
        assertFalse(f.live.paused.value)
        assertTrue(f.bothStreaming())
        assertIs<RecordingState.Recording>(f.recorder.state.value)
        assertEquals("Mark 1", f.background.perform(RecordingCommand.Mark))
        advance(10_000)

        assertEquals(null, f.background.perform(RecordingCommand.Stop))
        advance(100)
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        val result = assertNotNull(f.recorder.lastResult.value)
        assertEquals(StopReason.User, result.reason)
        assertEquals(1, result.markers)
        assertEquals(listOf("start ${KeepAliveType.SpecialUse}", "stop"), f.keepAlive.calls)
        assertFalse(f.background.keptAlive.value)
        // Over in the background: the feet pause.
        assertTrue(f.live.paused.value)
        assertTrue(f.noneStreaming())

        // 41 s without a gap, both feet at 100 Hz, the mark 31 s in.
        val timeline = SessionTimeline.read(f.store.lines(id))
        assertEquals(41_000.0, timeline.durationMillis, 100.0)
        assertEquals(8_200.0, result.rows.toDouble(), 60.0)
        assertEquals(31_000.0, timeline.markers.single().tMillis, 100.0)
    }

    @Test
    fun stoppingFromTheNotificationInTheForegroundKeepsTheFeetStreaming() = runTest {
        val f = recordingKeptAlive()
        advance(1_000)
        f.background.perform(RecordingCommand.Stop)
        advance(500)
        assertEquals(StopReason.User, f.recorder.lastResult.value?.reason)
        assertEquals("stop", f.keepAlive.calls.last())
        assertFalse(f.live.paused.value)
        assertTrue(f.bothStreaming())
    }

    @Test
    fun aRefusedKeepAliveStopsTheRecordingInTheBackgroundAsBefore() = runTest {
        val f = fixture()
        f.background.onForeground()
        advance(1_000)
        f.recorder.start()
        runCurrent()
        f.background.onKeepAliveFailed(f.keepAlive.lastRequest)
        advance(1_000)
        assertIs<RecordingState.Recording>(f.recorder.state.value, "goes on while in the foreground")
        f.background.onBackground()
        advance(500)
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        assertEquals(StopReason.Background, f.recorder.lastResult.value?.reason)
        assertTrue(f.live.paused.value)
        assertTrue(f.noneStreaming())
        assertEquals("stop", f.keepAlive.calls.last())
    }

    @Test
    fun leavingBeforeTheKeepAliveIsConfirmedStopsTheRecording() = runTest {
        val f = fixture()
        f.background.onForeground()
        advance(1_000)
        f.recorder.start()
        runCurrent()
        f.background.onBackground()
        advance(500)
        assertEquals(StopReason.Background, f.recorder.lastResult.value?.reason)
        assertTrue(f.live.paused.value)
        assertEquals(listOf("start ${KeepAliveType.SpecialUse}", "stop"), f.keepAlive.calls)
        assertFalse(f.background.keptAlive.value)
    }

    @Test
    fun leavingWhileTheRecordingIsStartingNeverAsksForTheKeepAlive() = runTest {
        val f = fixture()
        f.background.onForeground()
        advance(1_000)
        assertTrue(f.recorder.start())
        assertEquals(RecordingState.Starting, f.recorder.state.value)
        f.background.onBackground()
        advance(500)
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        assertEquals(StopReason.Background, f.recorder.lastResult.value?.reason)
        assertTrue(f.keepAlive.calls.isEmpty(), "no service start from the background: ${f.keepAlive.calls}")
        assertTrue(f.live.paused.value)
    }

    @Test
    fun aLateConfirmationOfAnEarlierRecordingIsIgnored() = runTest {
        val f = fixture()
        f.background.onForeground()
        advance(1_000)
        f.recorder.start()
        runCurrent()
        val first = f.keepAlive.lastRequest
        f.recorder.stopAndJoin()
        runCurrent()
        // The service answers only now, after that recording ended.
        f.background.onKeepAliveStarted(first)
        assertFalse(f.background.keptAlive.value)

        f.recorder.start()
        runCurrent()
        val second = f.keepAlive.lastRequest
        assertTrue(second != first)
        f.background.onKeepAliveStarted(first)
        assertFalse(f.background.keptAlive.value, "not confirmed for this recording")
        f.background.onBackground()
        advance(500)
        assertEquals(StopReason.Background, f.recorder.lastResult.value?.reason)

        // And a late end of an earlier one does not stop the current one.
        f.background.onForeground()
        advance(1_000)
        f.recorder.start()
        runCurrent()
        f.background.onKeepAliveStarted(f.keepAlive.lastRequest)
        f.background.onKeepAliveEnded(second)
        assertTrue(f.background.keptAlive.value)
    }

    @Test
    fun inTheBackgroundOnlyTheRecordedFeetStream() = runTest {
        val f = fixture()
        f.background.onForeground()
        advance(1_000)
        f.live.right.disconnect()
        advance(500)
        assertTrue(f.recorder.start())
        runCurrent()
        f.background.onKeepAliveStarted(f.keepAlive.lastRequest)
        f.live.right.connect()
        advance(1_000)
        assertTrue(f.bothStreaming())
        assertEquals(setOf(Foot.LEFT), assertIs<RecordingState.Recording>(f.recorder.state.value).feet)

        f.background.onBackground()
        advance(1_000)
        assertTrue(f.streaming(Foot.LEFT))
        assertFalse(f.streaming(Foot.RIGHT), "not recorded: paused in the background")
        assertEquals(setOf(Foot.RIGHT), f.live.pausedFeet.value)
        assertTrue(f.live.right.wantsConnection, "the request is kept")

        f.background.onForeground()
        advance(1_000)
        assertTrue(f.bothStreaming())
        assertEquals(emptySet(), f.live.pausedFeet.value)
    }

    @Test
    fun aKeepAliveThatEndsInTheBackgroundFinalizesTheRecording() = runTest {
        val f = recordingKeptAlive()
        f.background.onBackground()
        advance(5_000)
        assertIs<RecordingState.Recording>(f.recorder.state.value)
        f.background.onKeepAliveEnded(f.keepAlive.lastRequest) // the service was destroyed
        advance(500)
        assertEquals(StopReason.Background, f.recorder.lastResult.value?.reason)
        assertTrue(f.store.entries.values.single().csv.closed)
        assertTrue(f.live.paused.value)
    }

    @Test
    fun aKeepAliveThatEndsInTheForegroundLeavesTheRecordingAlone() = runTest {
        val f = recordingKeptAlive()
        f.background.onKeepAliveEnded(f.keepAlive.lastRequest)
        advance(2_000)
        assertIs<RecordingState.Recording>(f.recorder.state.value)
        assertTrue(f.bothStreaming())
    }

    @Test
    fun linksLostInTheBackgroundFinalizeTheRecording() = runTest {
        val f = recordingKeptAlive()
        f.background.onBackground()
        advance(2_000)
        f.live.left.disconnect()
        advance(1_000)
        assertIs<RecordingState.Recording>(f.recorder.state.value, "one foot is still linked")
        f.live.right.disconnect()
        advance(500)
        assertIs<RecordingState.Recording>(f.recorder.state.value, "a moment without links is not enough")
        advance(BackgroundRecording.DOWN_GRACE_MILLIS)
        assertEquals(StopReason.LinksLost, f.recorder.lastResult.value?.reason)
        assertTrue(f.live.paused.value)
    }

    @Test
    fun feetThatReconnectInTheBackgroundKeepTheRecordingGoing() = runTest {
        val socks = TestSocks({ testScheduler.currentTime })
        val f = recordingKeptAlive(devices = socks)
        f.background.onBackground()
        advance(2_000)
        // Both links drop at once: both reconnecting, none linked for a moment.
        socks.latest(Foot.LEFT).drop()
        socks.latest(Foot.RIGHT).drop()
        advance(50)
        for (foot in Foot.entries) assertIs<LinkState.Reconnecting>(f.live.link(foot).status.value.state)
        assertIs<RecordingState.Recording>(f.recorder.state.value, "reconnecting feet are still recorded")
        assertEquals(emptySet(), f.live.pausedFeet.value)
        advance(1_500)
        assertTrue(f.bothStreaming())
        advance(1_000)
        f.recorder.stopAndJoin()
        assertEquals(StopReason.User, f.recorder.lastResult.value?.reason)
        val timeline = SessionTimeline.read(f.store.lines(f.store.entries.keys.single()))
        for (foot in Foot.entries) {
            assertTrue(timeline.track(foot).lastMillis!! > 4_000, "$foot recorded after the reconnect")
        }
    }

    @Test
    fun aFootThatCannotReconnectByItselfEndsTheRecordingInTheBackground() = runTest {
        val socks = TestSocks({ testScheduler.currentTime })
        val l = DeviceChoice.Bluetooth("00:16:A4:00:00:01", "Block 3")
        val r = DeviceChoice.Bluetooth("00:16:A4:00:00:02", "Block 3")
        val f = recordingKeptAlive(AppSettings(left = l, right = r), socks, KeepAliveType.ConnectedDevice)
        f.background.onBackground()
        advance(1_000)
        // Bluetooth switched off: both links drop, and the device check says it is pointless to retry.
        socks.problem = DeviceResult.Unavailable("Bluetooth is off", UnavailableReason.BluetoothOff)
        socks.latest(Foot.LEFT).drop()
        socks.latest(Foot.RIGHT).drop()
        advance(100)
        assertIs<LinkState.Unavailable>(f.live.left.status.value.state)
        advance(BackgroundRecording.DOWN_GRACE_MILLIS)
        assertEquals(StopReason.LinksLost, f.recorder.lastResult.value?.reason)
        assertTrue(f.store.entries.values.single().csv.closed)
        assertTrue(f.live.paused.value)
    }

    @Test
    fun withReconnectOffADropInTheBackgroundEndsTheRecordingAsBefore() = runTest {
        val socks = TestSocks({ testScheduler.currentTime })
        val f = recordingKeptAlive(AppSettings(autoReconnect = false), socks)
        f.background.onBackground()
        advance(1_000)
        socks.latest(Foot.LEFT).drop()
        advance(100)
        assertIs<RecordingState.Recording>(f.recorder.state.value, "the right foot is still linked")
        socks.latest(Foot.RIGHT).drop()
        advance(100 + BackgroundRecording.DOWN_GRACE_MILLIS)
        assertEquals(StopReason.LinksLost, f.recorder.lastResult.value?.reason)
    }

    @Test
    fun switchingReconnectOffWhileBothFeetReconnectEndsTheRecording() = runTest {
        val socks = TestSocks({ testScheduler.currentTime })
        val f = recordingKeptAlive(devices = socks)
        f.background.onBackground()
        advance(1_000)
        socks.failOpen = true
        socks.latest(Foot.LEFT).drop()
        socks.latest(Foot.RIGHT).drop()
        advance(5_000)
        assertIs<RecordingState.Recording>(f.recorder.state.value)
        f.settings.update { it.copy(autoReconnect = false) }
        advance(100 + BackgroundRecording.DOWN_GRACE_MILLIS)
        assertEquals(StopReason.LinksLost, f.recorder.lastResult.value?.reason)
    }

    @Test
    fun feetThatKeepFailingInTheBackgroundEndTheRecordingAfterFiveMinutes() = runTest {
        val socks = TestSocks({ testScheduler.currentTime })
        val f = recordingKeptAlive(devices = socks)
        socks.failOpen = true
        socks.latest(Foot.LEFT).drop()
        socks.latest(Foot.RIGHT).drop()
        // In the foreground the user sees it: no limit there, and that time does not count.
        advance(10 * 60_000L)
        assertIs<RecordingState.Recording>(f.recorder.state.value)

        f.background.onBackground()
        advance(BackgroundRecording.RECONNECT_GIVE_UP_MILLIS - 1_000)
        assertIs<RecordingState.Recording>(f.recorder.state.value, "still trying")
        assertTrue(f.live.left.status.value.state.isRecovering)
        advance(2_000)
        assertEquals(StopReason.LinksLost, f.recorder.lastResult.value?.reason)
        assertTrue(f.store.entries.values.single().csv.closed)
        assertTrue(f.live.paused.value)
    }

    @Test
    fun aFootThatStreamsAgainRestartsTheFiveMinutes() = runTest {
        val socks = TestSocks({ testScheduler.currentTime })
        val f = recordingKeptAlive(devices = socks)
        f.background.onBackground()
        socks.failOpen = true
        socks.latest(Foot.LEFT).drop()
        socks.latest(Foot.RIGHT).drop()
        advance(4 * 60_000L)
        socks.failOpen = false
        advance(31_000) // the next attempts (at most 30 s apart) connect
        assertTrue(f.bothStreaming())
        socks.failOpen = true
        socks.latest(Foot.LEFT).drop()
        socks.latest(Foot.RIGHT).drop()
        advance(4 * 60_000L)
        assertIs<RecordingState.Recording>(f.recorder.state.value, "four minutes since the feet streamed")
        advance(60_000L + 1_000)
        assertEquals(StopReason.LinksLost, f.recorder.lastResult.value?.reason)
    }

    @Test
    fun aMomentWithoutLinksInTheBackgroundDoesNotEndTheRecording() = runTest {
        val f = recordingKeptAlive()
        f.background.onBackground()
        advance(1_000)
        f.live.left.disconnect()
        f.live.right.disconnect()
        advance(200)
        assertTrue(f.noneStreaming())
        f.live.left.connect()
        f.live.right.connect()
        advance(BackgroundRecording.DOWN_GRACE_MILLIS * 2)
        assertIs<RecordingState.Recording>(f.recorder.state.value)
        assertTrue(f.bothStreaming())
        assertEquals(emptySet(), f.live.pausedFeet.value)
    }

    @Test
    fun theNextRecordingAsksForTheKeepAliveAgain() = runTest {
        val f = recordingKeptAlive()
        f.recorder.stopAndJoin()
        runCurrent()
        assertTrue(f.recorder.start())
        runCurrent()
        assertEquals(
            listOf("start ${KeepAliveType.SpecialUse}", "stop", "start ${KeepAliveType.SpecialUse}"),
            f.keepAlive.calls,
        )
        assertFalse(f.background.keptAlive.value, "the new one is not confirmed yet")
    }

    @Test
    fun aBluetoothSockIsRecordedAsAConnectedDeviceIfThePermissionIsGranted() = runTest {
        val sock = AppSettings(left = DeviceChoice.Bluetooth("00:11:22:33:44:55", "Sock"))
        for ((permission, expected) in listOf(
            true to KeepAliveType.ConnectedDevice,
            false to KeepAliveType.SpecialUse,
        )) {
            val f = fixture(sock, bluetoothPermission = permission)
            f.background.onForeground()
            advance(1_000)
            assertTrue(f.recorder.start())
            runCurrent()
            assertEquals(listOf("start $expected"), f.keepAlive.calls)
            assertEquals(expected, f.background.lastType.value)
            f.recorder.stopAndJoin()
        }
    }

    @Test
    fun startedScreensIgnoreConfigurationChanges() {
        val changes = mutableListOf<Boolean>()
        val screens = StartedScreens { changes += it }
        screens.onStarted()
        assertEquals(listOf(true), changes)
        // Rotation: the old activity stops for a configuration change, the new one starts.
        screens.onStopped(changingConfigurations = true)
        screens.onStarted()
        assertEquals(listOf(true), changes)
        // A second activity on top, then both go.
        screens.onStarted()
        screens.onStopped(changingConfigurations = false)
        assertEquals(listOf(true), changes)
        screens.onStopped(changingConfigurations = false)
        assertEquals(listOf(true, false), changes)
        screens.onStopped(changingConfigurations = false)
        assertEquals(listOf(true, false), changes, "never below zero")
        screens.onStarted()
        assertEquals(listOf(true, false, true), changes)
    }

    @Test
    fun notificationUpdatesForDropsAreRarerThanForMarks() {
        val posted = RecordingState.Recording("a", Foot.entries.toSet(), 0)
        val delay = BackgroundRecording::notificationDelayMillis
        assertEquals(0, delay(null, posted, 0))
        // A mark: a second after the last post.
        assertEquals(700, delay(posted, posted.copy(markers = 1), 300))
        assertEquals(0, delay(posted, posted.copy(markers = 1), 5_000))
        // Only drops: ten seconds after it.
        assertEquals(9_700, delay(posted, posted.copy(dropped = 5), 300))
        assertEquals(0, delay(posted, posted.copy(droppedRaw = 5), 12_000))
        // Another recording: as a mark.
        assertEquals(0, delay(posted, posted.copy(id = "b"), 1_000))
    }

    @Test
    fun notificationTextShowsFeetMarksAndDrops() {
        val base = RecordingState.Recording("id", Foot.entries.toSet(), 0)
        assertEquals("Both feet", BackgroundRecording.notificationText(base))
        assertEquals(
            "Left foot · 2 marks · 12 samples dropped",
            BackgroundRecording.notificationText(
                base.copy(feet = setOf(Foot.LEFT), markers = 2, dropped = 12, bytes = 999),
            ),
        )
        assertEquals(
            "Right foot · 1 mark · 3 raw packets dropped",
            BackgroundRecording.notificationText(base.copy(feet = setOf(Foot.RIGHT), markers = 1, droppedRaw = 3)),
        )
    }
}

package com.smartsocks.desktop.tools

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.BluetoothDeviceEntry
import com.smartsocks.app.platform.BluetoothState
import com.smartsocks.app.platform.DeviceActions
import com.smartsocks.app.platform.DeviceCatalog
import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceKind
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.SerialPortEntry
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.platform.desktopPlatform
import com.smartsocks.app.platform.simulatorTransport
import com.smartsocks.app.playback.SessionsController
import com.smartsocks.app.playback.loadSession
import com.smartsocks.app.recording.FileSessionStore
import com.smartsocks.app.recording.RecordingController
import com.smartsocks.app.recording.SessionSummary
import com.smartsocks.app.ui.AppScreen
import com.smartsocks.app.ui.SettingsScreen
import com.smartsocks.app.ui.SmartSocksApp
import com.smartsocks.app.ui.SmartSocksTheme
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.ChartSettings
import com.smartsocks.core.settings.ChartWindow
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.LiveView
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedRecording
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * How the case is shown: the whole app (live, settings, sessions list, player), or the settings screen alone with
 * Advanced open. SESSIONS_EMPTY: the list without recordings.
 */
private enum class Screen { LIVE, SETTINGS, SETTINGS_EXPANDED, SESSIONS, SESSIONS_EMPTY, PLAYER }

/**
 * What the player shows: the demo session (or [sessionId]) at [atSeconds] in [view], with an A–B loop and speed if
 * given.
 */
private class PlayerSetup(
    val view: LiveView,
    val atSeconds: Double,
    val loop: Pair<Double, Double>? = null,
    val speed: Double = 1.0,
    val pendingA: Double? = null,
    val sessionId: String = DEMO_ID,
)

private class Case(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
    val theme: AppTheme,
    val screen: Screen = Screen.LIVE,
    val settings: AppSettings = AppSettings(),
    val catalog: DeviceCatalog = desktopMock,
    /** Replaces the desktop device factory for these choices (e.g. Bluetooth as on Android). */
    val bluetooth: DeviceResult? = null,
    /** The Bluetooth permission was denied for good. */
    val permissionBlocked: Boolean = false,
    /** Frames (about 16 ms each) rendered before the capture; the charts need some seconds of history. */
    val warmupFrames: Int = 150,
    /** Text scale on top of [density] (accessibility setting). */
    val fontScale: Float = 1f,
    /** Start recording after a third of the warm-up, add a marker after two thirds. */
    val record: Boolean = false,
    val player: PlayerSetup? = null,
    /** Adjusts the simulators' config per foot (e.g. a link that fails to open, to show reconnecting). */
    val simulator: ((Foot, SimConfig) -> SimConfig)? = null,
)

private class PreviewActions(blocked: Boolean) : DeviceActions {
    override val changes: StateFlow<Int> = MutableStateFlow(0)
    override val bluetoothPermissionBlocked: StateFlow<Boolean> = MutableStateFlow(blocked)
}

/** A desktop with two paired socks (Bluetooth COM ports) and a plain serial port. */
private val desktopMock = object : DeviceCatalog {
    override val kinds = listOf(DeviceKind.Simulator, DeviceKind.SerialPort)

    override fun serialPorts() = listOf(
        SerialPortEntry("COM1", "Communications Port", looksLikeSock = false),
        SerialPortEntry("COM12", "Standard Serial over Bluetooth link", looksLikeSock = true),
        SerialPortEntry("COM4", "Standard Serial over Bluetooth link", looksLikeSock = true),
    )
}

private class AndroidMock(val state: BluetoothState) : DeviceCatalog {
    override val kinds = listOf(DeviceKind.Simulator, DeviceKind.Bluetooth)

    override fun bluetoothState() = state

    override fun bluetoothDevices() = if (state != BluetoothState.Ready) {
        emptyList()
    } else {
        listOf(
            BluetoothDeviceEntry("00:16:A4:10:20:30", "Block 3", looksLikeSock = true),
            BluetoothDeviceEntry("00:16:A4:10:20:31", "Block 3", looksLikeSock = true),
            BluetoothDeviceEntry("5C:F3:70:01:02:03", "Headphones", looksLikeSock = false),
        )
    }
}

private val sockL = DeviceChoice.Bluetooth("00:16:A4:10:20:30", "Block 3")
private val tuned = ProcessorSettings(contactOnThreshold = 35.0, contactOffThreshold = 15.0, copBeta = 0.2)

private val chart200 = AppSettings(liveView = LiveView.CHART, stream = StreamSettings(SampleRate.HZ_200))
private val numbers = AppSettings(liveView = LiveView.NUMBERS)

private val loop = 10.0 to 13.6

/** The right sock never opens: it keeps reconnecting (attempt 2 waits 2 s, from about 1.6 s on). */
private val rightFails: (Foot, SimConfig) -> SimConfig =
    { foot, config -> if (foot == Foot.RIGHT) config.copy(failToConnect = true) else config }

private val cases = listOf(
    // Recording on the live screen.
    Case("live-recording-desktop-light", 1120, 760, 1f, AppTheme.LIGHT, record = true, warmupFrames = 240),
    Case(
        "live-recording-phone-dark",
        360,
        780,
        2.5f,
        AppTheme.DARK,
        settings = chart200,
        record = true,
        warmupFrames = 240,
    ),
    Case(
        "live-not-streaming-desktop-light",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        settings = AppSettings(autoConnect = false),
        warmupFrames = 20,
    ),
    // Recording while the right sock is still connecting: it is waiting to join.
    Case(
        "live-recording-waiting-desktop-light",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        record = true,
        warmupFrames = 240,
        simulator = rightFails,
    ),
    Case(
        "live-recording-waiting-phone-dark",
        360,
        780,
        2.5f,
        AppTheme.DARK,
        record = true,
        warmupFrames = 240,
        simulator = rightFails,
    ),
    // Sessions list.
    Case("sessions-desktop-light", 1120, 760, 1f, AppTheme.LIGHT, Screen.SESSIONS, warmupFrames = 20),
    Case("sessions-desktop-dark", 1120, 760, 1f, AppTheme.DARK, Screen.SESSIONS, warmupFrames = 20),
    Case("sessions-phone-light", 360, 780, 2.5f, AppTheme.LIGHT, Screen.SESSIONS, warmupFrames = 20),
    Case("sessions-empty-desktop-dark", 1120, 760, 1f, AppTheme.DARK, Screen.SESSIONS_EMPTY, warmupFrames = 20),
    Case("sessions-empty-phone-light", 360, 780, 2.5f, AppTheme.LIGHT, Screen.SESSIONS_EMPTY, warmupFrames = 20),
    // Player on the demo session (60 s, both feet, 200 Hz, two markers).
    Case(
        "player-cop-desktop-light",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.COP, 12.33, loop = loop, speed = 0.25),
    ),
    Case(
        "player-cop-desktop-dark",
        1120,
        760,
        1f,
        AppTheme.DARK,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.COP, 31.0),
    ),
    Case(
        "player-chart-desktop-light",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.CHART, 12.33, loop = loop),
    ),
    Case(
        "player-chart-desktop-dark",
        1120,
        760,
        1f,
        AppTheme.DARK,
        Screen.PLAYER,
        settings = AppSettings(chart = ChartSettings(ChartWindow.SECONDS_5, Foot.RIGHT)),
        warmupFrames = 20,
        player = PlayerSetup(LiveView.CHART, 41.0, pendingA = 40.0),
    ),
    Case(
        "player-numbers-desktop-light",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.NUMBERS, 12.33),
    ),
    Case(
        "player-desktop-min",
        900,
        600,
        1f,
        AppTheme.LIGHT,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.COP, 12.33, loop = loop),
    ),
    Case(
        "player-cop-phone-light",
        360,
        780,
        2.5f,
        AppTheme.LIGHT,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.COP, 12.33, loop = loop, speed = 0.5),
    ),
    Case(
        "player-chart-phone-dark",
        360,
        780,
        2.5f,
        AppTheme.DARK,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.CHART, 12.33, loop = loop),
    ),
    Case(
        "player-numbers-phone-dark",
        360,
        780,
        2.5f,
        AppTheme.DARK,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.NUMBERS, 12.33),
    ),
    Case(
        "player-cop-phone-landscape",
        780,
        360,
        2.5f,
        AppTheme.DARK,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.COP, 12.33, loop = loop),
    ),
    // A session whose right foot joined 6 s in: before that it shows when it joins, the chart has no right line.
    Case(
        "player-latejoin-cop-desktop-light",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.COP, 3.0, sessionId = LATE_JOIN_ID),
    ),
    Case(
        "player-latejoin-chart-desktop-dark",
        1120,
        760,
        1f,
        AppTheme.DARK,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.CHART, 8.0, sessionId = LATE_JOIN_ID),
    ),
    Case(
        "player-latejoin-numbers-phone-light",
        360,
        780,
        2.5f,
        AppTheme.LIGHT,
        Screen.PLAYER,
        warmupFrames = 20,
        player = PlayerSetup(LiveView.NUMBERS, 2.0, sessionId = LATE_JOIN_ID),
    ),
    // Chart and numbers views: both feet at 200 Hz for about 7 s so that a 5 or 10 s window is full.
    Case("chart-desktop-light", 1120, 760, 1f, AppTheme.LIGHT, settings = chart200, warmupFrames = 420),
    Case(
        "chart-desktop-dark",
        1120,
        760,
        1f,
        AppTheme.DARK,
        settings = chart200.copy(chart = ChartSettings(ChartWindow.SECONDS_5, Foot.RIGHT)),
        warmupFrames = 420,
    ),
    Case("chart-desktop-min", 900, 600, 1f, AppTheme.LIGHT, settings = chart200, warmupFrames = 420),
    Case("chart-phone", 360, 780, 2.5f, AppTheme.LIGHT, settings = chart200, warmupFrames = 420),
    // One foot without a device: its legend says so, its lines are missing.
    Case(
        "chart-phone-dark-one-foot",
        360,
        780,
        2.5f,
        AppTheme.DARK,
        settings = chart200.copy(right = DeviceChoice.None),
        warmupFrames = 420,
    ),
    Case("numbers-desktop-light", 1120, 760, 1f, AppTheme.LIGHT, settings = numbers),
    Case(
        "numbers-desktop-dark",
        1120,
        760,
        1f,
        AppTheme.DARK,
        settings = numbers.copy(right = DeviceChoice.SerialPort("COM99")),
    ),
    Case("numbers-phone", 360, 780, 2.5f, AppTheme.LIGHT, settings = numbers),
    Case("numbers-phone-full", 360, 1500, 2.5f, AppTheme.DARK, settings = numbers),
    // Too little room for the charts: a small phone in landscape with the largest font (labels larger than plots).
    Case(
        "chart-phone-landscape-font2",
        640,
        320,
        2.5f,
        AppTheme.LIGHT,
        settings = chart200,
        warmupFrames = 240,
        fontScale = 2f,
    ),
    Case(
        "chart-tiny-font2",
        280,
        420,
        2f,
        AppTheme.DARK,
        settings = chart200.copy(chart = ChartSettings(ChartWindow.SECONDS_30, Foot.RIGHT)),
        warmupFrames = 240,
        fontScale = 2f,
    ),
    Case(
        "numbers-phone-landscape-font2",
        640,
        320,
        2.5f,
        AppTheme.DARK,
        settings = numbers,
        fontScale = 2f,
    ),

    Case("desktop-light", 1120, 760, 1f, AppTheme.LIGHT),
    Case("desktop-dark", 1120, 760, 1f, AppTheme.DARK),
    Case("desktop-min", 900, 600, 1f, AppTheme.LIGHT),
    Case("phone-light", 360, 780, 2.5f, AppTheme.LIGHT),
    // Unusable devices: Bluetooth is Android-only, the COM port does not exist.
    Case(
        "desktop-errors",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        settings = AppSettings(left = sockL, right = DeviceChoice.SerialPort("COM99")),
    ),
    Case("phone-idle", 360, 780, 2.5f, AppTheme.DARK, settings = AppSettings(autoConnect = false)),
    // Reconnecting: the right foot's link does not open.
    Case("live-reconnecting-desktop-light", 1120, 760, 1f, AppTheme.LIGHT, simulator = rightFails),
    Case("live-reconnecting-desktop-dark", 1120, 760, 1f, AppTheme.DARK, settings = numbers, simulator = rightFails),
    Case("live-reconnecting-phone-dark", 360, 780, 2.5f, AppTheme.DARK, simulator = rightFails),
    Case("settings-reconnecting-desktop-light", 1120, 760, 1f, AppTheme.LIGHT, Screen.SETTINGS, simulator = rightFails),
    Case("settings-reconnecting-phone-dark", 360, 780, 2.5f, AppTheme.DARK, Screen.SETTINGS, simulator = rightFails),
    // Settings.
    Case("settings-desktop-dark", 1120, 760, 1f, AppTheme.DARK, Screen.SETTINGS),
    Case(
        "settings-desktop-light-full",
        1120,
        1500,
        1f,
        AppTheme.LIGHT,
        Screen.SETTINGS_EXPANDED,
        settings = AppSettings(right = DeviceChoice.Simulator(SimMode.Lifted, preloaded = true), processor = tuned),
    ),
    Case("settings-desktop-min", 900, 600, 1f, AppTheme.LIGHT, Screen.SETTINGS),
    // Serial ports (mocked): the left foot on COM4; the right one remembers COM7, which is gone. Not connected,
    // so that no real port of this machine is opened.
    Case(
        "settings-desktop-serial",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(
            left = DeviceChoice.SerialPort("COM4"),
            right = DeviceChoice.SerialPort("COM7"),
            autoConnect = false,
        ),
    ),
    Case(
        "settings-desktop-conflict",
        1120,
        760,
        1f,
        AppTheme.DARK,
        Screen.SETTINGS,
        settings = AppSettings(
            left = DeviceChoice.SerialPort("COM4"),
            right = DeviceChoice.SerialPort("com4"),
            autoConnect = false,
        ),
    ),
    Case("settings-phone", 360, 780, 2.5f, AppTheme.LIGHT, Screen.SETTINGS),
    Case("settings-phone-full", 360, 3000, 2.5f, AppTheme.DARK, Screen.SETTINGS_EXPANDED),
    // Android: the emulator (no adapter), a missing permission, a ready adapter with bonded socks.
    Case(
        "settings-android-bt-unsupported",
        360,
        900,
        2.5f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.Unsupported),
        bluetooth = DeviceResult.Unavailable("This device has no Bluetooth"),
    ),
    Case(
        "settings-android-bt-permission",
        360,
        900,
        2.5f,
        AppTheme.DARK,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
    ),
    Case(
        "settings-android-bt-ready",
        360,
        1100,
        2.5f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL, autoConnect = false),
        catalog = AndroidMock(BluetoothState.Ready),
        bluetooth = DeviceResult.Unavailable("Not opened in previews"),
    ),
    Case(
        "settings-android-bt-blocked",
        360,
        900,
        2.5f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
        permissionBlocked = true,
    ),
    Case(
        "live-android-bt-blocked",
        360,
        780,
        2.5f,
        AppTheme.DARK,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
        permissionBlocked = true,
    ),
    Case(
        "live-android-bt-permission",
        360,
        780,
        2.5f,
        AppTheme.LIGHT,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
    ),
)

/**
 * Renders the app off-screen with simulated feet and mocked device lists (no window, no screen capture, no real
 * port opened unless a case connects to one) and writes one PNG per case to the directory given as the first
 * argument. Development tool for checking the UI.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/screens" }).apply { mkdirs() }
    val settingsDir = Files.createTempDirectory("smartsocks-render").toFile()
    val demoDir = File(settingsDir, "sessions")
    val emptyDir = File(settingsDir, "empty")
    writeDemoSessions(demoDir)
    val only = args.drop(1).toSet()
    runBlocking {
        for (case in cases.filter { only.isEmpty() || it.name in only }) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = InMemorySettingsStore(SettingsCodec.encode(case.settings.copy(theme = case.theme)))
            val settings = SettingsRepository(store, scope)
            // Recordings of a case go to a scratch folder, so the demo list stays the same for every case.
            val sessionsDir = when {
                case.screen == Screen.SESSIONS_EMPTY -> emptyDir
                case.record -> File(settingsDir, "recorded-${case.name}")
                else -> demoDir
            }
            val platform = desktopPlatform(File(settingsDir, "unused.txt"), case.catalog, sessionsDir)
            val desktop = platform.devices
            val devices = DeviceFactory { foot: Foot, choice: DeviceChoice ->
                val sim = case.simulator
                when {
                    choice is DeviceChoice.Bluetooth && case.bluetooth != null -> case.bluetooth

                    choice is DeviceChoice.Simulator && sim != null ->
                        DeviceResult.Ready(simulatorTransport(foot, choice, renderClock) { sim(foot, it) })

                    else -> desktop.create(foot, choice)
                }
            }
            val controller = LiveController(settings, devices, scope)
            val recorder = RecordingController(controller, platform.sessionStore, settings, scope, Dispatchers.IO)
            // The player lives on this (single) thread, like the UI thread of the app.
            // A child of this (single) thread's scope, cancelled after the case.
            val uiScope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
            val sessions = SessionsController(
                platform.sessionStore,
                uiScope,
                Dispatchers.IO,
                activeRecording = recorder.activeRecording,
            )
            case.player?.let { setup ->
                val demo = platform.sessionStore.list().first { it.id == setup.sessionId }
                val player = sessions.show(loadSession(platform.sessionStore, demo))
                player.setView(setup.view)
                player.setSpeed(setup.speed)
                setup.loop?.let { (a, b) ->
                    player.seek(a * 1000)
                    player.setLoopStart()
                    player.seek(b * 1000)
                    player.setLoopEnd()
                }
                setup.pendingA?.let {
                    player.seek(it * 1000)
                    player.setLoopStart()
                }
                player.seek(setup.atSeconds * 1000)
            }
            val startScreen = when (case.screen) {
                Screen.SETTINGS -> AppScreen.SETTINGS
                Screen.SESSIONS, Screen.SESSIONS_EMPTY -> AppScreen.SESSIONS
                Screen.PLAYER -> AppScreen.PLAYER
                else -> AppScreen.LIVE
            }
            val scene = ImageComposeScene(
                width = (case.widthDp * case.density).toInt(),
                height = (case.heightDp * case.density).toInt(),
                density = Density(case.density, case.fontScale),
            ) {
                if (case.screen == Screen.SETTINGS_EXPANDED) {
                    val current by settings.settings.collectAsState()
                    SmartSocksTheme(current.theme == AppTheme.DARK) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            SettingsScreen(
                                current,
                                settings::update,
                                controller,
                                case.catalog,
                                PreviewActions(case.permissionBlocked),
                                advancedExpanded = true,
                            )
                        }
                    }
                } else {
                    SmartSocksApp(
                        settings,
                        controller,
                        case.catalog,
                        PreviewActions(case.permissionBlocked),
                        recorder = recorder,
                        sessions = sessions,
                        sessionActions = platform.sessionActions,
                        startScreen = startScreen,
                    )
                }
            }
            val start = System.nanoTime()
            // ~2.5 s of frames (by default) so that both feet stream and the COP trails fill.
            var renderNanos = 0L
            repeat(case.warmupFrames) { frame ->
                if (case.record && frame == case.warmupFrames / 3) recorder.start()
                if (case.record && frame == case.warmupFrames * 2 / 3) recorder.mark()
                val t0 = System.nanoTime()
                scene.render(t0 - start)
                renderNanos += System.nanoTime() - t0
                delay(16)
            }
            val avgMillis = "%.2f".format(renderNanos / case.warmupFrames / 1e6)
            val image = scene.render(System.nanoTime() - start)
            val file = File(out, "${case.name}.png")
            file.writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            println("wrote $file (off-screen render avg $avgMillis ms/frame)")
            scene.close()
            recorder.stopAndJoin()
            sessions.closePlayer()
            uiScope.cancel()
            controller.shutdown()
            scope.cancel()
        }
    }
    settingsDir.deleteRecursively()
}

private const val DEMO_ID = "2026-11-13_14-05-22"
private const val LATE_JOIN_ID = "2026-11-13_11-47-10"

private val renderStart = System.nanoTime()
private val renderClock: () -> Long = { System.nanoTime() - renderStart }

/**
 * A 60 s walk of both feet at 200 Hz with two markers, and three shorter recordings (one with a right foot that joined
 * late), made by the simulator.
 */
private fun writeDemoSessions(dir: File) {
    var time = OffsetDateTime.of(2026, 11, 13, 9, 30, 4, 0, ZoneOffset.ofHours(1))
    val store = FileSessionStore(dir, now = { time })
    fun demo(
        seconds: Double,
        feet: Set<Foot>,
        rate: SampleRate,
        markers: List<Pair<Double, String>>,
        raw: Boolean,
        joinAt: Map<Foot, Double> = emptyMap(),
    ) {
        val rec = store.create(saveRaw = raw)
        val result = SimulatedRecording.write(
            rec.csv,
            rec.raw,
            seconds,
            rate,
            feet = feet,
            markers = markers,
            startTimeIso = rec.startTimeIso,
            joinAtSeconds = joinAt,
        )
        val joined = SessionTimeline.read(store.csvFile(rec.id).readLines()).joins
        store.writeSummary(rec.id, SessionSummary(result.durationMillis, feet, result.rows, markers.size, 0, 0, joined))
    }
    demo(8.0, setOf(Foot.LEFT), SampleRate.HZ_100, emptyList(), raw = false)
    time = OffsetDateTime.of(2026, 11, 12, 18, 12, 40, 0, ZoneOffset.ofHours(1))
    demo(25.0, Foot.entries.toSet(), SampleRate.HZ_100, listOf(5.0 to "Mark 1"), raw = true)
    time = OffsetDateTime.of(2026, 11, 13, 11, 47, 10, 0, ZoneOffset.ofHours(1))
    demo(20.0, Foot.entries.toSet(), SampleRate.HZ_100, emptyList(), raw = false, joinAt = mapOf(Foot.RIGHT to 6.0))
    time = OffsetDateTime.of(2026, 11, 13, 14, 5, 22, 0, ZoneOffset.ofHours(1))
    demo(60.0, Foot.entries.toSet(), SampleRate.HZ_200, listOf(11.0 to "Mark 1", 38.5 to "Mark 2"), raw = true)
    // Newest first by file time: the demo session on top.
    for ((i, f) in dir.listFiles()!!.sortedBy { it.name }.withIndex()) f.setLastModified(1_000_000_000_000L + i)
    store.csvFile(DEMO_ID).setLastModified(2_000_000_000_000L)
}

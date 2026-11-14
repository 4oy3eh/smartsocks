package com.smartsocks.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.ReplayFileUsers
import com.smartsocks.app.platform.desktopPlatform
import com.smartsocks.app.playback.SessionsController
import com.smartsocks.app.recording.RecordingController
import com.smartsocks.app.recording.StopReason
import com.smartsocks.app.ui.AppScreen
import com.smartsocks.app.ui.LivePerf
import com.smartsocks.app.ui.SmartSocksApp
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.LiveView
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension
import java.lang.management.ManagementFactory
import kotlin.math.roundToInt

fun main() {
    // Development aid: every simulated link drops after this many packets (per connect), to see reconnecting.
    val simDropAfter = System.getenv("SMARTSOCKS_SIM_DROP_AFTER")?.toLongOrNull()?.takeIf { it > 0 }
    val platform = desktopPlatform(simulatorConfig = { it.copy(disconnectAfterPackets = simDropAfter) })
    // Streaming and settings I/O run off the UI thread; the UI only samples the latest state per frame.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Development aid: both feet replay this recording (its name), each its own foot; the saved settings stay as
    // they are (nothing is saved during such a run).
    val replayId = System.getenv("SMARTSOCKS_REPLAY")?.trim()?.takeIf { it.isNotEmpty() }
    val settingsStore = replayId?.let { ReplayRunSettings(platform.settingsStore, it) } ?: platform.settingsStore
    val settings = SettingsRepository(settingsStore, scope)
    val controller = LiveController(settings, platform.devices, scope)
    val recorder = RecordingController(controller, platform.sessionStore, settings, scope, Dispatchers.IO)
    // The player runs on the UI thread (it is not thread-safe); file access on IO.
    val uiThreadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val sessions = SessionsController(
        platform.sessionStore,
        uiThreadScope,
        Dispatchers.IO,
        activeRecording = recorder.activeRecording,
        fileUsers = ReplayFileUsers(controller, settings),
    )
    // Development aid: open this recording (its name) in the player and play it, e.g. to measure playback.
    val playOnStart = System.getenv("SMARTSOCKS_PLAY")
    // Development aid: record this many seconds once a foot streams, with a mark halfway.
    val recordSeconds = System.getenv("SMARTSOCKS_RECORD_SECONDS")?.toLongOrNull()
    // Development aid: open straight into the settings (for screenshots without input automation).
    val startInSettings = System.getenv("SMARTSOCKS_START_IN_SETTINGS") == "1"
    // Development aid: switch the live view ("cop", "chart", "numbers"); remembered like a choice in the UI.
    System.getenv("SMARTSOCKS_LIVE_VIEW")?.let { name ->
        LiveView.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { view ->
            settings.update { it.copy(liveView = view) }
        }
    }
    // Development aid: print the live screen's frame pacing and the chart's CPU time every few seconds.
    val perf = System.getenv("SMARTSOCKS_PERF") == "1"
    LivePerf.enabled = perf

    application {
        val state = rememberWindowState(size = DpSize(1120.dp, 760.dp))
        val uiScope = rememberCoroutineScope()
        if (perf) {
            LaunchedEffect(Unit) {
                val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
                while (true) {
                    delay(PERF_REPORT_MILLIS)
                    val load = os?.processCpuLoad ?: -1.0
                    val cpu = if (load >= 0) " | process CPU ${(load * 100).roundToInt()} %" else ""
                    LivePerf.report()?.let { println("[perf] $it$cpu") }
                }
            }
        }
        var closing by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = {
                if (!closing) {
                    // Hide at once; save and stop the socks without blocking the UI thread.
                    closing = true
                    uiScope.launch {
                        coroutineScope {
                            launch { withTimeoutOrNull(FLUSH_TIMEOUT_MILLIS) { runCatching { settings.flush() } } }
                            launch {
                                // The recording first: it needs the sessions to flush what they still deliver.
                                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) {
                                    recorder.stopAndJoin(StopReason.AppClosed)
                                }
                                withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { controller.shutdown() }
                            }
                        }
                        scope.cancel()
                        uiThreadScope.cancel()
                        exitApplication()
                    }
                }
            },
            state = state,
            visible = !closing,
            title = "SmartSocks",
        ) {
            window.minimumSize = Dimension(900, 600)
            if (recordSeconds != null) {
                LaunchedEffect(Unit) {
                    while (!recorder.start()) delay(200)
                    delay(recordSeconds * 500)
                    recorder.mark()
                    delay(recordSeconds * 500)
                    recorder.stopAndJoin()
                }
            }
            var startScreen = if (startInSettings) AppScreen.SETTINGS else AppScreen.LIVE
            if (playOnStart != null) {
                startScreen = AppScreen.SESSIONS
                LaunchedEffect(Unit) {
                    sessions.open(playOnStart) { sessions.player.value?.player?.play() }
                }
            }
            SmartSocksApp(
                settings,
                controller,
                platform.catalog,
                recorder = recorder,
                sessions = sessions,
                sessionActions = platform.sessionActions,
                startScreen = startScreen,
            )
        }
    }
}

/** [inner]'s settings with both feet replaying recording [sessionId] and connecting on start; saves nothing. */
private class ReplayRunSettings(private val inner: SettingsStore, private val sessionId: String) : SettingsStore {
    override suspend fun load(): AppSettings = inner.load().copy(
        left = DeviceChoice.Replay(sessionId, Foot.LEFT),
        right = DeviceChoice.Replay(sessionId, Foot.RIGHT),
        autoConnect = true,
    )

    override suspend fun save(settings: AppSettings) = Unit
}

private const val FLUSH_TIMEOUT_MILLIS = 1_000L
private const val PERF_REPORT_MILLIS = 5_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L

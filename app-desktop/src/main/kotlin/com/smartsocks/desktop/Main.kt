package com.smartsocks.desktop

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
import com.smartsocks.app.platform.desktopPlatform
import com.smartsocks.app.ui.SmartSocksApp
import com.smartsocks.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Dimension

fun main() {
    val platform = desktopPlatform()
    // Streaming and settings I/O run off the UI thread; the UI only samples the latest state per frame.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsRepository(platform.settingsStore, scope)
    val controller = LiveController(settings, platform.devices, scope)
    // Development aid: open straight into the settings (for screenshots without input automation).
    val startInSettings = System.getenv("SMARTSOCKS_START_IN_SETTINGS") == "1"

    application {
        val state = rememberWindowState(size = DpSize(1120.dp, 760.dp))
        val uiScope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        Window(
            onCloseRequest = {
                if (!closing) {
                    // Hide at once; save and stop the socks without blocking the UI thread.
                    closing = true
                    uiScope.launch {
                        coroutineScope {
                            launch { withTimeoutOrNull(FLUSH_TIMEOUT_MILLIS) { runCatching { settings.flush() } } }
                            launch { withTimeoutOrNull(SHUTDOWN_TIMEOUT_MILLIS) { controller.shutdown() } }
                        }
                        scope.cancel()
                        exitApplication()
                    }
                }
            },
            state = state,
            visible = !closing,
            title = "SmartSocks",
        ) {
            window.minimumSize = Dimension(900, 600)
            SmartSocksApp(settings, controller, platform.catalog, startInSettings = startInSettings)
        }
    }
}

private const val FLUSH_TIMEOUT_MILLIS = 1_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 3_000L

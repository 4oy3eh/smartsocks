package com.smartsocks.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.DeviceActions
import com.smartsocks.app.platform.DeviceCatalog
import com.smartsocks.app.platform.SessionActions
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.playback.SessionsController
import com.smartsocks.app.recording.RecordingController
import com.smartsocks.app.recording.RecordingState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.SettingsRepository

/** The app's screens; the player shows the session open in [SessionsController.player]. */
enum class AppScreen { LIVE, SETTINGS, SESSIONS, PLAYER }

/** A full screen that Escape closes (desktop, hardware keyboards). */
@Composable
private fun EscapeCloses(onClose: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        modifier.focusRequester(focus).focusable().onPreviewKeyEvent {
            val escape = it.key == Key.Escape && it.type == KeyEventType.KeyUp
            if (escape) onClose()
            escape
        },
        content = content,
    )
}

/** Below this width the top bar puts the foot chips on their own row and the layout tightens. */
private val CompactWidth = 600.dp

/**
 * The whole app: live screen with a top bar, settings behind the gear ([startInSettings] for previews). [catalog]
 * lists the devices the settings offer; [actions] are the system dialogs (Android) behind the Bluetooth buttons.
 */
@Composable
fun SmartSocksApp(
    settings: SettingsRepository,
    controller: LiveController,
    catalog: DeviceCatalog = DeviceCatalog.SimulatorOnly,
    actions: DeviceActions = DeviceActions.None,
    startInSettings: Boolean = false,
    recorder: RecordingController? = null,
    sessions: SessionsController? = null,
    sessionActions: SessionActions = SessionActions.None,
    startScreen: AppScreen = if (startInSettings) AppScreen.SETTINGS else AppScreen.LIVE,
) {
    val current by settings.settings.collectAsState()
    val left by controller.left.status.collectAsState()
    val right by controller.right.status.collectAsState()
    var screen by rememberSaveable { mutableStateOf(startScreen) }
    val player = sessions?.player?.collectAsState()?.value
    // The player is gone (deleted, or not restored after the process died): back to the list.
    if (screen == AppScreen.PLAYER && player == null) screen = AppScreen.SESSIONS
    // A session opened from elsewhere (development start option) while the list shows: play it.
    LaunchedEffect(player) { if (player != null && screen == AppScreen.SESSIONS) screen = AppScreen.PLAYER }
    val dark = isDarkTheme(current.theme)
    val streaming = left.state == LinkState.Streaming || right.state == LinkState.Streaming
    val playing = player?.state?.collectAsState()?.value?.playing == true

    PlatformWindowEffects(darkTheme = dark, keepScreenOn = streaming || playing)
    PlatformBackHandler(enabled = screen != AppScreen.LIVE) {
        screen = if (screen == AppScreen.PLAYER) AppScreen.SESSIONS else AppScreen.LIVE
    }
    // Leaving the player pauses it.
    val inPlayer = screen == AppScreen.PLAYER
    LaunchedEffect(inPlayer) { if (!inPlayer) player?.player?.pause() }

    SmartSocksTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val compact = maxWidth < CompactWidth
                Column(Modifier.fillMaxSize()) {
                    if (screen == AppScreen.PLAYER && player != null) {
                        PlayerTopBar(player, onBack = { screen = AppScreen.SESSIONS })
                        PlayerScreen(
                            player,
                            chart = current.chart,
                            onChartChange = { chart -> settings.update { it.copy(chart = chart) } },
                            compact = compact,
                            onBack = { screen = AppScreen.SESSIONS },
                            modifier = Modifier.weight(1f),
                        )
                    } else if (screen == AppScreen.SESSIONS && sessions != null) {
                        EscapeCloses(onClose = { screen = AppScreen.LIVE }, modifier = Modifier.weight(1f)) {
                            SessionsTopBar(onBack = { screen = AppScreen.LIVE }, sessionActions)
                            val finished = recorder?.finished?.collectAsState()?.value
                            LaunchedEffect(finished) { sessions.refresh() }
                            SessionsScreen(
                                sessions,
                                sessionActions,
                                compact,
                                onOpened = { screen = AppScreen.PLAYER },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else if (screen == AppScreen.SETTINGS) {
                        EscapeCloses(onClose = { screen = AppScreen.LIVE }, modifier = Modifier.weight(1f)) {
                            SettingsTopBar(onBack = { screen = AppScreen.LIVE })
                            val recordingState = recorder?.state?.collectAsState()?.value
                            SettingsScreen(
                                current,
                                onChange = settings::update,
                                controller = controller,
                                catalog = catalog,
                                actions = actions,
                                modifier = Modifier.weight(1f),
                                recording = recordingState != null && recordingState != RecordingState.Idle,
                            )
                        }
                    } else {
                        LiveTopBar(
                            left = left,
                            right = right,
                            compact = compact,
                            onToggle = { status ->
                                val state = status.state
                                when {
                                    state is LinkState.Unavailable &&
                                        state.reason == UnavailableReason.PermissionNeeded ->
                                        actions.grantBluetoothPermission()

                                    state is LinkState.Unavailable && state.reason == UnavailableReason.BluetoothOff ->
                                        actions.enableBluetooth()

                                    else -> controller.link(status.foot).toggle()
                                }
                            },
                            onSettings = { screen = AppScreen.SETTINGS },
                            onSessions = if (sessions != null) {
                                { screen = AppScreen.SESSIONS }
                            } else {
                                null
                            },
                        )
                        DeviceNotice(left, right, actions, compact, onDismiss = { foot ->
                            controller.link(foot).disconnect()
                        })
                        LiveScreen(
                            controller = controller,
                            view = current.liveView,
                            onViewChange = { view -> settings.update { it.copy(liveView = view) } },
                            chart = current.chart,
                            onChartChange = { chart -> settings.update { it.copy(chart = chart) } },
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            recorder = recorder,
                            onOpenRecording = { id -> sessions?.open(id) { screen = AppScreen.PLAYER } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTopBar(
    left: FootStatus,
    right: FootStatus,
    compact: Boolean,
    onToggle: (FootStatus) -> Unit,
    onSettings: () -> Unit,
    onSessions: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(start = if (compact) 16.dp else 24.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Brand()
            Spacer(Modifier.weight(1f))
            if (!compact) {
                FootChip(left, compact = false, onClick = { onToggle(left) })
                Spacer(Modifier.width(8.dp))
                FootChip(right, compact = false, onClick = { onToggle(right) })
                Spacer(Modifier.width(8.dp))
            }
            if (onSessions != null) {
                IconButton(onClick = onSessions) {
                    Icon(AppIcons.Sessions, contentDescription = "Sessions")
                }
            }
            IconButton(onClick = onSettings) {
                Icon(AppIcons.Settings, contentDescription = "Settings")
            }
        }
        if (compact) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FootChip(left, compact = true, onClick = { onToggle(left) }, modifier = Modifier.weight(1f))
                FootChip(right, compact = true, onClick = { onToggle(right) }, modifier = Modifier.weight(1f))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * Shown on the live screen while a foot waits to connect to a sock that needs something the user can grant (the
 * Bluetooth permission) or switch on, instead of a bare "unavailable". "Not now" ([onDismiss]) drops those feet's
 * pending connects, which hides the notice; their chips still offer the fix.
 */
@Composable
private fun DeviceNotice(
    left: FootStatus,
    right: FootStatus,
    actions: DeviceActions,
    compact: Boolean,
    onDismiss: (Foot) -> Unit,
) {
    val waiting = listOf(left, right).filter { it.pendingConnect && it.state.isFixable }
    val reasons = waiting.mapNotNull { (it.state as? LinkState.Unavailable)?.reason }
    val blocked by actions.bluetoothPermissionBlocked.collectAsState()
    val (text, button, action) = when {
        UnavailableReason.PermissionNeeded in reasons -> Triple(
            if (blocked) {
                "Permission needed: allow \"Nearby devices\" for SmartSocks in the app's settings to connect."
            } else {
                "Permission needed: SmartSocks needs \"Nearby devices\" to connect to the socks."
            },
            if (blocked) "Open settings" else "Grant",
            actions::grantBluetoothPermission,
        )

        UnavailableReason.BluetoothOff in reasons -> Triple(
            "Bluetooth is off: switch it on to connect to the socks.",
            "Turn on",
            actions::enableBluetooth,
        )

        else -> return
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = if (compact) 16.dp else 24.dp).padding(top = 12.dp),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalButton(onClick = action) { Text(button) }
                TextButton(onClick = { waiting.forEach { onDismiss(it.foot) } }) { Text("Not now") }
            }
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Smart", style = MaterialTheme.typography.titleLarge)
        Text("Socks", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.Back, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

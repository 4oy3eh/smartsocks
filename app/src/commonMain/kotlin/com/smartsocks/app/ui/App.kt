package com.smartsocks.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.SettingsRepository

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
) {
    val current by settings.settings.collectAsState()
    val left by controller.left.status.collectAsState()
    val right by controller.right.status.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(startInSettings) }
    val dark = isDarkTheme(current.theme)
    val streaming = left.state == LinkState.Streaming || right.state == LinkState.Streaming

    PlatformWindowEffects(darkTheme = dark, keepScreenOn = streaming)
    PlatformBackHandler(enabled = showSettings) { showSettings = false }

    SmartSocksTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                val compact = maxWidth < CompactWidth
                Column(Modifier.fillMaxSize()) {
                    if (showSettings) {
                        // Escape closes the settings (desktop, hardware keyboards).
                        val focus = remember { FocusRequester() }
                        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                        Column(
                            Modifier.weight(1f).focusRequester(focus).focusable().onPreviewKeyEvent {
                                val escape = it.key == Key.Escape && it.type == KeyEventType.KeyUp
                                if (escape) showSettings = false
                                escape
                            },
                        ) {
                            SettingsTopBar(onBack = { showSettings = false })
                            SettingsScreen(
                                current,
                                onChange = settings::update,
                                controller = controller,
                                catalog = catalog,
                                actions = actions,
                                modifier = Modifier.weight(1f),
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
                            onSettings = { showSettings = true },
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

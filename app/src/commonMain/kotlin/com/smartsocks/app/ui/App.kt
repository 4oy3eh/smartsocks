package com.smartsocks.app.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.core.settings.SettingsRepository

/** Below this width the top bar puts the foot chips on their own row and the layout tightens. */
private val CompactWidth = 600.dp

/** The whole app: live screen with a top bar, settings behind the gear ([startInSettings] for previews). */
@Composable
fun SmartSocksApp(settings: SettingsRepository, controller: LiveController, startInSettings: Boolean = false) {
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
                        SettingsTopBar(onBack = { showSettings = false })
                        SettingsScreen(current, onChange = settings::update, modifier = Modifier.weight(1f))
                    } else {
                        LiveTopBar(
                            left = left,
                            right = right,
                            compact = compact,
                            onToggle = { controller.link(it.foot).toggle() },
                            onSettings = { showSettings = true },
                        )
                        LiveScreen(
                            controller = controller,
                            view = current.liveView,
                            onViewChange = { view -> settings.update { it.copy(liveView = view) } },
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

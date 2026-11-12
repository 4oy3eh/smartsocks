package com.smartsocks.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.sim.SimMode

/** Theme, per-foot simulator mode and auto-connect. Every change is saved by the settings repository. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
            Section("Appearance") {
                Label("Theme")
                Choice(
                    options = AppTheme.entries,
                    selected = settings.theme,
                    title = { it.title },
                    onSelect = { theme -> onChange { it.copy(theme = theme) } },
                )
            }
            Section("Simulator") {
                for (foot in Foot.entries) {
                    val device = settings.device(foot)
                    val sim = device as? DeviceChoice.Simulator
                    Label(if (foot == Foot.LEFT) "Left foot" else "Right foot")
                    Choice(
                        options = SimMode.entries,
                        selected = sim?.mode,
                        title = { it.name },
                        onSelect = { mode ->
                            onChange { it.withDevice(foot, DeviceChoice.Simulator(mode, sim?.preloaded ?: false)) }
                        },
                    )
                    if (sim == null) {
                        Text(
                            "Uses ${device.title}. Pick a mode to switch this foot to the simulator.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (foot == Foot.LEFT) Spacer(Modifier.height(12.dp))
                }
            }
            Section("Connection") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 16.dp)) {
                        Text("Connect on start", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Connect both feet to the remembered devices when the app opens.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = settings.autoConnect,
                        onCheckedChange = { on -> onChange { it.copy(autoConnect = on) } },
                    )
                }
            }
        }
    }
}

private val AppTheme.title: String
    get() = when (this) {
        AppTheme.SYSTEM -> "System"
        AppTheme.LIGHT -> "Light"
        AppTheme.DARK -> "Dark"
    }

private val DeviceChoice.title: String
    get() = when (this) {
        DeviceChoice.None -> "no device"
        is DeviceChoice.Simulator -> "the simulator"
        is DeviceChoice.SerialPort -> "serial port $portName"
        is DeviceChoice.Bluetooth -> "Bluetooth device ${name.ifBlank { address }}"
    }

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 8.dp),
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun <T> Choice(options: List<T>, selected: T?, title: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Text(title(option))
            }
        }
    }
}

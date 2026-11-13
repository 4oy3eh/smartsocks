package com.smartsocks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.BluetoothDeviceEntry
import com.smartsocks.app.platform.BluetoothState
import com.smartsocks.app.platform.DeviceActions
import com.smartsocks.app.platform.DeviceCatalog
import com.smartsocks.app.platform.DeviceKind
import com.smartsocks.app.platform.SerialPortEntry
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.platform.kind
import com.smartsocks.app.settings.DeviceOption
import com.smartsocks.app.settings.DeviceOptions
import com.smartsocks.app.settings.deviceInUseByOtherFoot
import com.smartsocks.app.settings.other
import com.smartsocks.app.settings.title
import com.smartsocks.app.settings.withDeviceIfFree
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.sim.SimMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the platform lists right now, loaded off the UI thread; shared by both feet's cards. */
@Stable
internal class DeviceLists {
    /** Null until the first load. */
    var ports by mutableStateOf<List<SerialPortEntry>?>(null)
    var bluetoothState by mutableStateOf<BluetoothState?>(null)
    var bluetoothDevices by mutableStateOf<List<BluetoothDeviceEntry>>(emptyList())
    var loading by mutableStateOf(false)
    var reloads by mutableIntStateOf(0)

    fun refresh() {
        reloads++
    }
}

/** Loaded on first use, on [DeviceLists.refresh] and whenever [actions] report a change (permission, adapter). */
@Composable
internal fun rememberDeviceLists(catalog: DeviceCatalog, actions: DeviceActions): DeviceLists {
    val lists = remember(catalog) { DeviceLists() }
    val changes by actions.changes.collectAsState()
    LaunchedEffect(catalog, lists.reloads, changes) {
        lists.loading = true
        val kinds = catalog.kinds
        val ports = withContext(Dispatchers.Default) {
            if (DeviceKind.SerialPort in kinds) catalog.serialPorts() else emptyList()
        }
        val bluetooth = withContext(Dispatchers.Default) {
            val state = if (DeviceKind.Bluetooth in kinds) catalog.bluetoothState() else BluetoothState.Unsupported
            state to if (state == BluetoothState.Ready) catalog.bluetoothDevices() else emptyList()
        }
        lists.ports = ports
        lists.bluetoothState = bluetooth.first
        lists.bluetoothDevices = bluetooth.second
        lists.loading = false
    }
    return lists
}

internal val DeviceKind?.title: String
    get() = when (this) {
        null -> "None"
        DeviceKind.Simulator -> "Simulator"
        DeviceKind.SerialPort -> "Serial port"
        DeviceKind.Bluetooth -> "Bluetooth"
    }

/** One foot's device: the kind, its options and the live connection with Connect/Disconnect. */
@Composable
internal fun FootDeviceCard(
    foot: Foot,
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    controller: LiveController,
    catalog: DeviceCatalog,
    lists: DeviceLists,
    actions: DeviceActions,
    modifier: Modifier = Modifier,
) {
    val link = controller.link(foot)
    val status by link.status.collectAsState()
    val current = settings.device(foot)
    // A kind picked without a device yet (no free port to suggest); forgotten once the device changes.
    var pendingKind by remember(current) { mutableStateOf<DeviceKind?>(null) }
    val shownKind = pendingKind ?: current.kind
    val kinds = buildList {
        add(null)
        addAll(catalog.kinds)
        current.kind?.let { if (it !in catalog.kinds) add(it) }
    }

    SettingsCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(foot.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            LinkButton(status, onToggle = { link.toggle() }, actions = actions)
        }
        LinkStatusLine(status, Modifier.padding(top = 2.dp, bottom = if (status.pendingConnect) 0.dp else 14.dp))
        if (status.pendingConnect) {
            // The connect waits for the fix; let the user call it off.
            TextButton(onClick = link::disconnect, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("Don't connect")
            }
        }

        FieldLabel("Device")
        SegmentedChoice(
            options = kinds,
            selected = shownKind,
            title = { it.title },
            onSelect = { kind ->
                pendingKind = null
                when (kind) {
                    null -> onChange { it.withDevice(foot, DeviceChoice.None) }

                    DeviceKind.Simulator ->
                        if (current !is DeviceChoice.Simulator) {
                            onChange {
                                it.withDevice(foot, DeviceChoice.Simulator())
                            }
                        }

                    DeviceKind.SerialPort, DeviceKind.Bluetooth -> if (current.kind != kind) {
                        val options = if (kind == DeviceKind.SerialPort) {
                            DeviceOptions.serialPorts(lists.ports.orEmpty(), foot, settings)
                        } else {
                            DeviceOptions.bluetooth(lists.bluetoothDevices, foot, settings)
                        }
                        val suggestion = DeviceOptions.suggestion(options)
                        if (suggestion != null) {
                            onChange { it.withDeviceIfFree(foot, suggestion.choice) }
                        } else {
                            pendingKind = kind
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        val draft = pendingKind
        if (draft != null) {
            // Picked a kind but no device of it yet: the foot keeps its current device meanwhile.
            val what = if (draft == DeviceKind.SerialPort) "a port" else "a sock"
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                Hint(
                    "Still using ${current.shortTitle} until you pick $what below.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        when (shownKind) {
            null -> Hint("This foot is not used. Pick the simulator or a sock to see it on the live screen.")

            DeviceKind.Simulator -> SimulatorOptions(foot, current as? DeviceChoice.Simulator, onChange)

            DeviceKind.SerialPort -> {
                if (DeviceKind.SerialPort in catalog.kinds) {
                    SerialPortOptions(foot, settings, lists, onChange)
                } else {
                    Hint("Serial ports are only available in the desktop app.")
                }
            }

            DeviceKind.Bluetooth -> {
                if (DeviceKind.Bluetooth in catalog.kinds) {
                    BluetoothOptions(foot, settings, lists, actions, onChange)
                } else {
                    Hint(
                        "Bluetooth devices are only available in the Android app. On desktop, use the sock's COM port.",
                    )
                }
            }
        }

        if (foot == Foot.RIGHT && settings.deviceInUseByOtherFoot(foot)) {
            Hint(
                "The left foot already uses this device. Pick another one for the right foot.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** The device in a sentence: "the simulator", "COM5", "Block 3". */
private val DeviceChoice.shortTitle: String
    get() = when (this) {
        DeviceChoice.None -> "no device"
        is DeviceChoice.Simulator -> "the simulator"
        is DeviceChoice.SerialPort -> portName
        is DeviceChoice.Bluetooth -> name.ifBlank { address }
    }

/** Connect / Disconnect, or the fix for a device that is missing something. */
@Composable
private fun LinkButton(status: FootStatus, onToggle: () -> Unit, actions: DeviceActions) {
    val state = status.state
    when {
        state.isActive -> OutlinedButton(onClick = onToggle) { Text("Disconnect") }

        state is LinkState.Unavailable && state.reason == UnavailableReason.PermissionNeeded -> {
            val blocked by actions.bluetoothPermissionBlocked.collectAsState()
            FilledTonalButton(onClick = actions::grantBluetoothPermission) {
                Text(if (blocked) "Open settings" else "Grant")
            }
        }

        state is LinkState.Unavailable && state.reason == UnavailableReason.BluetoothOff ->
            FilledTonalButton(onClick = actions::enableBluetooth) { Text("Turn on") }

        else -> FilledTonalButton(onClick = onToggle, enabled = state.canConnect) { Text("Connect") }
    }
}

@Composable
internal fun LinkStatusLine(status: FootStatus, modifier: Modifier = Modifier) {
    val colors = LocalSockColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(colors.forState(status.state), CircleShape))
        Spacer(Modifier.size(8.dp))
        val name = status.deviceName?.takeIf { status.state !is LinkState.Unavailable }
        val pending = "connects once fixed".takeIf { status.pendingConnect }
        Text(
            listOfNotNull(name, status.statusText(), pending).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val SimMode.title: String
    get() = when (this) {
        SimMode.Walking -> "Walking"
        SimMode.Standing -> "Standing"
        SimMode.Lifted -> "Lifted"
    }

@Composable
private fun SimulatorOptions(
    foot: Foot,
    sim: DeviceChoice.Simulator?,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val current = sim ?: DeviceChoice.Simulator()
    FieldLabel("Mode")
    SegmentedChoice(
        options = SimMode.entries,
        selected = current.mode,
        title = { it.title },
        onSelect = { mode -> onChange { it.withDevice(foot, current.copy(mode = mode)) } },
    )
    Hint(
        when (current.mode) {
            SimMode.Walking -> "A steady walk: heel strike to toe-off, feet half a stride apart."
            SimMode.Standing -> "Quiet standing with slow sway."
            SimMode.Lifted -> "The foot in the air, as for a calibration."
        },
        modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
    )
    SwitchRow(
        title = "Preloaded insole",
        description = "An insole that reads some load even when lifted. Calibrate to remove it.",
        checked = current.preloaded,
        onCheckedChange = { on -> onChange { it.withDevice(foot, current.copy(preloaded = on)) } },
    )
}

@Composable
private fun ListHeader(title: String, lists: DeviceLists) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        FieldLabel(title, Modifier.weight(1f).padding(top = 8.dp))
        TextButton(onClick = lists::refresh, enabled = !lists.loading) {
            Text(if (lists.loading) "Refreshing…" else "Refresh")
        }
    }
}

@Composable
private fun SerialPortOptions(
    foot: Foot,
    settings: AppSettings,
    lists: DeviceLists,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    ListHeader("Port", lists)
    val ports = lists.ports
    if (ports == null) {
        Hint("Looking for serial ports…")
    } else {
        val options = DeviceOptions.serialPorts(ports, foot, settings)
        if (options.isEmpty()) Hint("No serial ports found.")
        DeviceOptionList(options, foot, tag = "Bluetooth") { choice ->
            onChange { it.withDeviceIfFree(foot, choice) }
        }
    }
    Hint(
        "Pair the sock in the Windows Bluetooth settings first: it then appears here as a COM port " +
            "(\"Standard Serial over Bluetooth link\").",
        modifier = Modifier.padding(top = 10.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BluetoothOptions(
    foot: Foot,
    settings: AppSettings,
    lists: DeviceLists,
    actions: DeviceActions,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val selected = settings.device(foot) as? DeviceChoice.Bluetooth
    when (lists.bluetoothState) {
        null -> Hint("Checking Bluetooth…")

        BluetoothState.Unsupported -> Hint(
            "This device has no Bluetooth (the Android emulator, for one). Use the simulator here.",
        )

        BluetoothState.PermissionMissing -> {
            val blocked by actions.bluetoothPermissionBlocked.collectAsState()
            Hint("SmartSocks needs the \"Nearby devices\" permission to list and connect to your paired socks.")
            if (blocked) {
                Hint(
                    "The permission was denied: allow it under Permissions in the app's settings.",
                    modifier = Modifier.padding(top = 6.dp),
                )
                Button(onClick = actions::openAppSettings, modifier = Modifier.padding(top = 10.dp)) {
                    Text("Open app settings")
                }
            } else {
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = actions::requestBluetoothPermission) { Text("Grant permission") }
                    TextButton(onClick = actions::openAppSettings) { Text("App settings") }
                }
            }
        }

        BluetoothState.Disabled -> {
            Hint("Bluetooth is off.")
            Button(onClick = actions::enableBluetooth, modifier = Modifier.padding(top = 10.dp)) {
                Text("Turn on Bluetooth")
            }
        }

        BluetoothState.Ready -> {
            ListHeader("Paired devices", lists)
            val options = DeviceOptions.bluetooth(lists.bluetoothDevices, foot, settings)
            if (options.isEmpty()) Hint("No paired devices yet.")
            DeviceOptionList(options, foot, tag = "Sock") { choice -> onChange { it.withDeviceIfFree(foot, choice) } }
            Hint(
                "To add a sock, pair it in the system Bluetooth settings, then come back here.",
                modifier = Modifier.padding(top = 10.dp),
            )
            OutlinedButton(onClick = actions::openBluetoothSettings, modifier = Modifier.padding(top = 8.dp)) {
                Text("Open Bluetooth settings")
            }
        }
    }
    if (selected != null && lists.bluetoothState != BluetoothState.Ready) {
        Hint("Selected: ${selected.name.ifBlank { selected.address }}", modifier = Modifier.padding(top = 10.dp))
    }
}

/** Radio rows; [tag] marks the likely socks. */
@Composable
private fun DeviceOptionList(options: List<DeviceOption>, foot: Foot, tag: String, onPick: (DeviceChoice) -> Unit) {
    if (options.isEmpty()) return
    Column(Modifier.clip(RoundedCornerShape(12.dp))) {
        options.forEachIndexed { i, option ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DeviceOptionRow(option, foot, tag, onPick)
        }
    }
}

@Composable
private fun DeviceOptionRow(option: DeviceOption, foot: Foot, tag: String, onPick: (DeviceChoice) -> Unit) {
    val enabled = option.selectable
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !option.selected) { onPick(option.choice) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = option.selected, onClick = null, enabled = enabled, modifier = Modifier.padding(8.dp))
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface else muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (option.looksLikeSock) {
                    Tag(
                        tag,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            val detail = when {
                option.usedByOtherFoot -> "Used by the ${foot.other.title.lowercase()}"
                !option.present -> listOf(option.detail, "not found").filter(String::isNotBlank).joinToString(" · ")
                else -> option.detail
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!option.present && !option.usedByOtherFoot) MaterialTheme.colorScheme.error else muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

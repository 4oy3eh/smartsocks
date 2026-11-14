package com.smartsocks.app.platform

import com.smartsocks.app.recording.SessionNames
import com.smartsocks.app.recording.SessionStore
import com.smartsocks.core.model.Foot
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.DeviceChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The kinds of device a foot can use, apart from none. */
enum class DeviceKind { Simulator, SerialPort, Bluetooth, Replay }

val DeviceChoice.kind: DeviceKind?
    get() = when (this) {
        DeviceChoice.None -> null
        is DeviceChoice.Simulator -> DeviceKind.Simulator
        is DeviceChoice.SerialPort -> DeviceKind.SerialPort
        is DeviceChoice.Bluetooth -> DeviceKind.Bluetooth
        is DeviceChoice.Replay -> DeviceKind.Replay
    }

/** A serial port present now. [looksLikeSock]: a Bluetooth serial link, which is how a paired sock shows up. */
data class SerialPortEntry(val name: String, val description: String, val looksLikeSock: Boolean)

/** A bonded Bluetooth device; [name] is null if the system does not know it. */
data class BluetoothDeviceEntry(val address: String, val name: String?, val looksLikeSock: Boolean)

/**
 * A recording that can be replayed as a sock: it has a raw packet dump. [feet]: the feet with data (see
 * [com.smartsocks.app.recording.StoredSession.feet]); [title] is its display name; [stream]: what it was recorded
 * at (and is replayed at), null if its header does not say. [feetKnown]: [feet] come from the summary; without one
 * (a recording cut short by a crash) they are the feet of the start, and another may have joined.
 */
data class ReplaySessionEntry(
    val id: String,
    val title: String,
    val feet: Set<Foot>,
    val durationMillis: Double? = null,
    val stream: StreamSettings? = null,
    val feetKnown: Boolean = true,
)

/** Whether Bluetooth devices can be listed and connected to, and if not, why. */
enum class BluetoothState {
    /** No Bluetooth adapter (the Android emulator), or not a Bluetooth platform at all. */
    Unsupported,
    PermissionMissing,
    Disabled,
    Ready,
}

/**
 * What devices this platform offers for the settings' device pickers. The calls may block briefly (port
 * enumeration): call them off the UI thread. They never throw.
 */
interface DeviceCatalog {
    /** In display order; [DeviceKind]s not listed here are not offered. */
    val kinds: List<DeviceKind>

    fun serialPorts(): List<SerialPortEntry> = emptyList()

    fun bluetoothState(): BluetoothState = BluetoothState.Unsupported

    /** Bonded devices, likely socks first; empty unless [bluetoothState] is [BluetoothState.Ready]. */
    fun bluetoothDevices(): List<BluetoothDeviceEntry> = emptyList()

    /** Recordings that can be replayed ([DeviceKind.Replay]), newest first. */
    fun replaySessions(): List<ReplaySessionEntry> = emptyList()

    companion object {
        /** The simulator only (previews, tests). */
        val SimulatorOnly: DeviceCatalog = object : DeviceCatalog {
            override val kinds = listOf(DeviceKind.Simulator)
        }
    }
}

/**
 * This catalog plus [DeviceKind.Replay] (after the other kinds): the recordings in [store] that have a raw packet
 * dump and data. Listing reads headers and summaries only (blocking I/O, as every catalog call).
 */
fun DeviceCatalog.withReplay(store: SessionStore): DeviceCatalog {
    val inner = this
    return object : DeviceCatalog by inner {
        override val kinds: List<DeviceKind> = inner.kinds.filter { it != DeviceKind.Replay } + DeviceKind.Replay

        override fun replaySessions(): List<ReplaySessionEntry> = try {
            store.list().filter { it.hasRaw && it.feet.isNotEmpty() }.map {
                ReplaySessionEntry(
                    it.id,
                    SessionNames.display(it.id),
                    it.feet,
                    it.durationMillis,
                    it.header.stream,
                    feetKnown = it.summary != null,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * System dialogs the UI can ask for, provided by the Android activity; no-ops elsewhere. Results arrive
 * asynchronously: [changes] is bumped whenever what the [DeviceCatalog] reports may have changed (a permission
 * granted, Bluetooth switched on, back from the system settings), so lists should be reloaded on it.
 */
interface DeviceActions {
    val changes: StateFlow<Int>

    /**
     * The user denied the Bluetooth permission for good ("Don't allow" twice): the system no longer shows the
     * request, only the app's system settings can grant it.
     */
    val bluetoothPermissionBlocked: StateFlow<Boolean> get() = NotBlocked

    /** The runtime Bluetooth permission ("Nearby devices"). */
    fun requestBluetoothPermission() = Unit

    /** What a "Grant" button does: the request, or the app's settings once the request is blocked. */
    fun grantBluetoothPermission() {
        if (bluetoothPermissionBlocked.value) openAppSettings() else requestBluetoothPermission()
    }

    /** The system dialog that switches Bluetooth on. */
    fun enableBluetooth() = Unit

    /** The system Bluetooth settings, to pair a new sock. */
    fun openBluetoothSettings() = Unit

    /** This app's page in the system settings: the way back once a permission was denied for good. */
    fun openAppSettings() = Unit

    companion object {
        internal val NotBlocked: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

        val None: DeviceActions = object : DeviceActions {
            override val changes: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
        }
    }
}

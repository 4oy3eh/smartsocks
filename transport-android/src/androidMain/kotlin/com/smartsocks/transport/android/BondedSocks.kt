package com.smartsocks.transport.android

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context

/** A bonded (paired) Bluetooth device, for a device picker. [name] is null if the system does not know it. */
data class BluetoothDeviceInfo(val address: String, val name: String?, val looksLikeSock: Boolean)

/** Why [BondedSocks.list] may come back empty; lets the UI tell the user what to fix. */
enum class BluetoothAvailability {
    /** The device has no Bluetooth adapter (e.g. the Android emulator). */
    Unsupported,

    /** [BluetoothPermissions.requiredPermissions] are not all granted. */
    PermissionMissing,

    /** Bluetooth is switched off. */
    Disabled,

    Ready,
}

object BondedSocks {
    fun availability(context: Context): BluetoothAvailability {
        val adapter = context.bluetoothAdapter() ?: return BluetoothAvailability.Unsupported
        if (!BluetoothPermissions.hasPermissions(context)) return BluetoothAvailability.PermissionMissing
        return if (adapter.isEnabled) BluetoothAvailability.Ready else BluetoothAvailability.Disabled
    }

    /**
     * Bonded Classic-Bluetooth devices, likely socks first, then by name. Devices are never scanned for: a sock has
     * to be paired in the system settings first.
     *
     * Returns an empty list if there is no adapter or Bluetooth is off (see [availability]). Throws
     * [SecurityException] if [BluetoothPermissions] are missing, since that needs the app to ask for them.
     */
    // Permissions are checked explicitly; lint cannot see that.
    @SuppressLint("MissingPermission")
    fun list(context: Context): List<BluetoothDeviceInfo> {
        val adapter = context.bluetoothAdapter() ?: return emptyList()
        BluetoothPermissions.requirePermissions(context)
        if (!adapter.isEnabled) return emptyList()
        val devices = adapter.bondedDevices.orEmpty()
            // RFCOMM needs Classic Bluetooth; LE-only devices (watches, tags) cannot be socks.
            .filter { it.type != BluetoothDevice.DEVICE_TYPE_LE }
            .map { BluetoothDeviceInfo(it.address, it.name, looksLikeSock(it.name, it.address)) }
        return sortForPicker(devices)
    }

    /**
     * Name/address heuristic. The only known sock was named "Block 3" with a module from OUI 00:16:A4 (see
     * docs/legacy-analysis.md); "sock" and "insole" cover future firmware names.
     */
    internal fun looksLikeSock(name: String?, address: String): Boolean {
        val n = name.orEmpty()
        return SOCK_NAME_HINTS.any { n.contains(it, ignoreCase = true) } ||
            address.startsWith(SOCK_MODULE_OUI, ignoreCase = true)
    }

    internal fun sortForPicker(devices: List<BluetoothDeviceInfo>): List<BluetoothDeviceInfo> = devices.sortedWith(
        compareByDescending<BluetoothDeviceInfo> { it.looksLikeSock }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: it.address },
    )

    private val SOCK_NAME_HINTS = listOf("block", "sock", "insole")
    private const val SOCK_MODULE_OUI = "00:16:A4"
}

package com.smartsocks.app.platform

import android.content.Context
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.transport.android.BluetoothAvailability
import com.smartsocks.transport.android.BondedSocks
import com.smartsocks.transport.android.RfcommSockTransport
import java.io.File

/** Android: settings in the app's files directory; socks over Bluetooth RFCOMM. */
fun androidPlatform(context: Context): Platform {
    val app = context.applicationContext
    return Platform(
        settingsStore = FileSettingsStore(File(app.filesDir, "settings.txt")),
        devices = DeviceFactory.withSimulator(
            check = { choice ->
                if (choice is DeviceChoice.Bluetooth) bluetoothProblem(BondedSocks.availability(app)) else null
            },
        ) { _, choice ->
            when (choice) {
                is DeviceChoice.Bluetooth ->
                    DeviceResult.Ready(RfcommSockTransport(app, choice.address, choice.name.ifBlank { choice.address }))

                else -> unsupportedOnThisPlatform(choice, "Android")
            }
        },
        catalog = AndroidDeviceCatalog(app),
    )
}

/** Why a Bluetooth sock cannot be connected in [availability], or null if it can. */
internal fun bluetoothProblem(availability: BluetoothAvailability): DeviceResult.Unavailable? = when (availability) {
    BluetoothAvailability.Unsupported -> DeviceResult.Unavailable("This device has no Bluetooth")

    BluetoothAvailability.PermissionMissing ->
        DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded)

    BluetoothAvailability.Disabled -> DeviceResult.Unavailable("Bluetooth is off", UnavailableReason.BluetoothOff)

    BluetoothAvailability.Ready -> null
}

internal fun BluetoothAvailability.toBluetoothState(): BluetoothState = when (this) {
    BluetoothAvailability.Unsupported -> BluetoothState.Unsupported
    BluetoothAvailability.PermissionMissing -> BluetoothState.PermissionMissing
    BluetoothAvailability.Disabled -> BluetoothState.Disabled
    BluetoothAvailability.Ready -> BluetoothState.Ready
}

/** The simulator and bonded Bluetooth devices. */
class AndroidDeviceCatalog(private val context: Context) : DeviceCatalog {
    override val kinds: List<DeviceKind> = listOf(DeviceKind.Simulator, DeviceKind.Bluetooth)

    override fun bluetoothState(): BluetoothState = BondedSocks.availability(context).toBluetoothState()

    override fun bluetoothDevices(): List<BluetoothDeviceEntry> = try {
        BondedSocks.list(context).map { BluetoothDeviceEntry(it.address, it.name, it.looksLikeSock) }
    } catch (_: SecurityException) {
        emptyList() // the permission is missing: bluetoothState() says so
    }
}

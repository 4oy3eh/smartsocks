package com.smartsocks.app.platform

import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.transport.desktop.SerialPortInfo
import com.smartsocks.transport.desktop.SerialPorts
import com.smartsocks.transport.desktop.SerialSockTransport
import java.io.File

/** Desktop: settings in `%APPDATA%\SmartSocks` (Windows) or `~/.smartsocks`; socks as COM ports. */
fun desktopPlatform(
    settingsFile: File = defaultDesktopSettingsFile(),
    catalog: DeviceCatalog = DesktopDeviceCatalog(),
): Platform = Platform(
    settingsStore = FileSettingsStore(settingsFile),
    devices = DeviceFactory.withSimulator { _, choice ->
        when (choice) {
            is DeviceChoice.SerialPort -> DeviceResult.Ready(SerialSockTransport(choice.portName))
            else -> unsupportedOnThisPlatform(choice, "desktop")
        }
    },
    catalog = catalog,
)

/** The simulator and serial ports (a sock paired in Windows appears as a Bluetooth COM port). */
class DesktopDeviceCatalog(private val ports: () -> List<SerialPortInfo> = SerialPorts::list) : DeviceCatalog {
    override val kinds: List<DeviceKind> = listOf(DeviceKind.Simulator, DeviceKind.SerialPort)

    override fun serialPorts(): List<SerialPortEntry> = try {
        ports().map { SerialPortEntry(it.systemName, it.description, it.looksLikeBluetooth) }
    } catch (_: Exception) {
        emptyList()
    }
}

fun defaultDesktopSettingsFile(
    osName: String = System.getProperty("os.name").orEmpty(),
    appData: String? = System.getenv("APPDATA"),
    home: String = System.getProperty("user.home"),
): File = if (osName.startsWith("Windows", ignoreCase = true) && !appData.isNullOrBlank()) {
    File(File(appData, "SmartSocks"), "settings.txt")
} else {
    File(File(home, ".smartsocks"), "settings.txt")
}

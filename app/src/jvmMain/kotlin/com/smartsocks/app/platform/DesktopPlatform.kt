package com.smartsocks.app.platform

import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.transport.desktop.SerialSockTransport
import java.io.File

/** Desktop: settings in `%APPDATA%\SmartSocks` (Windows) or `~/.smartsocks`; socks as COM ports. */
fun desktopPlatform(settingsFile: File = defaultDesktopSettingsFile()): Platform = Platform(
    settingsStore = FileSettingsStore(settingsFile),
    devices = DeviceFactory.withSimulator { _, choice ->
        when (choice) {
            is DeviceChoice.SerialPort -> DeviceResult.Ready(SerialSockTransport(choice.portName))
            else -> unsupportedOnThisPlatform(choice, "desktop")
        }
    },
)

fun defaultDesktopSettingsFile(
    osName: String = System.getProperty("os.name").orEmpty(),
    appData: String? = System.getenv("APPDATA"),
    home: String = System.getProperty("user.home"),
): File = if (osName.startsWith("Windows", ignoreCase = true) && !appData.isNullOrBlank()) {
    File(File(appData, "SmartSocks"), "settings.txt")
} else {
    File(File(home, ".smartsocks"), "settings.txt")
}

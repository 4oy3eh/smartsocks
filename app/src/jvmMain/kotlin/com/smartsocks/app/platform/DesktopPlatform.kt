package com.smartsocks.app.platform

import com.smartsocks.app.recording.FileSessionStore
import com.smartsocks.app.recording.StoredSession
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.transport.desktop.SerialPortInfo
import com.smartsocks.transport.desktop.SerialPorts
import com.smartsocks.transport.desktop.SerialSockTransport
import java.awt.Desktop
import java.io.File

/**
 * Desktop: settings in `%APPDATA%\SmartSocks` (Windows) or `~/.smartsocks`; recordings in
 * `Documents\SmartSocks`; socks as COM ports, recordings replayed as socks. [simulatorConfig]: see
 * [DeviceFactory.withSimulator].
 */
fun desktopPlatform(
    settingsFile: File = defaultDesktopSettingsFile(),
    catalog: DeviceCatalog = DesktopDeviceCatalog(),
    sessionsDir: File = defaultDesktopSessionsDir(),
    simulatorConfig: (SimConfig) -> SimConfig = { it },
): Platform {
    val store = FileSessionStore(sessionsDir, locationText = sessionsLocationText(sessionsDir))
    return Platform(
        settingsStore = FileSettingsStore(settingsFile),
        devices = DeviceFactory.withSimulator(simulatorConfig = simulatorConfig, sessions = store) { _, choice ->
            when (choice) {
                is DeviceChoice.SerialPort -> DeviceResult.Ready(SerialSockTransport(choice.portName))
                else -> unsupportedOnThisPlatform(choice, "desktop")
            }
        },
        catalog = catalog.withReplay(store),
        sessionStore = store,
        sessionActions = DesktopSessionActions(store),
    )
}

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

/** `<home>\Documents\SmartSocks`; `SMARTSOCKS_SESSIONS_DIR` overrides it (development, tests). */
fun defaultDesktopSessionsDir(
    override: String? = System.getenv("SMARTSOCKS_SESSIONS_DIR"),
    home: String = System.getProperty("user.home"),
): File = if (!override.isNullOrBlank()) File(override) else File(File(home, "Documents"), "SmartSocks")

/** "Documents\SmartSocks" for the usual place, the full path otherwise. */
internal fun sessionsLocationText(dir: File, home: String = System.getProperty("user.home")): String {
    val docs = File(File(home, "Documents"), "SmartSocks")
    return if (dir.absoluteFile == docs.absoluteFile) "Documents${File.separator}SmartSocks" else dir.path
}

/** "Show in folder": Explorer with the file selected on Windows, the folder in the file manager elsewhere. */
private class DesktopSessionActions(private val store: FileSessionStore) : SessionActions {
    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    override val canShowInFolder: Boolean = windows ||
        runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }
            .getOrDefault(false)

    override fun showInFolder(session: StoredSession?) {
        val file = session?.let { store.csvFile(it.id) }?.takeIf { it.isFile }
        Thread {
            runCatching {
                store.dir.mkdirs()
                if (windows) {
                    val args = if (file != null) "/select,${file.absolutePath}" else store.dir.absolutePath
                    ProcessBuilder("explorer.exe", args).start()
                } else {
                    Desktop.getDesktop().open(store.dir)
                }
            }
        }.apply { isDaemon = true }.start()
    }
}

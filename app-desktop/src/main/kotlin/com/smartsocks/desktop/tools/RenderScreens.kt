package com.smartsocks.desktop.tools

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.BluetoothDeviceEntry
import com.smartsocks.app.platform.BluetoothState
import com.smartsocks.app.platform.DeviceActions
import com.smartsocks.app.platform.DeviceCatalog
import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceKind
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.SerialPortEntry
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.platform.desktopPlatform
import com.smartsocks.app.ui.SettingsScreen
import com.smartsocks.app.ui.SmartSocksApp
import com.smartsocks.app.ui.SmartSocksTheme
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files

/** How the case is shown: the whole app (live or settings), or the settings screen alone with Advanced open. */
private enum class Screen { LIVE, SETTINGS, SETTINGS_EXPANDED }

private class Case(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
    val theme: AppTheme,
    val screen: Screen = Screen.LIVE,
    val settings: AppSettings = AppSettings(),
    val catalog: DeviceCatalog = desktopMock,
    /** Replaces the desktop device factory for these choices (e.g. Bluetooth as on Android). */
    val bluetooth: DeviceResult? = null,
    /** The Bluetooth permission was denied for good. */
    val permissionBlocked: Boolean = false,
)

private class PreviewActions(blocked: Boolean) : DeviceActions {
    override val changes: StateFlow<Int> = MutableStateFlow(0)
    override val bluetoothPermissionBlocked: StateFlow<Boolean> = MutableStateFlow(blocked)
}

/** A desktop with two paired socks (Bluetooth COM ports) and a plain serial port. */
private val desktopMock = object : DeviceCatalog {
    override val kinds = listOf(DeviceKind.Simulator, DeviceKind.SerialPort)

    override fun serialPorts() = listOf(
        SerialPortEntry("COM1", "Communications Port", looksLikeSock = false),
        SerialPortEntry("COM12", "Standard Serial over Bluetooth link", looksLikeSock = true),
        SerialPortEntry("COM4", "Standard Serial over Bluetooth link", looksLikeSock = true),
    )
}

private class AndroidMock(val state: BluetoothState) : DeviceCatalog {
    override val kinds = listOf(DeviceKind.Simulator, DeviceKind.Bluetooth)

    override fun bluetoothState() = state

    override fun bluetoothDevices() = if (state != BluetoothState.Ready) {
        emptyList()
    } else {
        listOf(
            BluetoothDeviceEntry("00:16:A4:10:20:30", "Block 3", looksLikeSock = true),
            BluetoothDeviceEntry("00:16:A4:10:20:31", "Block 3", looksLikeSock = true),
            BluetoothDeviceEntry("5C:F3:70:01:02:03", "Headphones", looksLikeSock = false),
        )
    }
}

private val sockL = DeviceChoice.Bluetooth("00:16:A4:10:20:30", "Block 3")
private val tuned = ProcessorSettings(contactOnThreshold = 35.0, contactOffThreshold = 15.0, copBeta = 0.2)

private val cases = listOf(
    Case("desktop-light", 1120, 760, 1f, AppTheme.LIGHT),
    Case("desktop-dark", 1120, 760, 1f, AppTheme.DARK),
    Case("desktop-min", 900, 600, 1f, AppTheme.LIGHT),
    Case("phone-light", 360, 780, 2.5f, AppTheme.LIGHT),
    // Unusable devices: Bluetooth is Android-only, the COM port does not exist.
    Case(
        "desktop-errors",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        settings = AppSettings(left = sockL, right = DeviceChoice.SerialPort("COM99")),
    ),
    Case("phone-idle", 360, 780, 2.5f, AppTheme.DARK, settings = AppSettings(autoConnect = false)),
    // Settings.
    Case("settings-desktop-dark", 1120, 760, 1f, AppTheme.DARK, Screen.SETTINGS),
    Case(
        "settings-desktop-light-full",
        1120,
        1500,
        1f,
        AppTheme.LIGHT,
        Screen.SETTINGS_EXPANDED,
        settings = AppSettings(right = DeviceChoice.Simulator(SimMode.Lifted, preloaded = true), processor = tuned),
    ),
    Case("settings-desktop-min", 900, 600, 1f, AppTheme.LIGHT, Screen.SETTINGS),
    // Serial ports (mocked): the left foot on COM4; the right one remembers COM7, which is gone. Not connected,
    // so that no real port of this machine is opened.
    Case(
        "settings-desktop-serial",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(
            left = DeviceChoice.SerialPort("COM4"),
            right = DeviceChoice.SerialPort("COM7"),
            autoConnect = false,
        ),
    ),
    Case(
        "settings-desktop-conflict",
        1120,
        760,
        1f,
        AppTheme.DARK,
        Screen.SETTINGS,
        settings = AppSettings(
            left = DeviceChoice.SerialPort("COM4"),
            right = DeviceChoice.SerialPort("com4"),
            autoConnect = false,
        ),
    ),
    Case("settings-phone", 360, 780, 2.5f, AppTheme.LIGHT, Screen.SETTINGS),
    Case("settings-phone-full", 360, 3000, 2.5f, AppTheme.DARK, Screen.SETTINGS_EXPANDED),
    // Android: the emulator (no adapter), a missing permission, a ready adapter with bonded socks.
    Case(
        "settings-android-bt-unsupported",
        360,
        900,
        2.5f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.Unsupported),
        bluetooth = DeviceResult.Unavailable("This device has no Bluetooth"),
    ),
    Case(
        "settings-android-bt-permission",
        360,
        900,
        2.5f,
        AppTheme.DARK,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
    ),
    Case(
        "settings-android-bt-ready",
        360,
        1100,
        2.5f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL, autoConnect = false),
        catalog = AndroidMock(BluetoothState.Ready),
        bluetooth = DeviceResult.Unavailable("Not opened in previews"),
    ),
    Case(
        "settings-android-bt-blocked",
        360,
        900,
        2.5f,
        AppTheme.LIGHT,
        Screen.SETTINGS,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
        permissionBlocked = true,
    ),
    Case(
        "live-android-bt-blocked",
        360,
        780,
        2.5f,
        AppTheme.DARK,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
        permissionBlocked = true,
    ),
    Case(
        "live-android-bt-permission",
        360,
        780,
        2.5f,
        AppTheme.LIGHT,
        settings = AppSettings(left = sockL),
        catalog = AndroidMock(BluetoothState.PermissionMissing),
        bluetooth = DeviceResult.Unavailable("Bluetooth permission needed", UnavailableReason.PermissionNeeded),
    ),
)

/**
 * Renders the app off-screen with simulated feet and mocked device lists (no window, no screen capture, no real
 * port opened unless a case connects to one) and writes one PNG per case to the directory given as the first
 * argument. Development tool for checking the UI.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/screens" }).apply { mkdirs() }
    val settingsDir = Files.createTempDirectory("smartsocks-render").toFile()
    val only = args.drop(1).toSet()
    runBlocking {
        for (case in cases.filter { only.isEmpty() || it.name in only }) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = InMemorySettingsStore(SettingsCodec.encode(case.settings.copy(theme = case.theme)))
            val settings = SettingsRepository(store, scope)
            val desktop = desktopPlatform(File(settingsDir, "unused.txt"), case.catalog).devices
            val devices = DeviceFactory { foot: Foot, choice: DeviceChoice ->
                if (choice is DeviceChoice.Bluetooth &&
                    case.bluetooth != null
                ) {
                    case.bluetooth
                } else {
                    desktop.create(foot, choice)
                }
            }
            val controller = LiveController(settings, devices, scope)
            val scene = ImageComposeScene(
                width = (case.widthDp * case.density).toInt(),
                height = (case.heightDp * case.density).toInt(),
                density = Density(case.density),
            ) {
                when (case.screen) {
                    Screen.LIVE, Screen.SETTINGS -> SmartSocksApp(
                        settings,
                        controller,
                        case.catalog,
                        PreviewActions(case.permissionBlocked),
                        startInSettings = case.screen == Screen.SETTINGS,
                    )

                    Screen.SETTINGS_EXPANDED -> {
                        val current by settings.settings.collectAsState()
                        SmartSocksTheme(current.theme == AppTheme.DARK) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                SettingsScreen(
                                    current,
                                    settings::update,
                                    controller,
                                    case.catalog,
                                    PreviewActions(case.permissionBlocked),
                                    advancedExpanded = true,
                                )
                            }
                        }
                    }
                }
            }
            val start = System.nanoTime()
            // ~2.5 s of frames so that both feet stream and the COP trails fill.
            repeat(150) {
                scene.render(System.nanoTime() - start)
                delay(16)
            }
            val image = scene.render(System.nanoTime() - start)
            val file = File(out, "${case.name}.png")
            file.writeBytes(requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes)
            println("wrote $file")
            scene.close()
            controller.shutdown()
            scope.cancel()
        }
    }
    settingsDir.deleteRecursively()
}

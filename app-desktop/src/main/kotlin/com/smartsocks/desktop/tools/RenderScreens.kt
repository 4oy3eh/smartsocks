package com.smartsocks.desktop.tools

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.desktopPlatform
import com.smartsocks.app.ui.SmartSocksApp
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files

private class Case(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val density: Float,
    val theme: AppTheme,
    val settingsOpen: Boolean = false,
    val settings: AppSettings = AppSettings(),
)

private val cases = listOf(
    Case("desktop-light", 1120, 760, 1f, AppTheme.LIGHT),
    Case("desktop-dark", 1120, 760, 1f, AppTheme.DARK),
    Case("desktop-min", 900, 600, 1f, AppTheme.LIGHT),
    Case("desktop-settings", 1120, 760, 1f, AppTheme.DARK, settingsOpen = true),
    Case("phone-light", 360, 780, 2.5f, AppTheme.LIGHT),
    Case("phone-settings", 360, 780, 2.5f, AppTheme.LIGHT, settingsOpen = true),
    // Unusable devices: Bluetooth is Android-only, the COM port does not exist.
    Case(
        "desktop-errors",
        1120,
        760,
        1f,
        AppTheme.LIGHT,
        settings = AppSettings(
            left = DeviceChoice.Bluetooth("00:11:22:33:44:55", "Sock L"),
            right = DeviceChoice.SerialPort("COM99"),
        ),
    ),
    Case("phone-idle", 360, 780, 2.5f, AppTheme.DARK, settings = AppSettings(autoConnect = false)),
)

/**
 * Renders the app off-screen with two simulated feet (no window, no screen capture) and writes one PNG per layout
 * and theme to the directory given as the first argument. Development tool for checking the UI.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "build/screens" }).apply { mkdirs() }
    val settingsDir = Files.createTempDirectory("smartsocks-render").toFile()
    runBlocking {
        for (case in cases) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = InMemorySettingsStore(SettingsCodec.encode(case.settings.copy(theme = case.theme)))
            val settings = SettingsRepository(store, scope)
            val controller = LiveController(settings, desktopPlatform(File(settingsDir, "unused.txt")).devices, scope)
            val scene = ImageComposeScene(
                width = (case.widthDp * case.density).toInt(),
                height = (case.heightDp * case.density).toInt(),
                density = Density(case.density),
            ) {
                SmartSocksApp(settings, controller, startInSettings = case.settingsOpen)
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

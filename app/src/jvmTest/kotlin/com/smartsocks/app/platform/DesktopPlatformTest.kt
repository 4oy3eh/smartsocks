package com.smartsocks.app.platform

import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.transport.desktop.SerialSockTransport
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopPlatformTest {
    private val dir: File = Files.createTempDirectory("smartsocks-test").toFile().apply { deleteOnExit() }

    @Test
    fun settingsFileLocation() {
        val appData = listOf("C:", "Users", "me", "AppData", "Roaming").joinToString(File.separator)
        assertEquals(
            File(File(appData, "SmartSocks"), "settings.txt"),
            defaultDesktopSettingsFile("Windows 11", appData, "C:"),
        )
        val home = File(File("/home/me", ".smartsocks"), "settings.txt")
        assertEquals(home, defaultDesktopSettingsFile("Linux", null, "/home/me"))
        assertEquals(home, defaultDesktopSettingsFile("Windows 10", "", "/home/me"), "no APPDATA: home directory")
    }

    @Test
    fun fileStoreRoundTripsAndStartsWithDefaults() = runTest {
        val store = FileSettingsStore(File(File(dir, "nested"), "settings.txt"))
        assertEquals(AppSettings(), store.load())

        val settings = AppSettings(theme = AppTheme.DARK, left = DeviceChoice.Simulator(SimMode.Lifted))
        store.save(settings)
        store.save(settings.copy(autoConnect = false)) // replaces the existing file
        assertEquals(settings.copy(autoConnect = false), FileSettingsStore(store.file).load())
        assertTrue(store.file.parentFile.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aBusyTargetIsRetriedAndTheTemporaryFileNeverStays() = runTest {
        val target = File(dir, "retry.txt")
        var failures = 2
        val moves = mutableListOf<String>()
        val store = FileSettingsStore(target, moveRetries = 3, retryDelayMillis = 0) { from, to ->
            moves += from.fileName.toString()
            if (failures-- > 0) throw AccessDeniedException(to.toString())
            FileSettingsStore.replaceFile(from, to)
        }
        store.save(AppSettings(theme = AppTheme.LIGHT))
        assertEquals(AppTheme.LIGHT, store.load().theme)
        assertEquals(3, moves.size)
        assertEquals(1, moves.toSet().size, "all attempts move the same temporary file")
        assertTrue(moves[0].startsWith("retry.txt.") && moves[0].endsWith(".tmp"))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aTargetThatStaysBusyFailsTheSaveAndKeepsTheOldFile() = runTest {
        val target = File(dir, "busy.txt")
        FileSettingsStore(target).save(AppSettings(theme = AppTheme.DARK))
        var attempts = 0
        val store = FileSettingsStore(target, moveRetries = 3, retryDelayMillis = 0) { _, to ->
            attempts++
            throw FileSystemException(to.toString(), null, "The process cannot access the file")
        }
        assertFailsWith<FileSystemException> { store.save(AppSettings(theme = AppTheme.LIGHT)) }
        assertEquals(4, attempts, "the first try and three retries")
        assertEquals(AppTheme.DARK, store.load().theme)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun desktopDevices() {
        val devices = desktopPlatform(File(dir, "s.txt")).devices
        val sim = assertIs<DeviceResult.Ready>(devices.create(Foot.LEFT, DeviceChoice.Simulator(SimMode.Standing)))
        assertEquals(SimMode.Standing, assertIs<SimulatedSockTransport>(sim.transport).mode)
        val serial = assertIs<DeviceResult.Ready>(devices.create(Foot.RIGHT, DeviceChoice.SerialPort("COM7")))
        assertEquals("COM7", assertIs<SerialSockTransport>(serial.transport).displayName)
        val bt = assertIs<DeviceResult.Unavailable>(devices.create(Foot.LEFT, DeviceChoice.Bluetooth("00:11", "Sock")))
        assertEquals("Bluetooth device Sock is not supported on desktop", bt.message)
        assertIs<DeviceResult.Unavailable>(devices.create(Foot.LEFT, DeviceChoice.None))
    }
}

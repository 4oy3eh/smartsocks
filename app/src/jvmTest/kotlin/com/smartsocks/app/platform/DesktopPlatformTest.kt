package com.smartsocks.app.platform

import com.smartsocks.app.recording.FileSessionStore
import com.smartsocks.app.recording.SessionSummary
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.sim.ReplaySockTransport
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedRecording
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.transport.desktop.SerialPortInfo
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
import kotlin.test.assertNull
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
    fun desktopCatalogOffersTheSimulatorAndSerialPorts() {
        val catalog = DesktopDeviceCatalog {
            listOf(
                SerialPortInfo("COM3", "Standard Serial over Bluetooth link", looksLikeBluetooth = true),
                SerialPortInfo("COM1", "Communications Port", looksLikeBluetooth = false),
            )
        }
        assertEquals(listOf(DeviceKind.Simulator, DeviceKind.SerialPort), catalog.kinds)
        assertEquals(
            listOf(
                SerialPortEntry("COM3", "Standard Serial over Bluetooth link", looksLikeSock = true),
                SerialPortEntry("COM1", "Communications Port", looksLikeSock = false),
            ),
            catalog.serialPorts(),
        )
        assertEquals(BluetoothState.Unsupported, catalog.bluetoothState())
        assertTrue(catalog.bluetoothDevices().isEmpty())
        assertTrue(DesktopDeviceCatalog { error("enumeration failed") }.serialPorts().isEmpty())
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

    @Test
    fun desktopReplaysRecordingsThatHaveRawPackets() {
        val sessionsDir = File(dir, "replay-sessions")
        val store = FileSessionStore(sessionsDir)
        val rec = store.create(saveRaw = true)
        SimulatedRecording.write(
            rec.csv,
            rec.raw,
            1.0,
            SampleRate.HZ_50,
            MeasurementRange.K128,
            feet = setOf(Foot.RIGHT),
        )
        val platform = desktopPlatform(File(dir, "s.txt"), DesktopDeviceCatalog { emptyList() }, sessionsDir)

        assertEquals(listOf(DeviceKind.Simulator, DeviceKind.SerialPort, DeviceKind.Replay), platform.catalog.kinds)
        val entry = platform.catalog.replaySessions().single()
        assertEquals(rec.id, entry.id)
        assertEquals(setOf(Foot.RIGHT), entry.feet)
        assertEquals(StreamSettings(SampleRate.HZ_50, MeasurementRange.K128), entry.stream)

        val ready = assertIs<DeviceResult.Ready>(
            platform.devices.create(Foot.LEFT, DeviceChoice.Replay(rec.id, Foot.RIGHT)),
        )
        assertEquals(entry.stream, ready.stream)
        assertIs<ReplaySockTransport>(ready.transport)

        // The raw dump streams line by line and is released when closed.
        val lines = store.openRaw(rec.id).use { source -> source.asSequence().toList() }
        assertEquals(store.rawFile(rec.id).readLines(), lines)
        assertEquals(52, lines.size)

        // A replay holds the dump open: renaming and deleting still work (Windows: opened with share-delete).
        val held = store.openRaw(rec.id)
        held.next()
        val renamed = store.rename(rec.id, "walk")
        assertTrue(store.rawFile("walk").isFile && !store.rawFile(rec.id).exists())
        assertEquals(51, held.asSequence().count(), "the open dump reads on")
        val again = store.openRaw(renamed.id)
        store.delete(renamed.id)
        again.close()
        held.close()
        assertNull(store.find(renamed.id))
        assertTrue(sessionsDir.listFiles().orEmpty().isEmpty(), "no file left: ${sessionsDir.list()?.toList()}")
        assertFailsWith<NoSuchElementException> { store.openRaw(renamed.id) }
    }

    @Test
    fun deleteRemovesTheRecordingItselfLast() {
        val sessionsDir = File(dir, "delete-order")
        val store = FileSessionStore(sessionsDir)
        val rec = store.create(saveRaw = true)
        SimulatedRecording.write(rec.csv, rec.raw, 0.5, SampleRate.HZ_50, MeasurementRange.K64)
        store.writeSummary(rec.id, SessionSummary(500.0, Foot.entries.toSet(), 50, 0))
        // Make the raw dump undeletable: a directory in its place cannot be deleted while it holds a file.
        store.rawFile(rec.id).delete()
        store.rawFile(rec.id).mkdirs()
        File(store.rawFile(rec.id), "x").writeText("x")
        assertFailsWith<java.io.IOException> { store.delete(rec.id) }
        assertTrue(store.csvFile(rec.id).isFile, "the recording stays when its dump cannot go")
        assertTrue(store.find(rec.id) != null, "still listed")
    }
}

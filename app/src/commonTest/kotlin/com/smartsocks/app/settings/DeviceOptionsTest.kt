package com.smartsocks.app.settings

import com.smartsocks.app.platform.BluetoothDeviceEntry
import com.smartsocks.app.platform.SerialPortEntry
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.sim.SimMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceOptionsTest {
    private val ports = listOf(
        SerialPortEntry("COM1", "Communications Port", looksLikeSock = false),
        SerialPortEntry("COM10", "Standard Serial over Bluetooth link", looksLikeSock = true),
        SerialPortEntry("COM3", "Standard Serial over Bluetooth link", looksLikeSock = true),
        SerialPortEntry("COM2", "USB Serial", looksLikeSock = false),
    )

    @Test
    fun serialPortsListBluetoothLinksFirstInPortNumberOrder() {
        val options = DeviceOptions.serialPorts(ports, Foot.LEFT, AppSettings())
        assertEquals(listOf("COM3", "COM10", "COM1", "COM2"), options.map { it.title })
        assertEquals("Standard Serial over Bluetooth link", options[0].detail)
        assertTrue(options.all { it.present && !it.selected && !it.usedByOtherFoot })
        assertEquals(DeviceChoice.SerialPort("COM3"), options[0].choice)
    }

    @Test
    fun theRememberedPortIsShownFirstWhenItIsGone() {
        val settings = AppSettings(left = DeviceChoice.SerialPort("COM7"))
        val options = DeviceOptions.serialPorts(ports, Foot.LEFT, settings)
        assertEquals(5, options.size)
        val missing = options.first()
        assertEquals("COM7", missing.title)
        assertFalse(missing.present)
        assertTrue(missing.selected)

        val present = DeviceOptions.serialPorts(ports, Foot.LEFT, AppSettings(left = DeviceChoice.SerialPort("com10")))
        assertEquals(4, present.size, "port names ignore case")
        assertTrue(present.single { it.selected }.present)
    }

    @Test
    fun theOtherFootsDeviceCannotBePicked() {
        val settings = AppSettings(left = DeviceChoice.SerialPort("COM3"), right = DeviceChoice.Simulator())
        val right = DeviceOptions.serialPorts(ports, Foot.RIGHT, settings)
        val taken = right.single { it.title == "COM3" }
        assertTrue(taken.usedByOtherFoot)
        assertFalse(taken.selectable)
        assertEquals(DeviceChoice.SerialPort("COM10"), DeviceOptions.suggestion(right)?.choice)

        // Picking it anyway (a stale tap) changes nothing.
        assertSame(settings, settings.withDeviceIfFree(Foot.RIGHT, DeviceChoice.SerialPort("com3")))
        assertEquals(
            DeviceChoice.SerialPort("COM10"),
            settings.withDeviceIfFree(Foot.RIGHT, DeviceChoice.SerialPort("COM10")).right,
        )
    }

    @Test
    fun sameDeviceMeansSamePortOrAddressNeverTheSimulator() {
        assertTrue(DeviceChoice.SerialPort("COM4").isSameDeviceAs(DeviceChoice.SerialPort("com4")))
        assertFalse(DeviceChoice.SerialPort("COM4").isSameDeviceAs(DeviceChoice.SerialPort("COM14")))
        assertTrue(
            DeviceChoice.Bluetooth("00:16:a4:01", "L").isSameDeviceAs(DeviceChoice.Bluetooth("00:16:A4:01", "R")),
        )
        assertFalse(DeviceChoice.Bluetooth("00:16:A4:01", "x").isSameDeviceAs(DeviceChoice.SerialPort("00:16:A4:01")))
        assertFalse(DeviceChoice.Simulator().isSameDeviceAs(DeviceChoice.Simulator()))
        assertFalse(DeviceChoice.None.isSameDeviceAs(DeviceChoice.None))

        val clash = AppSettings(left = DeviceChoice.SerialPort("COM4"), right = DeviceChoice.SerialPort("COM4"))
        assertTrue(clash.deviceInUseByOtherFoot(Foot.RIGHT))
        assertFalse(AppSettings(right = DeviceChoice.Simulator(SimMode.Lifted)).deviceInUseByOtherFoot(Foot.RIGHT))
    }

    @Test
    fun theIdentityIgnoresDisplayNamesCaseAndTheSimulatorMode() {
        assertEquals(
            DeviceChoice.Bluetooth("00:16:a4:00:00:01", "Block 3").identity,
            DeviceChoice.Bluetooth("00:16:A4:00:00:01", "").identity,
        )
        assertEquals(DeviceChoice.SerialPort("com5").identity, DeviceChoice.SerialPort("COM5").identity)
        assertEquals(DeviceChoice.Simulator(SimMode.Walking).identity, DeviceChoice.Simulator(SimMode.Lifted).identity)
        assertNotEquals(DeviceChoice.Simulator().identity, DeviceChoice.Simulator(preloaded = true).identity)
        assertNotEquals(DeviceChoice.SerialPort("COM5").identity, DeviceChoice.SerialPort("COM15").identity)
        assertNotEquals(
            DeviceChoice.SerialPort("00:16:A4:00:00:01").identity,
            DeviceChoice.Bluetooth("00:16:A4:00:00:01", "").identity,
        )
    }

    @Test
    fun whenBothFeetNameTheSameSockTheLeftKeepsIt() {
        val clash = AppSettings(left = DeviceChoice.SerialPort("COM3"), right = DeviceChoice.SerialPort("com3"))
        val left = DeviceOptions.serialPorts(ports, Foot.LEFT, clash).single { it.selected }
        assertEquals("COM3", left.title)
        assertFalse(left.usedByOtherFoot)
        val right = DeviceOptions.serialPorts(ports, Foot.RIGHT, clash).single { it.selected }
        assertTrue(right.usedByOtherFoot)
        // Also when the port is gone.
        val gone = AppSettings(left = DeviceChoice.SerialPort("COM9"), right = DeviceChoice.SerialPort("COM9"))
        assertFalse(DeviceOptions.serialPorts(ports, Foot.LEFT, gone).first().usedByOtherFoot)
        assertTrue(DeviceOptions.serialPorts(ports, Foot.RIGHT, gone).first().usedByOtherFoot)
    }

    @Test
    fun bluetoothDevicesListSocksFirstAndKeepTheRememberedOne() {
        val bonded = listOf(
            BluetoothDeviceEntry("5C:00:00:00:00:01", "Headphones", looksLikeSock = false),
            BluetoothDeviceEntry("00:16:A4:00:00:02", "Block 3", looksLikeSock = true),
            BluetoothDeviceEntry("00:16:A4:00:00:01", null, looksLikeSock = true),
        )
        val settings = AppSettings(
            left = DeviceChoice.Bluetooth("00:16:a4:00:00:02", "Block 3"),
            right = DeviceChoice.Bluetooth("11:22:33:44:55:66", "Old sock"),
        )
        val left = DeviceOptions.bluetooth(bonded, Foot.LEFT, settings)
        assertEquals(listOf("00:16:A4:00:00:01", "Block 3", "Headphones"), left.map { it.title })
        assertTrue(left[1].selected)
        assertEquals(DeviceChoice.Bluetooth("00:16:A4:00:00:01", ""), left[0].choice)

        val right = DeviceOptions.bluetooth(bonded, Foot.RIGHT, settings)
        assertEquals("Old sock", right[0].title)
        assertFalse(right[0].present, "no longer bonded")
        assertTrue(right.single { it.title == "Block 3" }.usedByOtherFoot)
        assertEquals("00:16:A4:00:00:01", (DeviceOptions.suggestion(right)?.choice as DeviceChoice.Bluetooth).address)
    }

    @Test
    fun noSuggestionWithoutAFreePresentDevice() {
        assertNull(DeviceOptions.suggestion(DeviceOptions.serialPorts(emptyList(), Foot.LEFT, AppSettings())))
        val onlyTaken = listOf(SerialPortEntry("COM3", "", looksLikeSock = true))
        val settings = AppSettings(left = DeviceChoice.SerialPort("COM3"), right = DeviceChoice.SerialPort("COM9"))
        assertNull(DeviceOptions.suggestion(DeviceOptions.serialPorts(onlyTaken, Foot.RIGHT, settings)))
        val plain = listOf(SerialPortEntry("COM1", "", looksLikeSock = false))
        assertEquals(
            "COM1",
            DeviceOptions.suggestion(DeviceOptions.serialPorts(plain, Foot.LEFT, AppSettings()))?.title,
        )
    }
}

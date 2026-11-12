package com.smartsocks.transport.android

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluetoothHelpersTest {
    @Test
    fun legacyPermissionsBelowAndroid12() {
        for (sdk in listOf(26, 29, 30)) {
            assertContentEquals(
                arrayOf("android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN"),
                BluetoothPermissions.requiredPermissions(sdk),
            )
        }
    }

    @Test
    fun connectPermissionFromAndroid12() {
        for (sdk in listOf(31, 33, 37)) {
            assertContentEquals(
                arrayOf("android.permission.BLUETOOTH_CONNECT"),
                BluetoothPermissions.requiredPermissions(sdk),
            )
        }
    }

    @Test
    fun scanIsNeverRequired() {
        for (sdk in 26..40) {
            assertFalse(BluetoothPermissions.BLUETOOTH_SCAN in BluetoothPermissions.requiredPermissions(sdk))
        }
    }

    @Test
    fun sockHeuristic() {
        assertTrue(BondedSocks.looksLikeSock("Block 3", "AA:BB:CC:DD:EE:FF"))
        assertTrue(BondedSocks.looksLikeSock("SmartSock L", "AA:BB:CC:DD:EE:FF"))
        assertTrue(BondedSocks.looksLikeSock(null, "00:16:a4:48:8E:E6"))
        assertFalse(BondedSocks.looksLikeSock("Galaxy Buds", "AA:BB:CC:DD:EE:FF"))
        assertFalse(BondedSocks.looksLikeSock(null, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun socksAreListedFirstThenByName() {
        val sorted = BondedSocks.sortForPicker(
            listOf(
                BluetoothDeviceInfo("01", "car audio", false),
                BluetoothDeviceInfo("02", "Block 4", true),
                BluetoothDeviceInfo("03", null, false),
                BluetoothDeviceInfo("04", "block 3", true),
                BluetoothDeviceInfo("05", "Buds", false),
            ),
        )
        assertEquals(listOf("04", "02", "03", "05", "01"), sorted.map { it.address })
    }
}

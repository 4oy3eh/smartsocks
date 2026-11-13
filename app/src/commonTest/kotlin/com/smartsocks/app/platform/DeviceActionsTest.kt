package com.smartsocks.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DeviceActionsTest {
    private class Recording : DeviceActions {
        override val changes: StateFlow<Int> = MutableStateFlow(0)
        override val bluetoothPermissionBlocked = MutableStateFlow(false)
        val calls = mutableListOf<String>()

        override fun requestBluetoothPermission() {
            calls += "request"
        }

        override fun openAppSettings() {
            calls += "app settings"
        }
    }

    @Test
    fun grantAsksUntilTheRequestIsBlockedThenOpensTheAppSettings() {
        val actions = Recording()
        actions.grantBluetoothPermission()
        actions.bluetoothPermissionBlocked.value = true
        actions.grantBluetoothPermission()
        assertEquals(listOf("request", "app settings"), actions.calls)
    }

    @Test
    fun theNoOpActionsAreNeverBlocked() {
        assertFalse(DeviceActions.None.bluetoothPermissionBlocked.value)
        DeviceActions.None.grantBluetoothPermission() // does nothing, does not throw
    }
}

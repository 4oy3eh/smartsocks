package com.smartsocks.app.android

import android.content.pm.ServiceInfo
import com.smartsocks.app.recording.KeepAliveType
import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundServiceTypesTest {
    @Test
    fun connectedDeviceFallsBackToSpecialUseFromAndroid14() {
        assertEquals(
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            ),
            foregroundServiceAttempts(KeepAliveType.ConnectedDevice, sdkInt = 34),
        )
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE),
            foregroundServiceAttempts(KeepAliveType.SpecialUse, sdkInt = 37),
        )
    }

    @Test
    fun beforeAndroid14OneAttemptWithoutAType() {
        for (type in KeepAliveType.entries) {
            assertEquals(listOf<Int?>(null), foregroundServiceAttempts(type, sdkInt = 33))
            assertEquals(listOf<Int?>(null), foregroundServiceAttempts(type, sdkInt = 26))
        }
    }

    @Test
    fun theTypesAreTheManifestOnes() {
        // connectedDevice = 0x10, specialUse = 0x40000000 (android:foregroundServiceType="connectedDevice|specialUse").
        assertEquals(0x10, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        assertEquals(0x40000000, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}

package com.smartsocks.app.platform

import android.content.Context
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.transport.android.RfcommSockTransport
import java.io.File

/** Android: settings in the app's files directory; socks over Bluetooth RFCOMM. */
fun androidPlatform(context: Context): Platform {
    val app = context.applicationContext
    return Platform(
        settingsStore = FileSettingsStore(File(app.filesDir, "settings.txt")),
        devices = DeviceFactory.withSimulator { _, choice ->
            when (choice) {
                is DeviceChoice.Bluetooth ->
                    DeviceResult.Ready(RfcommSockTransport(app, choice.address, choice.name.ifBlank { choice.address }))

                else -> unsupportedOnThisPlatform(choice, "Android")
            }
        },
    )
}

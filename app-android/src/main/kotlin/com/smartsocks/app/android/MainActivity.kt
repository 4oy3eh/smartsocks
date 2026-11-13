package com.smartsocks.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.smartsocks.app.ui.SmartSocksApp

class MainActivity : ComponentActivity() {
    private val model: LiveViewModel by viewModels()

    /** Registered with the activity's creation, as result launchers must be. */
    private val deviceActions = ActivityDeviceActions(this) { model.controller.recheckDevices() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val catalog = (application as SmartSocksApplication).platform.catalog
        setContent {
            SmartSocksApp(model.settings, model.controller, catalog, deviceActions)
        }
    }

    override fun onStart() {
        super.onStart()
        model.controller.setPaused(false)
        // The user may have granted the permission, paired a sock or switched Bluetooth on elsewhere meanwhile.
        deviceActions.onStart()
        deviceActions.changed()
    }

    override fun onStop() {
        super.onStop()
        deviceActions.onStop()
        // Since Android 12 Back keeps the root activity (and so the ViewModel) alive: without this the socks
        // would stream in the background forever. A configuration change restarts the activity at once: keep
        // the links up across it.
        if (!isChangingConfigurations) model.controller.setPaused(true)
        (application as SmartSocksApplication).flushSettings()
    }
}

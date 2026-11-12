package com.smartsocks.app.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.smartsocks.app.ui.SmartSocksApp

class MainActivity : ComponentActivity() {
    private val model: LiveViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SmartSocksApp(model.settings, model.controller)
        }
    }

    override fun onStart() {
        super.onStart()
        model.controller.setPaused(false)
    }

    override fun onStop() {
        super.onStop()
        // Since Android 12 Back keeps the root activity (and so the ViewModel) alive: without this the socks
        // would stream in the background forever. A configuration change restarts the activity at once: keep
        // the links up across it.
        if (!isChangingConfigurations) model.controller.setPaused(true)
        (application as SmartSocksApplication).flushSettings()
    }
}

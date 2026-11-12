package com.smartsocks.app.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartsocks.app.live.LiveController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus

/** Keeps both foot sessions alive across configuration changes; they end with the activity. */
class LiveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmartSocksApplication

    val settings = app.settings
    val controller = LiveController(settings, app.platform.devices, viewModelScope + Dispatchers.Default)
}

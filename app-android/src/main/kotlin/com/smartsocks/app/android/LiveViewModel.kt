package com.smartsocks.app.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.playback.SessionsController
import com.smartsocks.app.recording.RecordingController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus

/**
 * Keeps both foot sessions, a running recording and the open player alive across configuration changes; they end
 * with the activity.
 */
class LiveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmartSocksApplication

    val settings = app.settings
    val controller = LiveController(settings, app.platform.devices, viewModelScope + Dispatchers.Default)
    val recorder = RecordingController(
        controller,
        app.platform.sessionStore,
        settings,
        viewModelScope + Dispatchers.Default,
        Dispatchers.IO,
    )

    /** On the main thread: the player is driven from the UI. */
    val sessions = SessionsController(
        app.platform.sessionStore,
        viewModelScope,
        Dispatchers.IO,
        activeRecording = recorder.activeRecording,
    )
}

package com.smartsocks.app.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartsocks.app.live.ReplayFileUsers
import com.smartsocks.app.playback.SessionsController
import kotlinx.coroutines.Dispatchers

/**
 * The screen's state holders. Both feet and the recorder belong to the process ([SmartSocksApplication.live]: a
 * recording goes on in the background); the open player lives here, across configuration changes, and ends with
 * the activity.
 */
class LiveViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SmartSocksApplication

    val settings = app.settings
    val controller = app.live.controller
    val recorder = app.live.recorder

    /** On the main thread: the player is driven from the UI. */
    val sessions = SessionsController(
        app.platform.sessionStore,
        viewModelScope,
        Dispatchers.IO,
        activeRecording = recorder.activeRecording,
        fileUsers = ReplayFileUsers(controller, settings),
    )
}

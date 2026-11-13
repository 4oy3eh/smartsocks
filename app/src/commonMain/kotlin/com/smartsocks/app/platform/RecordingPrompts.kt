package com.smartsocks.app.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the platform asks or tells around recording (Android: the notification permission). */
interface RecordingPrompts {
    /**
     * Record was pressed (UI thread), right before the recording starts; it starts whatever the answer. Android 13+
     * asks for the notification permission here, the first time.
     */
    fun beforeRecording() = Unit

    /** A note for the recording strip (e.g. that notifications are off), shown until [dismissNotice]. */
    val notice: StateFlow<String?> get() = NoNotice

    fun dismissNotice() = Unit

    companion object {
        private val NoNotice: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

        val None: RecordingPrompts = object : RecordingPrompts {}
    }
}

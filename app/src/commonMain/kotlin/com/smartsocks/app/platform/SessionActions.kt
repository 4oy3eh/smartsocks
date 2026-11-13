package com.smartsocks.app.platform

import com.smartsocks.app.recording.StoredSession

/** What the platform can do with a recording's files besides playing them. */
interface SessionActions {
    /** Android: the system share sheet. */
    val canShare: Boolean get() = false

    /** Desktop: open the folder in the file manager. */
    val canShowInFolder: Boolean get() = false

    fun share(session: StoredSession) = Unit

    /** Opens the recordings folder, selecting [session] if given. */
    fun showInFolder(session: StoredSession?) = Unit

    companion object {
        val None: SessionActions = object : SessionActions {}
    }
}

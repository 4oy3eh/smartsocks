package com.smartsocks.app.platform

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.smartsocks.app.recording.FileSessionStore
import com.smartsocks.app.recording.SessionNames
import com.smartsocks.app.recording.StoredSession

/**
 * "Share" through the system share sheet: the recording (and its raw packet file) as `text/csv` content URIs from
 * the [FileProvider] registered under [authority] (see the app's manifest and `res/xml/session_paths.xml`).
 */
class AndroidSessionActions(
    private val context: Context,
    private val store: FileSessionStore,
    private val authority: String,
) : SessionActions {
    override val canShare: Boolean = true

    override fun share(session: StoredSession) {
        val uris = store.files(session.id).mapNotNull { file ->
            runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
        }
        if (uris.isEmpty()) return
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
        }.apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_SUBJECT, "SmartSocks session ${SessionNames.display(session.id)}")
            clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "Share session").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            // Nothing can receive files: nothing to do.
        }
    }
}

package com.smartsocks.app.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import com.smartsocks.app.platform.RecordingPrompts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Before every recording: are the recording notifications shown? Android 13+ asks for the notification permission
 * the first time (the recording starts meanwhile). If they are off (denied, or switched off for the app or its
 * "Recording" channel) the recording still goes on in the background, only without its notification, and the strip
 * says so. Create it while the activity is being created (the result launcher must be registered before it starts).
 *
 * "Asked once" is kept in the shared preferences [PREFS], which backup leaves out (backup_rules.xml,
 * data_extraction_rules.xml): a restored install asks again.
 */
class ActivityRecordingPrompts(private val activity: ComponentActivity) : RecordingPrompts {
    private val _notice = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = _notice.asStateFlow()

    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val request =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) _notice.value = OFF_NOTICE
        }

    override fun beforeRecording() {
        if (notificationsShown()) {
            _notice.value = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !prefs.getBoolean(KEY_ASKED, false)) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                prefs.edit { putBoolean(KEY_ASKED, true) }
                request.launch(permission)
                return
            }
        }
        _notice.value = OFF_NOTICE
    }

    /** The app's notifications are on, and so is the recording channel (if it exists yet: created on first use). */
    private fun notificationsShown(): Boolean {
        val manager = activity.getSystemService(NotificationManager::class.java) ?: return true
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(RecordingService.CHANNEL_ID) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    override fun dismissNotice() {
        _notice.value = null
    }

    private companion object {
        const val PREFS = "recording"
        const val KEY_ASKED = "notificationPermissionAsked"
        const val OFF_NOTICE =
            "Notifications are off: the recording goes on in the background, but without its notification " +
                "(elapsed time, Mark, Stop). Open the app to stop it."
    }
}

package com.smartsocks.app.android

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.smartsocks.app.R
import com.smartsocks.app.recording.BackgroundRecording
import com.smartsocks.app.recording.KeepAliveType
import com.smartsocks.app.recording.RecordingCommand
import com.smartsocks.app.recording.RecordingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.smartsocks.app.recording.KeepAlive as KeepAliveContract

/**
 * Keeps a recording going while the app is in the background: a foreground service with the recording
 * notification (elapsed time, feet, drops; Mark and Stop) and a partial wake lock (the CPU stays on with the screen
 * off). It holds no logic: the recording, the feet and the decisions are in [SmartSocksApplication.live]
 * ([BackgroundRecording]); the notification's actions call the same methods as the Live screen.
 *
 * Started with [Context.startService] from the foreground when a recording starts (see [KeepAlive]); it then goes
 * foreground at once. Not with `startForegroundService`: if going foreground is refused, that would crash the app
 * once the service stops. Types: see [foregroundServiceAttempts]. Swiping the app away from the recents keeps the
 * recording (and this service) going: the notification stops it. Swiping the notification away (allowed from
 * Android 14) brings it back.
 */
class RecordingService : Service() {
    private val live get() = (application as SmartSocksApplication).live

    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** The keep-alive request this service runs for, see [KeepAliveContract.start]. */
    private var request = 0L

    /** The type it was last asked to run as (and went foreground with, or its fallback). */
    private var type: KeepAliveType? = null

    private var posted: RecordingState.Recording? = null
    private var postedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val type = intent.getStringExtra(EXTRA_TYPE)?.let { name ->
                    KeepAliveType.entries.firstOrNull { it.name == name }
                } ?: KeepAliveType.SpecialUse
                val request = intent.getLongExtra(EXTRA_REQUEST, 0)
                if (scope == null) {
                    goForeground(type, request)
                } else {
                    // Still running: for this recording now (or still), possibly as another type.
                    this.request = request
                    if (type != this.type) retype(type)
                    live.background.onKeepAliveStarted(request)
                }
            }

            ACTION_MARK -> live.background.perform(RecordingCommand.Mark)

            ACTION_STOP -> live.background.perform(RecordingCommand.Stop)

            // The user swiped the notification away: without it there is no Mark or Stop.
            ACTION_REPOST -> if (scope != null) post()
        }
        // Not foreground (refused, or a stale notification action after the recording): nothing to keep running.
        if (scope == null) stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun goForeground(type: KeepAliveType, request: Long) {
        val recording = live.recorder.state.value as? RecordingState.Recording
        if (recording == null) {
            // Already over (stopped right after the start): nothing to keep alive.
            live.background.onKeepAliveFailed(request)
            return
        }
        createChannel()
        val notification = notification(recording)
        val started = foregroundServiceAttempts(type, Build.VERSION.SDK_INT).any {
            tryStartForeground(it, notification)
        }
        if (!started) {
            live.background.onKeepAliveFailed(request)
            return
        }
        this.request = request
        this.type = type
        posted = recording
        postedAt = SystemClock.elapsedRealtime()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        this.scope = scope
        holdWakeLock(scope)
        live.background.onKeepAliveStarted(request)
        scope.launch {
            // The first post is blocked if the permission request that Record started is still open: post again
            // once notifications are on (then stop asking).
            val nm = notificationManager()
            var wasOff = false
            while (!nm.areNotificationsEnabled()) {
                wasOff = true
                delay(NOTIFICATIONS_CHECK_MILLIS)
            }
            if (wasOff) post()
        }
        scope.launch {
            live.recorder.state
                .map { it as? RecordingState.Recording }
                .filterNotNull()
                .distinctUntilChanged { a, b -> a.id == b.id && text(a) == text(b) }
                .conflate()
                .collect { next ->
                    val last = posted
                    if (last != null && last.id == next.id && text(last) == text(next)) return@collect
                    val since = SystemClock.elapsedRealtime() - postedAt
                    delay(BackgroundRecording.notificationDelayMillis(last, next, since))
                    post()
                }
        }
    }

    /**
     * Runs on as [type] (e.g. `connectedDevice` once a Bluetooth sock joined a recording started as `specialUse`): goes
     * foreground again with that type. If the system refuses, it keeps running as it was.
     */
    private fun retype(type: KeepAliveType) {
        if (Build.VERSION.SDK_INT < FOREGROUND_TYPES_SDK) {
            this.type = type // types are not enforced: nothing to change
            return
        }
        val recording = live.recorder.state.value as? RecordingState.Recording ?: return
        if (tryStartForeground(
                foregroundServiceAttempts(type, Build.VERSION.SDK_INT).first(),
                notification(recording),
            )
        ) {
            this.type = type
            posted = recording
            postedAt = SystemClock.elapsedRealtime()
        }
    }

    /** Posts the notification for the recording as it is now. */
    private fun post() {
        val state = live.recorder.state.value as? RecordingState.Recording ?: return
        notificationManager().notify(NOTIFICATION_ID, notification(state))
        posted = state
        postedAt = SystemClock.elapsedRealtime()
    }

    /** False if the system refuses [type] now (not allowed from the background, missing prerequisite). */
    @SuppressLint("NewApi") // a type only for Android 14+, see foregroundServiceAttempts
    private fun tryStartForeground(type: Int?, notification: Notification): Boolean = try {
        if (type != null) {
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException, MissingForegroundServiceTypeException,
        // InvalidForegroundServiceTypeException: all ServiceStartNotAllowedException (an IllegalStateException).
        Log.w(TAG, "Cannot keep the recording going in the background as type $type", e)
        false
    } catch (e: SecurityException) {
        // The type's runtime prerequisite is missing (e.g. no Bluetooth permission for connectedDevice).
        Log.w(TAG, "Cannot keep the recording going in the background as type $type", e)
        false
    }

    /**
     * A partial wake lock while the service runs, re-armed every [WAKE_LOCK_REFRESH_MILLIS] with a timeout of
     * [WAKE_LOCK_TIMEOUT_MILLIS]: if this process stops refreshing it (hung), it is released by itself.
     */
    private fun holdWakeLock(scope: CoroutineScope) {
        val power = getSystemService(PowerManager::class.java) ?: return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
        wakeLock = lock
        scope.launch {
            while (true) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MILLIS)
                delay(WAKE_LOCK_REFRESH_MILLIS)
            }
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        val wasForeground = scope != null
        scope = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (wasForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Finalizes a recording still running (destroyed without the recording ending) if in the background.
            live.background.onKeepAliveEnded(request)
        }
        super.onDestroy()
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)!!

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a session is recorded"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun text(state: RecordingState.Recording) = BackgroundRecording.notificationText(state)

    private fun notification(state: RecordingState.Recording): Notification {
        val elapsedMillis = (live.controller.nowNanos() - state.startedNanos) / 1_000_000
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle("Recording")
            .setContentText(text(state))
            .setWhen(System.currentTimeMillis() - elapsedMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setDeleteIntent(serviceIntent(ACTION_REPOST))
            .addAction(action(R.drawable.ic_action_mark, "Mark", ACTION_MARK))
            .addAction(action(R.drawable.ic_action_stop, "Stop", ACTION_STOP))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun serviceIntent(action: String): PendingIntent {
        val intent = Intent(this, RecordingService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(icon: Int, title: String, action: String): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, serviceIntent(action)).build()

    /**
     * [KeepAliveContract] by this service. [onFailed] gets the request if the service cannot even be started (the
     * app is no longer in the foreground); a refusal to go foreground is reported by the service itself.
     */
    class KeepAlive(private val context: Context, private val onFailed: (Long) -> Unit) : KeepAliveContract {
        override fun start(type: KeepAliveType, request: Long) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name)
                .putExtra(EXTRA_REQUEST, request)
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                // Background start not allowed (the app left the foreground right after Record).
                Log.w(TAG, "Cannot start the recording service", e)
                onFailed(request)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot start the recording service", e)
                onFailed(request)
            }
        }

        override fun changeType(type: KeepAliveType, request: Long) {
            val intent = Intent(context, RecordingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name)
                .putExtra(EXTRA_REQUEST, request)
            try {
                // The service is in the foreground: the app may start it from the background.
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot change the recording service to $type", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot change the recording service to $type", e)
            }
        }

        override fun stop() {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }

    companion object {
        /** The notification channel of the recording notification. */
        const val CHANNEL_ID = "recording"

        private const val TAG = "SmartSocksRecording"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_START = "com.smartsocks.app.action.RECORDING_START"
        private const val ACTION_MARK = "com.smartsocks.app.action.RECORDING_MARK"
        private const val ACTION_STOP = "com.smartsocks.app.action.RECORDING_STOP"
        private const val ACTION_REPOST = "com.smartsocks.app.action.RECORDING_REPOST"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_REQUEST = "request"
        private const val NOTIFICATIONS_CHECK_MILLIS = 3_000L
        private const val WAKE_LOCK_TAG = "SmartSocks:recording"
        private const val WAKE_LOCK_TIMEOUT_MILLIS = 30 * 60_000L
        private const val WAKE_LOCK_REFRESH_MILLIS = 10 * 60_000L
    }
}

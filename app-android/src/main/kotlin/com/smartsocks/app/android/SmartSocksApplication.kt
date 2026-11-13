package com.smartsocks.app.android

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.Platform
import com.smartsocks.app.platform.androidPlatform
import com.smartsocks.app.recording.BackgroundRecording
import com.smartsocks.app.recording.RecordingController
import com.smartsocks.app.recording.StartedScreens
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.transport.android.BluetoothPermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Process-wide services. The settings live as long as the process, so a pending save survives the activity; so do
 * both feet and the recorder ([live]): a recording goes on in the background (see [RecordingService]) and the
 * activity, when it comes back, shows it as it is. Whether the app is in the foreground comes from the started
 * activities ([StartedScreens]), not from one activity's callbacks.
 */
class SmartSocksApplication : Application() {
    /** Never cancelled; for work that must finish even when the activity goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var platform: Platform
        private set

    lateinit var settings: SettingsRepository
        private set

    /**
     * Created on first use (the activity, or the recording service), all together; the feet stay paused until an
     * activity is started.
     */
    val live: LiveServices by lazy { LiveServices(this) }

    private val screens = StartedScreens { foreground ->
        if (foreground) live.background.onForeground() else live.background.onBackground()
    }

    override fun onCreate() {
        super.onCreate()
        platform = androidPlatform(this, "$packageName.sessions")
        settings = SettingsRepository(platform.settingsStore, appScope)
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = screens.onStarted()

                override fun onActivityStopped(activity: Activity) =
                    screens.onStopped(activity.isChangingConfigurations)

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /** Saves pending settings now (the process may be killed once in the background). */
    fun flushSettings() {
        appScope.launch {
            withContext(NonCancellable) { runCatching { settings.flush() } }
        }
    }
}

/** Both feet, the recorder and what keeps a recording going in the background; shared by activity and service. */
class LiveServices(app: SmartSocksApplication) {
    /** Paused until a screen is started: the process may start without one (e.g. for the service). */
    val controller = LiveController(app.settings, app.platform.devices, app.appScope, initiallyPaused = true)

    val recorder = RecordingController(
        controller,
        app.platform.sessionStore,
        app.settings,
        app.appScope,
        Dispatchers.IO,
    )

    val background: BackgroundRecording = BackgroundRecording(
        controller,
        recorder,
        app.settings,
        RecordingService.KeepAlive(app) { request -> background.onKeepAliveFailed(request) },
        bluetoothPermission = { BluetoothPermissions.hasPermissions(app) },
        app.appScope,
    )
}

package com.smartsocks.app.android

import android.app.Application
import com.smartsocks.app.platform.Platform
import com.smartsocks.app.platform.androidPlatform
import com.smartsocks.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Process-wide services: the settings live as long as the process, so a pending save survives the activity. */
class SmartSocksApplication : Application() {
    /** Never cancelled; for work that must finish even when the activity goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var platform: Platform
        private set

    lateinit var settings: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        platform = androidPlatform(this, "$packageName.sessions")
        settings = SettingsRepository(platform.settingsStore, appScope)
    }

    /** Saves pending settings now (the process may be killed once in the background). */
    fun flushSettings() {
        appScope.launch {
            withContext(NonCancellable) { runCatching { settings.flush() } }
        }
    }
}

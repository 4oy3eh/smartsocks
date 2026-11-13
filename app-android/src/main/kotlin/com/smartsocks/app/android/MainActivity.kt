package com.smartsocks.app.android

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.smartsocks.app.ui.ChartRendering
import com.smartsocks.app.ui.LivePerf
import com.smartsocks.app.ui.SmartSocksApp

class MainActivity : ComponentActivity() {
    private val model: LiveViewModel by viewModels()

    /** Registered with the activity's creation, as result launchers must be. */
    private val deviceActions = ActivityDeviceActions(this) { model.controller.recheckDevices() }
    private val recordingPrompts = ActivityRecordingPrompts(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val platform = (application as SmartSocksApplication).platform
        val catalog = platform.catalog
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) applyDebugExtras()
        setContent {
            SmartSocksApp(
                model.settings,
                model.controller,
                catalog,
                deviceActions,
                recorder = model.recorder,
                sessions = model.sessions,
                sessionActions = platform.sessionActions,
                recordingPrompts = recordingPrompts,
            )
        }
    }

    /**
     * Development aids of debug builds, for measuring the chart on a device:
     * `am start -n com.smartsocks.app/.android.MainActivity --ei chartHairlineSteps 0 --ez perf true`.
     * `chartHairlineSteps` sets [ChartRendering.maxHairlineSteps]; `perf` logs [LivePerf] reports to logcat
     * (tag SmartSocksPerf) every few seconds.
     */
    private fun applyDebugExtras() {
        val steps = intent.getIntExtra("chartHairlineSteps", -1)
        if (steps >= 0) ChartRendering.maxHairlineSteps = steps
        if (intent.getBooleanExtra("perf", false)) {
            LivePerf.enabled = true
            val view = window.decorView
            view.postDelayed(
                object : Runnable {
                    override fun run() {
                        LivePerf.report()?.let { Log.i("SmartSocksPerf", it) }
                        if (!isDestroyed) view.postDelayed(this, PERF_REPORT_MILLIS)
                    }
                },
                PERF_REPORT_MILLIS,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // The user may have granted the permission, paired a sock or switched Bluetooth on elsewhere meanwhile.
        deviceActions.onStart()
        deviceActions.changed()
    }

    override fun onStop() {
        super.onStop()
        deviceActions.onStop()
        // The feet pause in the background unless a recording goes on there: SmartSocksApplication follows the
        // started activities (a configuration change does not count as leaving). The player pauses here.
        if (!isChangingConfigurations) model.sessions.player.value?.player?.pause()
        (application as SmartSocksApplication).flushSettings()
    }
}

private const val PERF_REPORT_MILLIS = 5_000L

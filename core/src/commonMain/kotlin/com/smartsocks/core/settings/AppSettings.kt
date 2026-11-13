package com.smartsocks.core.settings

import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.sim.SimMode

/** What a foot is connected to. */
sealed interface DeviceChoice {
    data object None : DeviceChoice

    /** The built-in simulator; [preloaded] simulates a leaky insole (see `SimConfig.preloadedInsole`). */
    data class Simulator(val mode: SimMode = SimMode.Walking, val preloaded: Boolean = false) : DeviceChoice

    /** A paired sock as a COM port (desktop). */
    data class SerialPort(val portName: String) : DeviceChoice

    /** A paired sock by its Bluetooth MAC address (Android RFCOMM); [name] is for display only. */
    data class Bluetooth(val address: String, val name: String) : DeviceChoice
}

enum class AppTheme { SYSTEM, LIGHT, DARK }

/** The main visualisation of the live screen. */
enum class LiveView { COP, CHART, NUMBERS }

/** How much history the live charts show. */
enum class ChartWindow(val seconds: Int) {
    SECONDS_5(5),
    SECONDS_10(10),
    SECONDS_30(30),
}

/** The live chart's choices: its time window and the foot whose six sensors it plots. */
data class ChartSettings(val window: ChartWindow = ChartWindow.SECONDS_10, val sensorFoot: Foot = Foot.LEFT)

/** Everything the app remembers between runs. Persisted by [SettingsRepository] in the [SettingsCodec] format. */
data class AppSettings(
    val left: DeviceChoice = DeviceChoice.Simulator(),
    val right: DeviceChoice = DeviceChoice.Simulator(),
    val stream: StreamSettings = StreamSettings(),
    /** All fields are user-tunable: contact thresholds, auto-zero (0 seconds disables it), COP smoothing. */
    val processor: ProcessorSettings = ProcessorSettings(),
    val theme: AppTheme = AppTheme.SYSTEM,
    val liveView: LiveView = LiveView.COP,
    val chart: ChartSettings = ChartSettings(),
    /** Connect to the remembered devices on start. */
    val autoConnect: Boolean = true,
) {
    fun device(foot: Foot): DeviceChoice = if (foot == Foot.LEFT) left else right

    fun withDevice(foot: Foot, choice: DeviceChoice): AppSettings =
        if (foot == Foot.LEFT) copy(left = choice) else copy(right = choice)
}

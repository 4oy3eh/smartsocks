package com.smartsocks.core.settings

import com.smartsocks.core.analytics.FootGains
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.SENSOR_COUNT
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

    /**
     * A recording played back as if it were a sock: [foot]'s packets of the raw dump of recording [sessionId] (any
     * foot may play either foot's packets); [loop]: start over at the end instead of disconnecting there.
     */
    data class Replay(val sessionId: String, val foot: Foot, val loop: Boolean = true) : DeviceChoice
}

/**
 * Which physical insole this is, for remembering things about it (its calibrations): the port or address in upper
 * case, never the display name; for the simulator only what changes its readings (preloaded or not); for a replay
 * the recording and the foot it plays.
 */
val DeviceChoice.identity: String
    get() = when (this) {
        DeviceChoice.None -> "none"
        is DeviceChoice.Simulator -> "simulator:$preloaded"
        is DeviceChoice.SerialPort -> "serial:${portName.uppercase()}"
        is DeviceChoice.Bluetooth -> "bluetooth:${address.uppercase()}"
        is DeviceChoice.Replay -> "replay:${foot.name}:$sessionId"
    }

/**
 * A pair of insoles as worn: the [identity] of the left foot's device and of the right one's. Balance gains only
 * mean something for the pair they were measured on: replacing one insole or swapping the sides is another pair.
 */
data class InsolePair(val left: String, val right: String) {
    /** One of the two is replay [sessionId]'s (either foot of it). */
    fun replays(sessionId: String): Boolean = left.isReplayOf(sessionId) || right.isReplayOf(sessionId)

    companion object {
        fun of(left: DeviceChoice, right: DeviceChoice) = InsolePair(left.identity, right.identity)

        private fun String.isReplayOf(sessionId: String) =
            Foot.entries.any { this == DeviceChoice.Replay(sessionId, it).identity }
    }
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

/**
 * A non-empty set of sensor channels, as a bit mask: bit `i` is channel `i + 1` (index `i` of the load arrays).
 * Never empty: [toggle] keeps the last selected channel.
 */
data class SensorSelection(val mask: Int) {
    init {
        require(mask in 1..ALL_MASK) { "Not a sensor selection: $mask" }
    }

    /** Channel index (0-based) [index] is selected. */
    operator fun contains(index: Int): Boolean = index in 0 until SENSOR_COUNT && (mask shr index) and 1 == 1

    val isAll: Boolean get() = mask == ALL_MASK

    /** Selected channel indices (0-based), ascending. */
    val indices: List<Int> get() = (0 until SENSOR_COUNT).filter { it in this }

    /** Channel [index] (0-based) flipped; the only selected channel stays selected. */
    fun toggle(index: Int): SensorSelection {
        require(index in 0 until SENSOR_COUNT)
        val m = mask xor (1 shl index)
        return if (m == 0) this else SensorSelection(m)
    }

    /** Channel numbers, "1+3+5"; "1–6" for all. */
    fun numbersText(): String = if (isAll) "1–$SENSOR_COUNT" else indices.joinToString("+") { "${it + 1}" }

    companion object {
        const val ALL_MASK = (1 shl SENSOR_COUNT) - 1

        val All = SensorSelection(ALL_MASK)

        /** From channel indices (0-based); out-of-range ones are ignored, none at all means [All]. */
        fun of(indices: Iterable<Int>): SensorSelection {
            var m = 0
            for (i in indices) if (i in 0 until SENSOR_COUNT) m = m or (1 shl i)
            return if (m == 0) All else SensorSelection(m)
        }
    }
}

/** What the chart's load panel plots per foot. */
enum class ChartLoad {
    /** The foot's total load. */
    TOTAL,

    /** The sum of the selected sensors' loads ([ChartSettings.sensors]). */
    SELECTED,
}

/**
 * The live chart's choices: its time window, the foot whose sensors it plots, which of them ([sensors], also summed
 * by the load panel in [ChartLoad.SELECTED] mode) and what the load panel shows.
 */
data class ChartSettings(
    val window: ChartWindow = ChartWindow.SECONDS_10,
    val sensorFoot: Foot = Foot.LEFT,
    val sensors: SensorSelection = SensorSelection.All,
    val load: ChartLoad = ChartLoad.TOTAL,
)

/** How sessions are recorded. [saveRaw]: also keep every undecoded packet in a second file, to re-process later. */
data class RecordingSettings(val saveRaw: Boolean = true)

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
    val recording: RecordingSettings = RecordingSettings(),
    /** Connect to the remembered devices on start. */
    val autoConnect: Boolean = true,
    /**
     * Retry a foot whose link drops or fails to open while it is meant to be connected, with a growing wait in
     * between (see `ReconnectBackoff` in the app). Off: such a foot stays disconnected until connected again.
     */
    val autoReconnect: Boolean = true,
    /**
     * The balance gains of every pair of insoles calibrated so far (see [FootGains], `FootProcessor.gain`), oldest
     * calibration first, at most [MAX_BALANCE_PAIRS]. A pair with an entry is calibrated (also with neutral gains:
     * then it needed no correction); one without is not.
     */
    val balanceGains: Map<InsolePair, FootGains> = emptyMap(),
) {

    fun device(foot: Foot): DeviceChoice = if (foot == Foot.LEFT) left else right

    fun withDevice(foot: Foot, choice: DeviceChoice): AppSettings =
        if (foot == Foot.LEFT) copy(left = choice) else copy(right = choice)

    /** The feet's current pair of insoles. */
    val currentPair: InsolePair get() = InsolePair.of(left, right)

    /** The balance gains stored for the current pair; null if it is not calibrated. */
    val currentGains: FootGains? get() = balanceGains[currentPair]

    /**
     * Stores [gains] for the current pair (null removes them), as its newest entry; beyond [MAX_BALANCE_PAIRS] the
     * oldest calibrations are dropped.
     */
    fun withCurrentGains(gains: FootGains?): AppSettings {
        val pair = currentPair
        val rest = balanceGains - pair
        if (gains == null) return if (pair in balanceGains) copy(balanceGains = rest) else this
        val kept = rest.entries.toList().takeLast(MAX_BALANCE_PAIRS - 1)
        return copy(
            balanceGains = LinkedHashMap<InsolePair, FootGains>().apply {
                for ((k, v) in kept) put(k, v)
                put(pair, gains)
            },
        )
    }

    /** Without the gains of pairs with a replay of recording [sessionId] (it was deleted). */
    fun withoutReplayGains(sessionId: String): AppSettings {
        if (balanceGains.keys.none { it.replays(sessionId) }) return this
        return copy(balanceGains = balanceGains.filterKeys { !it.replays(sessionId) })
    }

    companion object {
        /** How many calibrated pairs are remembered. */
        const val MAX_BALANCE_PAIRS = 32
    }
}

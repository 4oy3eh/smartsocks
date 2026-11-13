package com.smartsocks.app.settings

import com.smartsocks.core.analytics.ProcessorSettings
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The user-tunable [ProcessorSettings] fields as the settings screen shows them: a slider range inside what core
 * accepts, a step, a unit. [with] is the only way the UI changes them, so the result is always valid.
 */
enum class ProcessorField(
    val title: String,
    val unit: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val description: String,
) {
    ContactOn(
        "Contact on",
        "µS",
        1.0,
        200.0,
        1.0,
        "Total load at which a lifted foot counts as touching down.",
    ),
    ContactOff(
        "Contact off",
        "µS",
        0.0,
        200.0,
        1.0,
        "Total load below which the foot counts as lifted again. At most the on level.",
    ),
    AutoZeroWindow(
        "Auto-zero window",
        "s",
        0.0,
        10.0,
        0.5,
        "Without a calibration, the zero point is learned over this window while walking. 0 turns auto-zero off.",
    ),
    AutoZeroCap(
        "Auto-zero cap",
        "µS",
        0.0,
        100.0,
        1.0,
        "The most auto-zero may subtract per sensor: removes insole preload, never body weight.",
    ),
    SwingFraction(
        "Swing detection",
        "%",
        0.0,
        1.0,
        0.05,
        "Auto-zero learns only when the window's lowest total load is below this share of its highest (a swing).",
    ),
    CopCutoff(
        "COP smoothing",
        "Hz",
        0.1,
        10.0,
        0.1,
        "Cutoff of the COP filter at rest: lower is smoother, higher follows small movements sooner.",
    ),
    CopBeta(
        "COP speed response",
        "",
        0.0,
        1.0,
        0.01,
        "How much the filter opens up on fast movements: higher means less lag, but more jitter.",
    ),
    ;

    fun valueIn(settings: ProcessorSettings): Double = when (this) {
        ContactOn -> settings.contactOnThreshold
        ContactOff -> settings.contactOffThreshold
        AutoZeroWindow -> settings.autoZeroSeconds
        AutoZeroCap -> settings.autoZeroMaxMicroSiemens
        SwingFraction -> settings.autoZeroSwingFraction
        CopCutoff -> settings.copMinCutoffHz
        CopBeta -> settings.copBeta
    }

    /** [value] snapped to [step] and clamped to [min]..[max]. */
    fun snap(value: Double): Double {
        if (value.isNaN()) return min
        val snapped = min + ((value.coerceIn(min, max) - min) / step).roundToLong() * step
        // Without the float noise of the multiplication (0.1 * 3 = 0.30000000000000004).
        return ((snapped * 1e9).roundToLong() / 1e9).coerceIn(min, max)
    }

    /**
     * [settings] with this field set to [value] (snapped and clamped). The on/off hysteresis stays ordered: an on
     * level below the off level drags the off level down with it, an off level is capped at the on level.
     */
    fun with(settings: ProcessorSettings, value: Double): ProcessorSettings {
        val v = snap(value)
        return when (this) {
            ContactOn -> settings.copy(
                contactOnThreshold = v,
                contactOffThreshold = minOf(settings.contactOffThreshold, v),
            )

            ContactOff -> settings.copy(contactOffThreshold = minOf(v, settings.contactOnThreshold))

            AutoZeroWindow -> settings.copy(autoZeroSeconds = v)

            AutoZeroCap -> settings.copy(autoZeroMaxMicroSiemens = v)

            SwingFraction -> settings.copy(autoZeroSwingFraction = v)

            CopCutoff -> settings.copy(copMinCutoffHz = v)

            CopBeta -> settings.copy(copBeta = v)
        }
    }

    /**
     * The upper end of the slider in [settings]: the off level cannot go above the on level. Always above [min],
     * so the slider has a range to work with.
     */
    fun maxIn(settings: ProcessorSettings): Double =
        if (this == ContactOff) settings.contactOnThreshold.coerceIn(min + step, max) else max

    /** "20 µS", "4.0 s", "Off", "30 %", "1.5 Hz", "0.05". */
    fun format(value: Double): String = when (this) {
        ContactOn, ContactOff, AutoZeroCap -> "${value.roundToLong()} $unit"
        AutoZeroWindow -> if (value <= 0.0) "Off" else "${fixed(value, 1)} $unit"
        SwingFraction -> "${(value * 100).roundToLong()} $unit"
        CopCutoff -> "${fixed(value, 1)} $unit"
        CopBeta -> fixed(value, 2)
    }

    /** Whether [settings] differs from the defaults in this field. */
    fun isChanged(settings: ProcessorSettings): Boolean = valueIn(settings) != valueIn(ProcessorSettings())
}

/** [value] with exactly [decimals] decimals (no platform formatter in common code). */
internal fun fixed(value: Double, decimals: Int): String {
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val scaled = (abs(value) * scale).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    val whole = scaled / scale
    if (decimals == 0) return "$sign$whole"
    val frac = (scaled % scale).toString().padStart(decimals, '0')
    return "$sign$whole.$frac"
}

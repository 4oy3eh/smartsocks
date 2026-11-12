package com.smartsocks.core.analytics

import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.SENSOR_COUNT

/** Everything the UI needs to draw one foot at one moment. Immutable snapshot. */
class FootState(
    val foot: Foot,
    val timestampNanos: Long,
    /** Raw resistance per sensor. May contain [Double.POSITIVE_INFINITY]: open circuit or over the range. */
    val resistanceOhms: DoubleArray,
    /** Conductance above baseline per sensor, µS (≈ proportional to force). */
    val loads: DoubleArray,
    val totalLoad: Double,
    /** Share of the foot's total load carried by each sensor, 0..1. All zero when not in contact. */
    val shares: DoubleArray,
    /** Smoothed COP; null when the foot is in the air. */
    val cop: Point?,
    /** Unsmoothed COP, for recording. */
    val rawCop: Point?,
    /** Where the baseline that [loads] are measured from came from. */
    val baselineSource: BaselineSource,
) {
    val inContact: Boolean get() = cop != null

    /** An explicit calibration is in effect. */
    val calibrated: Boolean get() = baselineSource == BaselineSource.CALIBRATED
}

enum class BaselineSource {
    /** No baseline: raw conductance counts as load (auto-zero disabled and no calibration). */
    NONE,

    /** Rolling minimum of the last few seconds, see [ProcessorSettings.autoZeroSeconds]. */
    AUTO,

    /** Explicit calibration with the foot lifted (or [FootProcessor.setBaseline]); overrides [AUTO]. */
    CALIBRATED,
}

data class ProcessorSettings(
    /** Total load (µS over baseline) at or above which a lifted foot is considered to have touched down. */
    val contactOnThreshold: Double = 20.0,
    /** Total load below which a foot in contact is considered lifted again. Hysteresis: keep it below the on level. */
    val contactOffThreshold: Double = 10.0,
    /**
     * Without an explicit calibration the baseline is the per-sensor minimum conductance over this many seconds
     * (while walking the swing phase unloads the sensors every stride). 0 disables auto-zero. Caveat: standing
     * still for longer than this absorbs the standing load into the baseline until the next step; calibrate for
     * static measurements.
     */
    val autoZeroSeconds: Double = 4.0,
    val copMinCutoffHz: Double = 1.5,
    val copBeta: Double = 0.05,
) {
    init {
        require(contactOffThreshold <= contactOnThreshold) { "off threshold must not exceed the on threshold" }
        require(autoZeroSeconds >= 0.0)
    }
}

/** Turns raw frames of one foot into [FootState]s: baseline, loads, COP, smoothing. Single-threaded. */
class FootProcessor(
    val foot: Foot,
    layout: SensorLayout = SensorLayout.Legacy,
    private val settings: ProcessorSettings = ProcessorSettings(),
) {
    private val positions = layout.positions(foot)
    private val filterX = OneEuroFilter(settings.copMinCutoffHz, settings.copBeta)
    private val filterY = OneEuroFilter(settings.copMinCutoffHz, settings.copBeta)
    private var calibrator: BaselineCalibrator? = null
    private val rollingMin = if (settings.autoZeroSeconds > 0.0) {
        RollingMinimum((settings.autoZeroSeconds * 1e9).toLong())
    } else {
        null
    }
    private val conductance = DoubleArray(SENSOR_COUNT)
    private var contact = false

    var baseline: Baseline? = null
        private set

    /** 0..1 while calibrating, null otherwise. */
    val calibrationProgress: Double? get() = calibrator?.progress

    /** Start collecting an unloaded baseline; the foot must be lifted for the next [samples] frames. */
    fun startCalibration(samples: Int) {
        require(samples > 0) { "samples must be positive" }
        calibrator = BaselineCalibrator(samples)
    }

    /** Aborts a calibration in progress; a completed [baseline] is kept. */
    fun cancelCalibration() {
        calibrator = null
    }

    /** Sets (or with null clears) the explicit baseline; without one the auto-zero baseline applies. */
    fun setBaseline(value: Baseline?) {
        baseline = value
        calibrator = null
    }

    fun process(frame: SensorFrame): FootState {
        require(frame.foot == foot) { "Frame for ${frame.foot} sent to $foot processor" }
        calibrator?.let { c ->
            if (c.add(frame)) {
                baseline = c.result()
                calibrator = null
            }
        }

        val explicit = baseline
        val source = when {
            explicit != null -> BaselineSource.CALIBRATED
            rollingMin != null -> BaselineSource.AUTO
            else -> BaselineSource.NONE
        }
        if (rollingMin != null) {
            for (i in 0 until SENSOR_COUNT) conductance[i] = microSiemens(frame.resistanceOhms[i])
            rollingMin.add(frame.timestampNanos, conductance)
        }
        val reference = explicit?.unloadedMicroSiemens ?: rollingMin?.values
        val loads = LoadModel.loadsAbove(frame, reference)
        val total = loads.sum()
        contact = total > 0.0 && total >= if (contact) settings.contactOffThreshold else settings.contactOnThreshold
        val cop = if (contact) CenterOfPressure.compute(loads, positions, 0.0) else null
        if (cop == null) contact = false
        val shares = DoubleArray(SENSOR_COUNT) { if (cop != null) loads[it] / total else 0.0 }

        val smoothed = if (cop == null) {
            filterX.reset()
            filterY.reset()
            null
        } else {
            Point(
                filterX.filter(cop.position.x, frame.timestampNanos),
                filterY.filter(cop.position.y, frame.timestampNanos),
            )
        }

        return FootState(
            foot = foot,
            timestampNanos = frame.timestampNanos,
            resistanceOhms = frame.resistanceOhms,
            loads = loads,
            totalLoad = total,
            shares = shares,
            cop = smoothed,
            rawCop = cop?.position,
            baselineSource = source,
        )
    }
}

data class Balance(
    /** Left share of the combined load, 0..1. */
    val left: Double,
    val right: Double,
    /** COP of both feet together, in a frame where the feet sit at x = ∓[stanceHalfWidth]. */
    val combinedCop: Point?,
)

object BalanceCalculator {
    /**
     * A foot that is not in contact counts as zero load (its sub-threshold noise must not skew the split); null
     * when neither foot is in contact. Assumes both insoles have similar sensitivity; per-insole gain calibration
     * with a known weight would make the left/right split exact.
     */
    fun compute(left: FootState?, right: FootState?, stanceHalfWidth: Double = 1.0): Balance? {
        val l = if (left?.inContact == true) left.totalLoad else 0.0
        val r = if (right?.inContact == true) right.totalLoad else 0.0
        val total = l + r
        if (total <= 0.0) return null

        val leftCop = left?.cop?.let { Point(it.x - stanceHalfWidth, it.y) }
        val rightCop = right?.cop?.let { Point(it.x + stanceHalfWidth, it.y) }
        val combined = when {
            leftCop != null && rightCop != null -> Point(
                (leftCop.x * l + rightCop.x * r) / total,
                (leftCop.y * l + rightCop.y * r) / total,
            )

            else -> leftCop ?: rightCop
        }
        return Balance(l / total, r / total, combined)
    }
}

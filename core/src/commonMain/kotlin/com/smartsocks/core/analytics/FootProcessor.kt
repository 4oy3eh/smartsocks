package com.smartsocks.core.analytics

import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.SENSOR_COUNT

/**
 * The part of one foot's sample that [BalanceCalculator] works on. Implemented by the live [FootState] and by
 * recorded playback samples, so live and replayed balance come from the same code.
 */
interface FootLoad {
    /** Conductance above baseline summed over the sensors, µS. */
    val totalLoad: Double

    /** Smoothed COP; null when the foot is in the air. */
    val cop: Point?

    val inContact: Boolean
}

/** Everything the UI needs to draw one foot at one moment. Immutable snapshot. */
class FootState(
    val foot: Foot,
    val timestampNanos: Long,
    /** Raw resistance per sensor. May contain [Double.POSITIVE_INFINITY]: open circuit or over the range. */
    val resistanceOhms: DoubleArray,
    /** Conductance above baseline per sensor, µS (≈ proportional to force). */
    val loads: DoubleArray,
    override val totalLoad: Double,
    /** Share of the foot's total load carried by each sensor, 0..1. All zero when not in contact. */
    val shares: DoubleArray,
    /** Smoothed COP; null when the foot is in the air. */
    override val cop: Point?,
    /** Unsmoothed COP, for recording. */
    val rawCop: Point?,
    /** Where the baseline that [loads] are measured from came from. */
    val baselineSource: BaselineSource,
) : FootLoad {
    override val inContact: Boolean get() = cop != null

    /** An explicit calibration is in effect. */
    val calibrated: Boolean get() = baselineSource == BaselineSource.CALIBRATED
}

enum class BaselineSource {
    /**
     * No baseline: raw conductance counts as load (auto-zero disabled, or enabled but it has not seen the foot
     * lifted yet, and no calibration).
     */
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
     * (while walking the swing phase unloads the sensors every stride). 0 disables auto-zero.
     *
     * The baseline is only updated while the window contains a swing-like phase: the smallest total raw
     * conductance of the window is below [autoZeroSwingFraction] of its largest (foot lifted or heavily unloaded),
     * judged on the raw signal, independently of the baseline. Standing still keeps the baseline learned so far
     * (initially none), so quiet standing is never distorted. Consequence: a preloaded insole that is never
     * lifted stays uncalibrated, [FootState.baselineSource] stays [BaselineSource.NONE] and the UI should ask
     * for an explicit calibration.
     */
    val autoZeroSeconds: Double = 4.0,
    /**
     * Upper bound for what auto-zero may subtract per sensor, in µS. Auto-zero exists to remove leakage and insole
     * preload (a few µS, at most tens of µS), not body weight (hundreds of µS). Without a cap, standing still for
     * longer than [autoZeroSeconds] would make the standing load the "baseline" and the foot would read as lifted.
     */
    val autoZeroMaxMicroSiemens: Double = 30.0,
    /** A window counts as containing a swing when its minimal total conductance is below this share of its maximum. */
    val autoZeroSwingFraction: Double = 0.3,
    val copMinCutoffHz: Double = 1.5,
    val copBeta: Double = 0.05,
) {
    init {
        require(contactOffThreshold <= contactOnThreshold) { "off threshold must not exceed the on threshold" }
        require(autoZeroSeconds >= 0.0)
        require(autoZeroMaxMicroSiemens >= 0.0)
        require(autoZeroSwingFraction in 0.0..1.0)
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
    private val autoBaseline = DoubleArray(SENSOR_COUNT)
    private var autoLearned = false
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
        if (rollingMin != null) {
            for (i in 0 until SENSOR_COUNT) conductance[i] = microSiemens(frame.resistanceOhms[i])
            rollingMin.add(frame.timestampNanos, conductance)
            if (rollingMin.totalMin < settings.autoZeroSwingFraction * rollingMin.totalMax) {
                for (i in 0 until SENSOR_COUNT) {
                    autoBaseline[i] = minOf(rollingMin.values[i], settings.autoZeroMaxMicroSiemens)
                }
                autoLearned = true
            }
        }
        val source = when {
            explicit != null -> BaselineSource.CALIBRATED
            autoLearned -> BaselineSource.AUTO
            else -> BaselineSource.NONE
        }
        val reference = explicit?.unloadedMicroSiemens ?: if (autoLearned) autoBaseline else null
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
    fun compute(left: FootLoad?, right: FootLoad?, stanceHalfWidth: Double = 1.0): Balance? {
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

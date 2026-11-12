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
    val calibrated: Boolean,
) {
    val inContact: Boolean get() = cop != null
}

data class ProcessorSettings(
    /** Total load (µS) below which the foot is considered lifted. */
    val contactThreshold: Double = 20.0,
    val copMinCutoffHz: Double = 1.5,
    val copBeta: Double = 0.05,
)

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

    var baseline: Baseline? = null
        private set

    /** 0..1 while calibrating, null otherwise. */
    val calibrationProgress: Double? get() = calibrator?.progress

    /** Start collecting an unloaded baseline; the foot must be lifted for the next [samples] frames. */
    fun startCalibration(samples: Int) {
        calibrator = BaselineCalibrator(samples)
    }

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

        val loads = LoadModel.loads(frame, baseline)
        val cop = CenterOfPressure.compute(loads, positions, settings.contactThreshold)
        val total = loads.sum()
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
            calibrated = baseline != null,
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
     * Assumes both insoles have similar sensitivity; per-insole gain calibration with a known weight would make
     * the left/right split exact.
     */
    fun compute(left: FootState?, right: FootState?, stanceHalfWidth: Double = 1.0): Balance? {
        val l = left?.totalLoad ?: 0.0
        val r = right?.totalLoad ?: 0.0
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

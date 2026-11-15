package com.smartsocks.core.analytics

import com.smartsocks.core.model.Foot
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The balance gains of a pair of insoles: what each foot's loads are multiplied by ([FootProcessor.gain]) so that
 * both insoles read the same for the same force. Normalised to keep the overall level: `√(left · right) = 1`, so
 * neither foot is the reference and the sum of both feet's loads stays about what it was. They only mean something
 * for the pair they were measured on.
 */
data class FootGains(val left: Double = 1.0, val right: Double = 1.0) {
    init {
        require(left.isFinite() && left > 0.0 && right.isFinite() && right > 0.0) {
            "Gains must be positive and finite: $left, $right"
        }
    }

    fun of(foot: Foot): Double = if (foot == Foot.LEFT) left else right

    /** No correction: both 1.0. */
    val isNeutral: Boolean get() = left == 1.0 && right == 1.0

    companion object {
        val NEUTRAL = FootGains()

        /**
         * The gains for a right insole that reads [rightToLeft] times what the left one reads for the same force:
         * `left = √ρ`, `right = 1/√ρ`, so `right / left = 1/ρ` evens them out and `left · right = 1`.
         */
        fun forRatio(rightToLeft: Double): FootGains {
            require(rightToLeft.isFinite() && rightToLeft > 0.0) { "Ratio must be positive: $rightToLeft" }
            val root = sqrt(rightToLeft)
            return FootGains(root, 1 / root)
        }
    }
}

/** What a balance calibration accepts; see [BalanceCalibrator]. */
data class BalanceLimits(
    /** The first part of the measurement, discarded: the person only starts to shift after pressing the button. */
    val leadInNanos: Long = 1_500_000_000L,
    /** The swing is judged on the mean weight share of sub-windows this long (so noise does not count as swing). */
    val subWindowNanos: Long = 500_000_000L,
    /** A sample of one foot is paired with the newest one of the other foot if that is at most this old. */
    val pairingNanos: Long = 100_000_000L,
    /**
     * The left foot's raw load must fall when the right one's rises: their correlation at most this. A larger value
     * means the total did not stay constant (bouncing, leaning on something, a foot lifted) or nothing moved.
     */
    val maxCorrelation: Double = -0.9,
    /** Smallest range (max − min) of the left foot's share of the weight over the sub-windows. */
    val minSwing: Double = 0.15,
    /** Smallest mean raw total load per foot, µS: below it the foot is barely loaded and the ratio is noise. */
    val minMeanLoad: Double = 50.0,
    /** Largest plausible sensitivity ratio between the insoles, either way round. */
    val maxRatio: Double = 2.0,
    /** A ratio closer to 1 than this needs no correction: the result is [FootGains.NEUTRAL]. */
    val neutralBand: Double = 0.01,
    /** Fewer pairs than this (after the lead-in) are too few to judge. */
    val minPairs: Int = 50,
) {
    init {
        require(leadInNanos >= 0 && subWindowNanos > 0 && pairingNanos > 0)
        require(maxCorrelation in -1.0..0.0)
        require(minSwing >= 0.0 && minMeanLoad >= 0.0 && maxRatio > 1.0 && neutralBand >= 0.0 && minPairs > 1)
    }
}

/** Why a balance calibration was refused. */
enum class BalanceRejection {
    /** A foot was not on the ground at some point. */
    NOT_IN_CONTACT,

    /** A foot had no zero point (no calibration, auto-zero not learned yet). */
    NO_BASELINE,

    /** A foot's zero point changed during the measurement (e.g. auto-zero). */
    BASELINE_CHANGED,

    /** Too few samples of both feet arrived. */
    TOO_FEW_SAMPLES,

    /** A foot carried too little load, see [BalanceLimits.minMeanLoad]. */
    LOW_LOAD,

    /** The weight was not shifted far enough, see [BalanceLimits.minSwing]. */
    NO_SWING,

    /** The feet's loads did not move against each other, see [BalanceLimits.maxCorrelation]. */
    NOT_ANTICORRELATED,

    /** The insoles differ by more than [BalanceLimits.maxRatio]. */
    IMPLAUSIBLE_RATIO,
}

sealed interface BalanceResult {
    /**
     * [gains] even out the insoles: the right one read [rightToLeft] times what the left one read for the same force
     * (1.12: 12 % more). [FootGains.NEUTRAL] if they differ by less than [BalanceLimits.neutralBand].
     */
    data class Calibrated(
        val gains: FootGains,
        val rightToLeft: Double,
        val correlation: Double = -1.0,
        val swing: Double = 0.0,
    ) : BalanceResult

    /** [message] is for the user: one sentence, no final full stop. */
    data class Rejected(val reason: BalanceRejection, val message: String) : BalanceResult
}

/**
 * The paired raw total loads (µS, before any gain) of a weight-shift measurement: their means, standard deviations
 * and correlation, and the [swing] of the left foot's weight share.
 */
data class BalanceStats(
    val pairs: Int,
    val meanLeft: Double,
    val meanRight: Double,
    val sdLeft: Double,
    val sdRight: Double,
    val correlation: Double,
    val swing: Double,
)

object BalanceGain {
    /**
     * Decides a weight-shift calibration from its [stats] (contact and zero points are checked while measuring, see
     * [BalanceCalibrator]).
     *
     * With the person's total weight `W = F_L + F_R` constant, the raw loads are `L = s_L·F_L` and `R = s_R·(W − F_L)`,
     * so `σ(L) = s_L·σ(F)` and `σ(R) = s_R·σ(F)`: the ratio of the standard deviations is the ratio of the insoles'
     * sensitivities, **whatever the person's average stance** (a habitual 60/40 stays in the data). It is used rather
     * than the regression slope `−cov(L, R) / var(R)`, which also estimates it but is pulled towards 0 by noise on R
     * and is not symmetric (swapping the feet would not give the reciprocal); with the required correlation of −0.9
     * or better both agree closely.
     */
    fun evaluate(stats: BalanceStats, limits: BalanceLimits = BalanceLimits()): BalanceResult {
        if (stats.pairs < limits.minPairs) {
            return reject(BalanceRejection.TOO_FEW_SAMPLES, "Too few samples of both feet arrived: try again")
        }
        val low = when {
            stats.meanLeft < limits.minMeanLoad -> "left"
            stats.meanRight < limits.minMeanLoad -> "right"
            else -> null
        }
        if (low != null) {
            return reject(
                BalanceRejection.LOW_LOAD,
                "Too little load on the $low foot: stand with your full weight on both feet",
            )
        }
        if (stats.swing < limits.minSwing) {
            return reject(
                BalanceRejection.NO_SWING,
                "Shift your weight further: slowly from one foot to the other and back, several times",
            )
        }
        if (!(stats.correlation <= limits.maxCorrelation) || stats.sdRight <= 0.0 || stats.sdLeft <= 0.0) {
            return reject(
                BalanceRejection.NOT_ANTICORRELATED,
                "Shift your weight smoothly from foot to foot, without bouncing or leaning on anything",
            )
        }
        val rightToLeft = stats.sdRight / stats.sdLeft
        if (rightToLeft > limits.maxRatio || rightToLeft < 1 / limits.maxRatio) {
            val factor = if (rightToLeft >= 1) rightToLeft else 1 / rightToLeft
            val stronger = if (rightToLeft >= 1) "right" else "left"
            return reject(
                BalanceRejection.IMPLAUSIBLE_RATIO,
                "The $stronger insole reads ${formatFactor(factor)} times the other: check the insoles",
            )
        }
        val gains = if (abs(rightToLeft - 1) <
            limits.neutralBand
        ) {
            FootGains.NEUTRAL
        } else {
            FootGains.forRatio(rightToLeft)
        }
        return BalanceResult.Calibrated(gains, rightToLeft, stats.correlation, stats.swing)
    }

    internal fun reject(reason: BalanceRejection, message: String) = BalanceResult.Rejected(reason, message)

    private fun formatFactor(f: Double): String {
        val tenths = (f * 10).roundToInt()
        return "${tenths / 10}.${tenths % 10}"
    }
}

/**
 * Collects a weight-shift balance calibration: both feet on the ground, the person slowly shifts the weight from one
 * foot to the other for [durationNanos] (on the samples' own clock, which both feet's sessions must share). Feed it
 * every [FootState] of both feet, from one thread; it works on the raw total loads (`totalLoad / gain`), so the gains
 * in effect (even one changed meanwhile) do not matter and the result replaces them.
 *
 * The first [BalanceLimits.leadInNanos] are discarded. After that every sample is paired with the newest sample of
 * the other foot (if fresh), and the pairs are accumulated (Welford; nothing is allocated per sample). The
 * measurement is complete once both feet delivered a sample [durationNanos] after the first one, or at once when it
 * can no longer succeed: a foot leaves the ground, has no zero point ([BaselineSource.NONE]) or its zero point
 * changes ([FootState.baselineVersion]). Then [result] decides, see [BalanceGain.evaluate].
 */
class BalanceCalibrator(private val durationNanos: Long, private val limits: BalanceLimits = BalanceLimits()) {
    init {
        require(durationNanos > limits.leadInNanos) { "duration must be longer than the lead-in" }
    }

    private val footCount = Foot.entries.size
    private val lastNanos = LongArray(footCount) { NONE }
    private val lastRaw = DoubleArray(footCount)
    private val versions = IntArray(footCount)
    private var startNanos = NONE
    private var failure: BalanceResult.Rejected? = null

    // Pair statistics (Welford).
    private var n = 0
    private var meanL = 0.0
    private var meanR = 0.0
    private var m2L = 0.0
    private var m2R = 0.0
    private var coMoment = 0.0

    // Sub-windows of the left share.
    private var windowId = Long.MIN_VALUE
    private var windowSum = 0.0
    private var windowCount = 0
    private var minShare = Double.POSITIVE_INFINITY
    private var maxShare = Double.NEGATIVE_INFINITY

    /** 0..1: the share of the duration both feet have covered so far. */
    val progress: Double
        get() {
            if (startNanos == NONE) return 0.0
            var least = Long.MAX_VALUE
            for (t in lastNanos) least = minOf(least, if (t == NONE) startNanos else t)
            return ((least - startNanos).toDouble() / durationNanos).coerceIn(0.0, 1.0)
        }

    /** The outcome is already decided (a refusal), before the end. */
    val failedEarly: Boolean get() = failure != null

    val isComplete: Boolean
        get() = failedEarly || (startNanos != NONE && lastNanos.all { it != NONE && it - startNanos >= durationNanos })

    /** Adds one sample; returns [isComplete]. */
    fun add(state: FootState): Boolean {
        if (isComplete) return true
        val t = state.timestampNanos
        if (startNanos == NONE) startNanos = t
        val i = state.foot.ordinal
        val first = lastNanos[i] == NONE
        val side = state.foot.name.lowercase()
        when {
            !state.inContact -> fail(
                BalanceRejection.NOT_IN_CONTACT,
                "Keep both feet on the ground: the $side foot was lifted",
            )

            state.baselineSource == BaselineSource.NONE -> fail(BalanceRejection.NO_BASELINE, NO_BASELINE_MESSAGE)

            !first && state.baselineVersion != versions[i] -> fail(
                BalanceRejection.BASELINE_CHANGED,
                "The $side foot's zero point changed during the measurement (auto-zero): $ZERO_POINT_FIX",
            )
        }
        if (failure != null) return true
        versions[i] = state.baselineVersion
        lastNanos[i] = maxOf(lastNanos[i], t)
        lastRaw[i] = state.totalLoad / state.gain
        val other = 1 - i
        if (t - startNanos >= limits.leadInNanos && lastNanos[other] != NONE &&
            t - lastNanos[other] <= limits.pairingNanos
        ) {
            addPair(t, lastRaw[Foot.LEFT.ordinal], lastRaw[Foot.RIGHT.ordinal])
        }
        return isComplete
    }

    private fun addPair(t: Long, l: Double, r: Double) {
        n++
        val dl = l - meanL
        val dr = r - meanR
        meanL += dl / n
        meanR += dr / n
        m2L += dl * (l - meanL)
        m2R += dr * (r - meanR)
        coMoment += dl * (r - meanR)

        val id = (t - startNanos - limits.leadInNanos).floorDiv(limits.subWindowNanos)
        if (id != windowId) {
            closeWindow()
            windowId = id
        }
        val total = l + r
        if (total > 0.0) {
            windowSum += l / total
            windowCount++
        }
    }

    private fun closeWindow() {
        if (windowCount == 0) return
        val mean = windowSum / windowCount
        if (mean < minShare) minShare = mean
        if (mean > maxShare) maxShare = mean
        windowSum = 0.0
        windowCount = 0
    }

    private fun fail(reason: BalanceRejection, message: String) {
        failure = BalanceResult.Rejected(reason, message)
    }

    /** The statistics so far (closing the current sub-window). */
    fun stats(): BalanceStats {
        closeWindow()
        val sdL = if (n > 1) sqrt(m2L / n) else 0.0
        val sdR = if (n > 1) sqrt(m2R / n) else 0.0
        val corr = if (sdL > 0.0 && sdR > 0.0) coMoment / n / (sdL * sdR) else 0.0
        val swing = if (maxShare >= minShare) maxShare - minShare else 0.0
        return BalanceStats(n, meanL, meanR, sdL, sdR, corr, swing)
    }

    /** The outcome; call once [isComplete] (earlier it judges what has been seen so far). */
    fun result(): BalanceResult = failure ?: BalanceGain.evaluate(stats(), limits)

    companion object {
        const val NO_BASELINE_MESSAGE =
            "Calibrate the zero point or lift each foot once first: the balance needs both feet's zero points"

        /** What to do when auto-zero gets in the way: an explicit zero point does not move. */
        const val ZERO_POINT_FIX = "calibrate the zero point of both feet first (above), then try again"

        private const val NONE = Long.MIN_VALUE
    }
}

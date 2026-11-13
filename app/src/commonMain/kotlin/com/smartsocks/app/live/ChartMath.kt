package com.smartsocks.app.live

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** Round axis numbers: scale maxima and tick steps. */
object NiceNumbers {
    private val maxima = floatArrayOf(1f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f, 8f, 10f)
    private val steps = floatArrayOf(1f, 2f, 2.5f, 5f, 10f)

    /** The smallest of 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8 × 10ⁿ that is ≥ [value] (> 0). */
    fun ceilMax(value: Float): Float = ceilIn(maxima, value)

    /** The smallest of 1, 2, 2.5, 5 × 10ⁿ that is ≥ [value] (> 0). */
    fun ceilStep(value: Float): Float = ceilIn(steps, value)

    /** Tick spacing for an axis from 0 to [max]: at most about [maxTicks] intervals (fewer on short plots). */
    fun tickStep(max: Float, maxTicks: Int = 5): Float = ceilStep(max / maxTicks.coerceAtLeast(1))

    private fun ceilIn(candidates: FloatArray, value: Float): Float {
        require(value > 0f && value.isFinite()) { "value must be positive: $value" }
        val decade = 10.0.pow(floor(log10(value.toDouble()))).toFloat()
        for (c in candidates) {
            val v = c * decade
            if (v >= value * (1 - 1e-6f)) return v
        }
        return candidates.last() * decade
    }
}

/**
 * An automatic y-axis maximum with hysteresis, so the axis does not jitter with every step: it grows at once when the
 * data (plus [HEADROOM]) no longer fits, but shrinks only after the data has fitted a scale at most [SHRINK_FRACTION]
 * of the current one for [shrinkDelayNanos], and never below [floor] (noise of an unloaded foot stays small). Values
 * are [NiceNumbers.ceilMax] steps. Not thread-safe.
 */
class AutoScale(private val floor: Float, private val shrinkDelayNanos: Long = 1_500_000_000L) {
    init {
        require(floor > 0f)
    }

    var max: Float = NiceNumbers.ceilMax(floor)
        private set

    private var shrinkSince = NONE

    /**
     * Fits the scale to [dataMax] at once, without hysteresis: for a picture that will not move (paused) or that
     * shows other data now (another foot or window).
     */
    fun reset(dataMax: Float): Float {
        max = targetFor(dataMax)
        shrinkSince = NONE
        return max
    }

    private fun targetFor(dataMax: Float): Float =
        NiceNumbers.ceilMax(if (dataMax.isFinite()) max(dataMax * HEADROOM, floor) else floor)

    /** [dataMax]: the largest value now visible (NaN or ≤ 0 when none). */
    fun update(dataMax: Float, frameNanos: Long): Float {
        val target = targetFor(dataMax)
        when {
            target > max -> {
                max = target
                shrinkSince = NONE
            }

            target <= max * SHRINK_FRACTION -> {
                if (shrinkSince == NONE) {
                    shrinkSince = frameNanos
                } else if (frameNanos - shrinkSince >= shrinkDelayNanos) {
                    max = target
                    shrinkSince = NONE
                }
            }

            else -> shrinkSince = NONE
        }
        return max
    }

    companion object {
        const val HEADROOM = 1.1f
        const val SHRINK_FRACTION = 0.6f
        private const val NONE = Long.MIN_VALUE
    }
}

/** Receives the points of a polyline, in pixels. */
interface LineSink {
    fun moveTo(x: Float, y: Float)

    fun lineTo(x: Float, y: Float)
}

/**
 * Feeds a polyline to a [LineSink], thinned per pixel column: with many samples per pixel (30 s at 200 Hz on a
 * narrow screen) each column keeps only its first, lowest, highest and last point, in sample order, which draws
 * exactly the same envelope with a fraction of the segments. A NaN y or a break leaves a gap. Reusable, no
 * allocation; not thread-safe.
 */
class LineReducer {
    private var sink: LineSink? = null
    private var penDown = false
    private var have = false
    private var column = 0
    private var count = 0
    private var firstX = 0f
    private var firstY = 0f
    private var minX = 0f
    private var minY = 0f
    private var minK = 0
    private var maxX = 0f
    private var maxY = 0f
    private var maxK = 0
    private var lastX = 0f
    private var lastY = 0f

    fun start(sink: LineSink) {
        this.sink = sink
        penDown = false
        have = false
    }

    /** The next sample; x must not decrease. [breakBefore]: no line from the previous point. */
    fun point(x: Float, y: Float, breakBefore: Boolean) {
        if (y.isNaN() || breakBefore) {
            flush()
            penDown = false
            if (y.isNaN()) return
        }
        val c = floor(x).toInt()
        if (have && c != column) flush()
        if (!have) {
            have = true
            column = c
            count = 0
            firstX = x
            firstY = y
            minX = x
            minY = y
            minK = 0
            maxX = x
            maxY = y
            maxK = 0
        } else {
            if (y < minY) {
                minX = x
                minY = y
                minK = count
            }
            if (y > maxY) {
                maxX = x
                maxY = y
                maxK = count
            }
        }
        lastX = x
        lastY = y
        count++
    }

    fun finish() {
        flush()
        penDown = false
        sink = null
    }

    private fun flush() {
        if (!have) return
        have = false
        val s = sink ?: return
        val continues = penDown
        if (continues) s.lineTo(firstX, firstY) else s.moveTo(firstX, firstY)
        penDown = true
        val last = count - 1
        // The extremes in the order they occurred, skipping those that are the first or last point anyway.
        if (minK <= maxK) {
            if (minK in 1 until last) s.lineTo(minX, minY)
            if (maxK in 1 until last && maxK != minK) s.lineTo(maxX, maxY)
        } else {
            if (maxK in 1 until last) s.lineTo(maxX, maxY)
            if (minK in 1 until last) s.lineTo(minX, minY)
        }
        if (last > 0) {
            s.lineTo(lastX, lastY)
        } else if (!continues) {
            // The first point of a segment: a zero-length line, so that a lone sample still shows (round cap).
            s.lineTo(firstX, firstY)
        }
    }
}

package com.smartsocks.app.live

import com.smartsocks.core.analytics.Balance
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.SensorLayout
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Shared drawing geometry, in insole units (the [SensorLayout] coordinate system). */
object LiveGeometry {
    /**
     * Each foot's insole is drawn centred at x = ∓this; the combined COP from core's balance is computed for the
     * same stance width, so it can be drawn in the same coordinates.
     */
    const val STANCE_HALF_WIDTH = 0.8

    val layout: SensorLayout = SensorLayout.Legacy
}

/**
 * How strongly a sensor with [load] (µS) is coloured, 0..1: the square root of its share of [fullScale], so light
 * loads still show. 0 for no, a negative or an invalid load, or without a scale.
 */
fun loadLevel(load: Double, fullScale: Double): Float {
    if (!(fullScale > 0.0) || !fullScale.isFinite() || !(load > 0.0)) return 0f
    return sqrt((load / fullScale).coerceIn(0.0, 1.0)).toFloat()
}

/**
 * One point of a COP trail; [age] goes from 0 (now) to 1 (about to disappear). [startsSegment]: the foot was in the
 * air before this point (a new step), so no line leads to it from the previous point.
 */
class TrailPoint(val x: Double, val y: Double, val age: Float, val startsSegment: Boolean = false)

/** Everything the COP view draws for one foot in one frame. */
class FootView(
    val status: FootStatus,
    /** Null while not streaming. */
    val state: FootState?,
    /** Oldest first. Empty while not streaming. */
    val trail: List<TrailPoint>,
    /** Replaces the link state under the foot's name (playback: "Not recorded"). */
    val caption: String? = null,
) {
    val foot: Foot get() = status.foot
}

/** One displayed frame of the live screen. */
class LiveSnapshot(
    val left: FootView,
    val right: FootView,
    /** Null when neither foot is in contact. */
    val balance: Balance?,
    /** Sensor load (µS) drawn at full intensity; shared by both feet so they compare directly. */
    val fullScaleLoad: Double,
) {
    fun foot(foot: Foot): FootView = if (foot == Foot.LEFT) left else right
}

/**
 * The recent COP positions of one foot, timed by the frame clock so the trail fades at the same speed whatever the
 * sample rate. A sample is added once (by its timestamp); a foot in the air adds nothing, so its trail fades out.
 */
class CopTrail(private val windowNanos: Long = 1_000_000_000L, private val capacity: Int = 240) {
    private val times = LongArray(capacity)
    private val xs = DoubleArray(capacity)
    private val ys = DoubleArray(capacity)
    private val breaks = BooleanArray(capacity)
    private var gap = true
    private var start = 0
    private var size = 0
    private var lastSampleNanos = Long.MIN_VALUE

    fun update(frameNanos: Long, state: FootState?): List<TrailPoint> {
        if (state == null) {
            clear()
            return emptyList()
        }
        val cop = state.cop
        if (cop == null) gap = true
        if (cop != null && state.timestampNanos != lastSampleNanos) {
            lastSampleNanos = state.timestampNanos
            if (size == capacity) {
                start = (start + 1) % capacity
                size--
            }
            val i = (start + size) % capacity
            times[i] = frameNanos
            xs[i] = cop.x
            ys[i] = cop.y
            breaks[i] = gap
            gap = false
            size++
        }
        while (size > 0 && frameNanos - times[start] >= windowNanos) {
            start = (start + 1) % capacity
            size--
        }
        return List(size) {
            val i = (start + it) % capacity
            val age = ((frameNanos - times[i]).toDouble() / windowNanos).toFloat().coerceIn(0f, 1f)
            TrailPoint(xs[i], ys[i], age, startsSegment = breaks[i])
        }
    }

    fun clear() {
        size = 0
        start = 0
        lastSampleNanos = Long.MIN_VALUE
        gap = true
    }
}

/**
 * The load that maps to full colour: follows the highest sensor load seen on either foot, decaying (half-life
 * [halfLifeNanos]) so the scale adapts when the load drops, but never below [floor] so that noise does not light
 * up an unloaded insole.
 */
class LoadScale(private val floor: Double = 40.0, private val halfLifeNanos: Long = 4_000_000_000L) {
    private var peak = floor
    private var lastFrame = Long.MIN_VALUE

    val value: Double get() = peak

    fun update(frameNanos: Long, vararg states: FootState?): Double {
        if (lastFrame != Long.MIN_VALUE && frameNanos > lastFrame) {
            peak *= 0.5.pow((frameNanos - lastFrame).toDouble() / halfLifeNanos)
        }
        lastFrame = frameNanos
        for (state in states) {
            if (state == null) continue
            for (load in state.loads) if (load.isFinite()) peak = max(peak, load)
        }
        peak = max(peak, floor)
        return peak
    }
}

/** Turns the two feet's current state into [LiveSnapshot]s. Not thread-safe: one caller, the frame loop. */
internal class LiveSampler {
    private val leftTrail = CopTrail()
    private val rightTrail = CopTrail()
    private val scale = LoadScale()

    fun sample(
        frameNanos: Long,
        leftStatus: FootStatus,
        leftState: FootState?,
        rightStatus: FootStatus,
        rightState: FootState?,
    ): LiveSnapshot {
        val l = leftState.takeIf { leftStatus.state == LinkState.Streaming }
        val r = rightState.takeIf { rightStatus.state == LinkState.Streaming }
        return LiveSnapshot(
            left = FootView(leftStatus, l, leftTrail.update(frameNanos, l)),
            right = FootView(rightStatus, r, rightTrail.update(frameNanos, r)),
            balance = balanceOf(l, r),
            fullScaleLoad = scale.update(frameNanos, l, r),
        )
    }
}

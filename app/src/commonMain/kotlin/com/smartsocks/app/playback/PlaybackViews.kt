package com.smartsocks.app.playback

import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.FootView
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.app.live.TrailPoint
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.PlaybackFrame
import com.smartsocks.core.playback.PlaybackSample
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SensorSelection
import kotlin.math.max

/**
 * The whole session at a glance, decimated once: per foot the highest total load (0 while in the air) in each of
 * [buckets] equal time slices; NaN where the foot has no sample. For the overview strip above the time slider.
 */
class PlaybackOverview(
    val startMillis: Double,
    val endMillis: Double,
    val left: FloatArray,
    val right: FloatArray,
    /** Highest total load of either foot; at least 1 so it can always divide. */
    val maxTotal: Float,
    /**
     * Highest sum of the loads of each set of sensors over the session (either foot), indexed by
     * [SensorSelection.mask]: entry `1 shl i` is sensor i's peak, the full mask the peak of the sum of all.
     */
    private val maxSubsetLoads: FloatArray = FloatArray(SensorSelection.ALL_MASK + 1),
) {
    val buckets: Int get() = left.size

    /** Highest single-sensor load, µS: the colour scale of the COP view. */
    val maxSensorLoad: Float get() = maxSensorLoad(SensorSelection.ALL_MASK)

    /** Highest single-sensor load among the sensors in [mask] (a [SensorSelection.mask]); 0 without data. */
    fun maxSensorLoad(mask: Int): Float {
        var m = 0f
        for (s in 0 until SENSOR_COUNT) if ((mask shr s) and 1 == 1) m = max(m, maxSubsetLoads[1 shl s])
        return m
    }

    /** Highest sum of the loads of the sensors in [mask] at any one moment; 0 without data. */
    fun maxSelectedLoad(mask: Int): Float = maxSubsetLoads[mask]

    fun foot(foot: Foot): FloatArray = if (foot == Foot.LEFT) left else right

    /** Start time of bucket [i]. */
    fun timeOf(i: Int): Double = startMillis + (endMillis - startMillis) * i / buckets

    companion object {
        const val DEFAULT_BUCKETS = 800

        /** One pass over every sample: build it off the main thread with the timeline. */
        fun of(timeline: SessionTimeline, buckets: Int = DEFAULT_BUCKETS): PlaybackOverview {
            require(buckets > 0)
            val start = timeline.startMillis
            val span = timeline.durationMillis
            var maxTotal = 0f
            val loads = FloatArray(SENSOR_COUNT)
            val sums = FloatArray(SensorSelection.ALL_MASK + 1)
            val maxSums = FloatArray(SensorSelection.ALL_MASK + 1)
            val arrays = Foot.entries.map { foot ->
                val out = FloatArray(buckets) { Float.NaN }
                val track = timeline.track(foot)
                for (i in 0 until track.size) {
                    val b = if (span <= 0.0) 0 else ((track.timeAt(i) - start) / span * buckets).toInt()
                    val k = b.coerceIn(0, buckets - 1)
                    val total = if (track.inContactAt(i)) track.totalLoadAt(i).toFloat() else 0f
                    val v = if (total.isFinite()) total else 0f
                    if (out[k].isNaN() || v > out[k]) out[k] = v
                    if (v > maxTotal) maxTotal = v
                    for (s in 0 until SENSOR_COUNT) {
                        val load = track.loadAt(i, s).toFloat()
                        loads[s] = if (load.isFinite()) load else 0f
                    }
                    // Every subset's sum from a smaller one: 63 additions per sample.
                    for (m in 1 until sums.size) {
                        val lowest = m and -m
                        val sum = sums[m xor lowest] + loads[lowest.countTrailingZeroBits()]
                        sums[m] = sum
                        if (sum > maxSums[m]) maxSums[m] = sum
                    }
                }
                out
            }
            return PlaybackOverview(
                startMillis = start,
                endMillis = timeline.endMillis,
                left = arrays[0],
                right = arrays[1],
                maxTotal = max(maxTotal, 1f),
                maxSubsetLoads = maxSums,
            )
        }
    }
}

/**
 * Turns a playback position into the [LiveSnapshot] the live views draw, so the COP view and the balance panel are
 * the same code for live and recorded data. The trail is the last [trailMillis] of recorded COP before the position
 * (session time: at slow motion it stays on screen longer, like the movement itself).
 */
class PlaybackViews(
    private val timeline: SessionTimeline,
    /** Sensor load drawn at full colour; fixed for the session so moments compare directly. */
    val fullScaleLoad: Double,
    private val trailMillis: Double = 1000.0,
    private val maxTrailPoints: Int = 240,
) {
    fun snapshot(frame: PlaybackFrame): LiveSnapshot {
        val position = frame.positionMillis
        return LiveSnapshot(
            left = view(Foot.LEFT, frame.left, position),
            right = view(Foot.RIGHT, frame.right, position),
            balance = frame.balance,
            fullScaleLoad = fullScaleLoad,
        )
    }

    private fun view(foot: Foot, sample: PlaybackSample?, position: Double): FootView {
        val recorded = foot in timeline.feet
        val state = sample?.toFootState()
        val status = FootStatus(
            foot = foot,
            device = DeviceChoice.None,
            deviceName = "Recorded",
            state = if (state != null) LinkState.Streaming else LinkState.Idle,
        )
        val joined = timeline.joins[foot]
        val caption = when {
            !recorded -> "Not recorded"

            // A foot that connected only later in the recording.
            state == null && joined != null && position < joined ->
                "Joins at ${PlayerController.timeText(joined - timeline.startMillis, tenths = false)}"

            state == null -> "No data here"

            else -> null
        }
        return FootView(status, state, if (state == null) emptyList() else trail(foot, position), caption)
    }

    /** Recorded COP of [foot] in (position − [trailMillis], position], thinned to [maxTrailPoints]. */
    fun trail(foot: Foot, position: Double): List<TrailPoint> {
        val track = timeline.track(foot)
        val range = track.range(position - trailMillis, position)
        if (range.isEmpty()) return emptyList()
        val count = range.last - range.first + 1
        val stride = (count + maxTrailPoints - 1) / maxTrailPoints
        val gap = timeline.staleAfterMillis(foot)
        val out = ArrayList<TrailPoint>(count / stride + 1)
        var gapBefore = true
        var lastT = Double.NaN
        var i = range.first
        while (i <= range.last) {
            val x = track.copXAt(i)
            val y = track.copYAt(i)
            val t = track.timeAt(i)
            if (x.isNaN() || y.isNaN()) {
                gapBefore = true
            } else {
                val broken = gapBefore || (!lastT.isNaN() && t - lastT > gap * stride)
                val age = ((position - t) / trailMillis).toFloat().coerceIn(0f, 1f)
                out += TrailPoint(x, y, age, startsSegment = broken)
                gapBefore = false
                lastT = t
            }
            // Never skip over a lift-off: look at the in-between samples for a gap.
            val next = i + stride
            for (k in i + 1 until minOf(next, range.last + 1)) if (track.copXAt(k).isNaN()) gapBefore = true
            i = next
        }
        return out
    }
}

/** A recorded sample as the live views know it; shares are derived like the processor does. */
internal fun PlaybackSample.toFootState(): FootState {
    val total = totalLoad
    val shares = DoubleArray(SENSOR_COUNT) { if (inContact && total > 0) loads[it] / total else 0.0 }
    return FootState(
        foot = foot,
        timestampNanos = (tMillis * 1_000_000).toLong(),
        resistanceOhms = resistanceOhms,
        loads = loads,
        totalLoad = total,
        shares = shares,
        cop = cop,
        rawCop = rawCop,
        baselineSource = baselineSource,
    )
}

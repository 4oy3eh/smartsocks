package com.smartsocks.core.analytics

import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.protocol.SENSOR_COUNT

/** Per-sensor resistance with the foot lifted (no load). */
class Baseline(val unloadedOhms: DoubleArray) {
    init {
        require(unloadedOhms.size == SENSOR_COUNT)
    }

    internal val unloadedMicroSiemens = DoubleArray(SENSOR_COUNT) { microSiemens(unloadedOhms[it]) }
}

/**
 * Collects frames while the foot is lifted and takes the per-sensor median, so a single noisy sample (which is
 * what the legacy app used) cannot spoil the calibration.
 */
class BaselineCalibrator(private val samplesNeeded: Int = 50) {
    init {
        require(samplesNeeded > 0) { "samplesNeeded must be positive" }
    }

    private val samples = Array(SENSOR_COUNT) { DoubleArray(samplesNeeded) }
    private var collected = 0

    val progress: Double get() = collected.toDouble() / samplesNeeded
    val isComplete: Boolean get() = collected >= samplesNeeded

    /** Returns true once enough samples were collected. */
    fun add(frame: SensorFrame): Boolean {
        if (isComplete) return true
        for (i in 0 until SENSOR_COUNT) samples[i][collected] = frame.resistanceOhms[i]
        collected++
        return isComplete
    }

    fun result(): Baseline {
        check(isComplete) { "Calibration not complete: $collected/$samplesNeeded" }
        return Baseline(DoubleArray(SENSOR_COUNT) { median(samples[it]) })
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }
}

/**
 * FSR resistance falls roughly as 1/force, so conductance (1/R) is roughly linear in force. Load of a sensor is
 * its conductance above the unloaded baseline, in microsiemens. Without a baseline the raw conductance is used,
 * which is already close to zero for an unloaded FSR.
 */
object LoadModel {
    fun loads(frame: SensorFrame, baseline: Baseline?, out: DoubleArray = DoubleArray(SENSOR_COUNT)): DoubleArray =
        loadsAbove(frame, baseline?.unloadedMicroSiemens, out)

    /** Same with the baseline given directly as per-sensor conductance in µS (null = zero). */
    fun loadsAbove(
        frame: SensorFrame,
        baselineMicroSiemens: DoubleArray?,
        out: DoubleArray = DoubleArray(SENSOR_COUNT),
    ): DoubleArray {
        for (i in 0 until SENSOR_COUNT) {
            val g = microSiemens(frame.resistanceOhms[i])
            val g0 = baselineMicroSiemens?.get(i) ?: 0.0
            out[i] = (g - g0).coerceAtLeast(0.0)
        }
        return out
    }
}

/**
 * Per-sensor minimum of conductance over a sliding window (default ~4 s), kept in [BUCKETS] time buckets so memory
 * is fixed and [add] allocates nothing. Walking lifts the foot every stride, so the minimum is the unloaded value.
 * Single-threaded; timestamps are expected to be non-decreasing (a step back is treated as "now").
 */
class RollingMinimum(windowNanos: Long) {
    init {
        require(windowNanos >= BUCKETS) { "window too short" }
    }

    private val bucketNanos = windowNanos / BUCKETS
    private val mins = Array(SENSOR_COUNT) { DoubleArray(BUCKETS) { Double.POSITIVE_INFINITY } }
    private var currentId = Long.MIN_VALUE

    private val totalMins = DoubleArray(BUCKETS) { Double.POSITIVE_INFINITY }
    private val totalMaxs = DoubleArray(BUCKETS) { Double.NEGATIVE_INFINITY }

    /** Minimum per sensor over the window; valid after the first [add]. */
    val values = DoubleArray(SENSOR_COUNT)

    /** Smallest and largest sum over all sensors of a single sample in the window; valid after the first [add]. */
    var totalMin = 0.0
        private set
    var totalMax = 0.0
        private set

    fun add(timestampNanos: Long, microSiemens: DoubleArray) {
        val id = timestampNanos.floorDiv(bucketNanos)
        if (currentId == Long.MIN_VALUE) {
            currentId = id
        } else if (id > currentId) {
            val steps = minOf(id - currentId, BUCKETS.toLong()).toInt()
            for (k in 1..steps) {
                val slot = (currentId + k).mod(BUCKETS.toLong()).toInt()
                for (i in 0 until SENSOR_COUNT) mins[i][slot] = Double.POSITIVE_INFINITY
                totalMins[slot] = Double.POSITIVE_INFINITY
                totalMaxs[slot] = Double.NEGATIVE_INFINITY
            }
            currentId = id
        }
        val slot = currentId.mod(BUCKETS.toLong()).toInt()
        var sum = 0.0
        for (i in 0 until SENSOR_COUNT) sum += microSiemens[i]
        if (sum < totalMins[slot]) totalMins[slot] = sum
        if (sum > totalMaxs[slot]) totalMaxs[slot] = sum
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (b in 0 until BUCKETS) {
            if (totalMins[b] < lo) lo = totalMins[b]
            if (totalMaxs[b] > hi) hi = totalMaxs[b]
        }
        totalMin = lo
        totalMax = hi
        for (i in 0 until SENSOR_COUNT) {
            if (microSiemens[i] < mins[i][slot]) mins[i][slot] = microSiemens[i]
            var m = Double.POSITIVE_INFINITY
            for (b in 0 until BUCKETS) if (mins[i][b] < m) m = mins[i][b]
            values[i] = m
        }
    }

    fun clear() {
        for (row in mins) row.fill(Double.POSITIVE_INFINITY)
        totalMins.fill(Double.POSITIVE_INFINITY)
        totalMaxs.fill(Double.NEGATIVE_INFINITY)
        currentId = Long.MIN_VALUE
    }

    private companion object {
        const val BUCKETS = 16
    }
}

/**
 * Smallest resistance we trust: 100 Ω (10 000 µS) is already a hard press on an FSR, so anything lower is a
 * glitch (a zero count would otherwise mean infinite conductance and yank the COP).
 */
internal const val MIN_OHMS = 100.0

internal fun microSiemens(ohms: Double): Double = 1e6 / ohms.coerceAtLeast(MIN_OHMS)

data class Cop(val position: Point, val totalLoad: Double)

object CenterOfPressure {
    /**
     * Load-weighted mean of sensor positions. Returns null when the total load is below [minTotalLoad] — the foot
     * is in the air and any "COP" would be noise.
     */
    fun compute(loads: DoubleArray, positions: List<Point>, minTotalLoad: Double): Cop? {
        require(loads.size == positions.size)
        var total = 0.0
        var x = 0.0
        var y = 0.0
        for (i in loads.indices) {
            total += loads[i]
            x += loads[i] * positions[i].x
            y += loads[i] * positions[i].y
        }
        if (total < minTotalLoad || total <= 0.0) return null
        return Cop(Point(x / total, y / total), total)
    }
}

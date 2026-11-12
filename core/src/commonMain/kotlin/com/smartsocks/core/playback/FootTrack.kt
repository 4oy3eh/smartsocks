package com.smartsocks.core.playback

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.recording.CsvRow

/**
 * All samples of one foot, sorted by time (stable: samples with equal timestamps keep their file order), stored as
 * parallel primitive arrays, about 100 bytes per sample. Per-sensor values are kept as floats: the CSV holds them
 * with 1–3 decimals, well within float precision, and this halves the biggest part of the footprint. Missing COPs
 * are NaN. Built by [FootTrackBuilder].
 */
class FootTrack internal constructor(
    val foot: Foot,
    val size: Int,
    private val t: DoubleArray,
    private val total: DoubleArray,
    private val copX: DoubleArray,
    private val copY: DoubleArray,
    private val rawCopX: DoubleArray,
    private val rawCopY: DoubleArray,
    private val resistance: FloatArray,
    private val load: FloatArray,
    private val contact: BooleanArray,
    private val baseline: ByteArray,
) {
    /** Median gap between consecutive samples with different times, i.e. the measured sample period. */
    val medianGapMillis: Double? = medianGap()

    fun isEmpty(): Boolean = size == 0

    val firstMillis: Double? get() = if (size == 0) null else t[0]
    val lastMillis: Double? get() = if (size == 0) null else t[size - 1]

    fun timeAt(index: Int): Double = t[index]

    fun totalLoadAt(index: Int): Double = total[index]

    fun inContactAt(index: Int): Boolean = contact[index]

    /** Smoothed COP x, NaN when the foot was in the air. */
    fun copXAt(index: Int): Double = copX[index]

    fun copYAt(index: Int): Double = copY[index]

    fun copAt(index: Int): Point? = point(copX[index], copY[index])

    fun rawCopAt(index: Int): Point? = point(rawCopX[index], rawCopY[index])

    fun loadAt(index: Int, sensor: Int): Double = load[index * SENSOR_COUNT + sensor].toDouble()

    fun resistanceAt(index: Int, sensor: Int): Double = resistance[index * SENSOR_COUNT + sensor].toDouble()

    fun baselineAt(index: Int): BaselineSource = BaselineSource.entries[baseline[index].toInt()]

    fun sample(index: Int): PlaybackSample = PlaybackSample(
        foot = foot,
        index = index,
        tMillis = t[index],
        resistanceOhms = DoubleArray(SENSOR_COUNT) { resistanceAt(index, it) },
        loads = DoubleArray(SENSOR_COUNT) { loadAt(index, it) },
        totalLoad = total[index],
        cop = copAt(index),
        rawCop = rawCopAt(index),
        inContact = contact[index],
        baselineSource = baselineAt(index),
    )

    /**
     * The last sample at or before [tMillis] (the last of several with an equal timestamp). Clamped: a time before
     * the first sample gives 0. -1 only for an empty track.
     */
    fun indexAt(tMillis: Double): Int {
        if (size == 0) return -1
        return (upperBound(tMillis) - 1).coerceAtLeast(0)
    }

    /** Indices of the samples with [fromMillis] ≤ t ≤ [toMillis]; empty if there are none. */
    fun range(fromMillis: Double, toMillis: Double): IntRange {
        if (toMillis < fromMillis) return IntRange.EMPTY
        return lowerBound(fromMillis) until upperBound(toMillis)
    }

    /** First index with t ≥ [value]. */
    internal fun lowerBound(value: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] < value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index with t > [value]. */
    internal fun upperBound(value: Double): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= value) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun point(x: Double, y: Double): Point? = if (x.isNaN() || y.isNaN()) null else Point(x, y)

    private fun medianGap(): Double? {
        if (size < 2) return null
        val gaps = DoubleArray(size - 1)
        var n = 0
        for (i in 1 until size) {
            val gap = t[i] - t[i - 1]
            if (gap > 0.0) gaps[n++] = gap
        }
        if (n == 0) return null
        gaps.sort(0, n)
        return gaps[n / 2]
    }
}

/**
 * Collects the rows of one foot into growable primitive arrays, then hands them to a [FootTrack] without any
 * intermediate objects. Sorts (stably, through an index permutation) only if the rows did not arrive in order.
 */
internal class FootTrackBuilder(val foot: Foot) {
    private var size = 0
    private var capacity = INITIAL_CAPACITY
    private var sorted = true
    private var t = DoubleArray(capacity)
    private var total = DoubleArray(capacity)
    private var copX = DoubleArray(capacity)
    private var copY = DoubleArray(capacity)
    private var rawCopX = DoubleArray(capacity)
    private var rawCopY = DoubleArray(capacity)
    private var resistance = FloatArray(capacity * SENSOR_COUNT)
    private var load = FloatArray(capacity * SENSOR_COUNT)
    private var contact = BooleanArray(capacity)
    private var baseline = ByteArray(capacity)

    fun add(row: CsvRow) {
        require(row.foot == foot) { "Row for ${row.foot} added to the $foot track" }
        require(row.tMillis.isFinite()) { "Sample time must be finite, was ${row.tMillis}" }
        if (size == capacity) grow()
        val i = size
        if (i > 0 && row.tMillis < t[i - 1]) sorted = false
        t[i] = row.tMillis
        total[i] = row.totalLoad
        copX[i] = row.cop?.x ?: Double.NaN
        copY[i] = row.cop?.y ?: Double.NaN
        rawCopX[i] = row.rawCop?.x ?: Double.NaN
        rawCopY[i] = row.rawCop?.y ?: Double.NaN
        for (s in 0 until SENSOR_COUNT) {
            resistance[i * SENSOR_COUNT + s] = row.resistanceOhms[s].toFloat()
            load[i * SENSOR_COUNT + s] = row.loads[s].toFloat()
        }
        contact[i] = row.inContact
        baseline[i] = row.baseline.ordinal.toByte()
        size++
    }

    /** Arrays are trimmed (or permuted into time order) one at a time, so the peak stays near the final size. */
    fun build(): FootTrack {
        val order = if (sorted) null else stableOrder(t, size)
        val n = size
        return FootTrack(
            foot = foot,
            size = n,
            t = t.arrange(order, n),
            total = total.arrange(order, n),
            copX = copX.arrange(order, n),
            copY = copY.arrange(order, n),
            rawCopX = rawCopX.arrange(order, n),
            rawCopY = rawCopY.arrange(order, n),
            resistance = resistance.arrange(order, n),
            load = load.arrange(order, n),
            contact = contact.arrange(order, n),
            baseline = baseline.arrange(order, n),
        )
    }

    private fun grow() {
        capacity += capacity shr 1
        t = t.copyOf(capacity)
        total = total.copyOf(capacity)
        copX = copX.copyOf(capacity)
        copY = copY.copyOf(capacity)
        rawCopX = rawCopX.copyOf(capacity)
        rawCopY = rawCopY.copyOf(capacity)
        resistance = resistance.copyOf(capacity * SENSOR_COUNT)
        load = load.copyOf(capacity * SENSOR_COUNT)
        contact = contact.copyOf(capacity)
        baseline = baseline.copyOf(capacity)
    }

    private fun DoubleArray.arrange(order: IntArray?, n: Int): DoubleArray =
        if (order == null) copyOf(n) else DoubleArray(n) { this[order[it]] }

    private fun BooleanArray.arrange(order: IntArray?, n: Int): BooleanArray =
        if (order == null) copyOf(n) else BooleanArray(n) { this[order[it]] }

    private fun ByteArray.arrange(order: IntArray?, n: Int): ByteArray =
        if (order == null) copyOf(n) else ByteArray(n) { this[order[it]] }

    /** Per-sample blocks of [SENSOR_COUNT] values. */
    private fun FloatArray.arrange(order: IntArray?, n: Int): FloatArray = if (order == null) {
        copyOf(n * SENSOR_COUNT)
    } else {
        FloatArray(n * SENSOR_COUNT) { this[order[it / SENSOR_COUNT] * SENSOR_COUNT + it % SENSOR_COUNT] }
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}

/** Indices 0 until [size] ordered by [keys], equal keys in their original order (bottom-up merge sort). */
internal fun stableOrder(keys: DoubleArray, size: Int): IntArray {
    var src = IntArray(size) { it }
    var dst = IntArray(size)
    var width = 1
    while (width < size) {
        var lo = 0
        while (lo < size) {
            val mid = minOf(lo + width, size)
            val hi = minOf(lo + 2 * width, size)
            var i = lo
            var j = mid
            var k = lo
            while (i < mid && j < hi) dst[k++] = if (keys[src[j]] < keys[src[i]]) src[j++] else src[i++]
            while (i < mid) dst[k++] = src[i++]
            while (j < hi) dst[k++] = src[j++]
            lo = hi
        }
        val swap = src
        src = dst
        dst = swap
        width *= 2
    }
    return src
}

package com.smartsocks.core.playback

import com.smartsocks.core.analytics.Balance
import com.smartsocks.core.analytics.BalanceCalculator
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.FootLoad
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.recording.CsvFootJoin
import com.smartsocks.core.recording.CsvMarker
import com.smartsocks.core.recording.CsvRow
import com.smartsocks.core.recording.CsvSessionInfo
import com.smartsocks.core.recording.CsvSessionReader
import com.smartsocks.core.recording.RecordedSession

/**
 * One recorded sample, materialised from a [FootTrack]. Cheap enough to create once per displayed frame; for
 * charts over many samples read the track's per-index accessors instead.
 */
class PlaybackSample(
    val foot: Foot,
    /** Index in the foot's [FootTrack]. */
    val index: Int,
    val tMillis: Double,
    val resistanceOhms: DoubleArray,
    val loads: DoubleArray,
    override val totalLoad: Double,
    override val cop: Point?,
    val rawCop: Point?,
    override val inContact: Boolean,
    val baselineSource: BaselineSource,
) : FootLoad

/** What playback shows at one position: each foot's current sample (null if none) and the combined balance. */
class PlaybackFrame(
    val positionMillis: Double,
    val left: PlaybackSample?,
    val right: PlaybackSample?,
    val balance: Balance?,
) {
    fun sample(foot: Foot): PlaybackSample? = if (foot == Foot.LEFT) left else right
}

/**
 * A recording indexed for random access by time, like a video: per-foot [FootTrack]s, markers, and lookups for the
 * player and charts. Times are the CSV's `t_ms`, which may start slightly below zero.
 *
 * A foot that joined the recording late (it connected after the start) simply has no samples before it joined: at
 * those positions it shows nothing ([currentSample] is null), and [joins] says when it came.
 *
 * Building one from a long recording takes a while (and [GaitEvents.detect] on it too): do both off the main
 * thread. For long recordings use [read] or [SessionTimelineBuilder], which never hold the rows as objects.
 */
class SessionTimeline private constructor(
    private val tracks: List<FootTrack>,
    val header: Map<String, String>,
    markers: List<CsvMarker>,
    joins: List<CsvFootJoin>,
) {
    /** Keeps nothing of [session]: the rows are copied into compact tracks. */
    constructor(session: RecordedSession) : this(
        SessionTimelineBuilder().apply { session.rows.forEach(::add) }.tracks(),
        session.header,
        session.markers,
        session.joins,
    )

    internal constructor(tracks: List<FootTrack>, info: CsvSessionInfo) :
        this(tracks, info.header, info.markers, info.joins)

    /** Feet that have at least one sample, in [Foot] order (also those that joined late). */
    val feet: Set<Foot> = Foot.entries.filter { !track(it).isEmpty() }.toSet()

    /**
     * The feet with samples that joined the recording after it started, and when (their first sample, `t_ms`). Empty
     * for recordings without late joins, and for files written before joins were recorded.
     */
    val joins: Map<Foot, Double> = joins.filter { it.foot in feet }.associate { it.foot to it.tMillis }

    /** Earliest sample of any foot; 0 for a session without samples. */
    val startMillis: Double = tracks.mapNotNull { it.firstMillis }.minOrNull() ?: 0.0
    val endMillis: Double = tracks.mapNotNull { it.lastMillis }.maxOrNull() ?: 0.0
    val durationMillis: Double get() = endMillis - startMillis

    /** Sorted by time. */
    val markers: List<CsvMarker> = markers.sortedBy { it.tMillis }

    /** The period the recording asked the device for (`sample_rate_hz`); the device may have streamed slower. */
    val requestedPeriodMillis: Double? =
        header["sample_rate_hz"]?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }?.let { 1000.0 / it }

    /**
     * Time between samples of the recording: the longer of the requested period and the measured one (median gap
     * of the longest track), else 10 ms.
     */
    val samplePeriodMillis: Double = longer(requestedPeriodMillis, tracks.maxBy { it.size }.medianGapMillis)
        ?: DEFAULT_PERIOD_MILLIS

    /** Like [samplePeriodMillis], measured on [foot]'s own samples (the feet may stream at different rates). */
    fun samplePeriodMillis(foot: Foot): Double =
        longer(requestedPeriodMillis, track(foot).medianGapMillis) ?: samplePeriodMillis

    /**
     * A foot's sample stops being shown once the position is this far past it (end of that foot's data, or a gap
     * where its link was down), instead of freezing the last value on screen.
     */
    fun staleAfterMillis(foot: Foot): Double = STALE_PERIODS * samplePeriodMillis(foot)

    /**
     * Samples of different feet closer than this belong to one frame for [nextFrameTime]/[previousFrameTime]:
     * the two sample clocks are never exactly in phase.
     */
    val frameMergeMillis: Double get() = samplePeriodMillis / 4

    /** Empty track for a foot that was not recorded. */
    fun track(foot: Foot): FootTrack = tracks[foot.ordinal]

    /** See [FootTrack.indexAt]. */
    fun indexAt(foot: Foot, tMillis: Double): Int = track(foot).indexAt(tMillis)

    fun sampleAt(foot: Foot, index: Int): PlaybackSample = track(foot).sample(index)

    /** See [FootTrack.range]. */
    fun range(foot: Foot, fromMillis: Double, toMillis: Double): IntRange = track(foot).range(fromMillis, toMillis)

    /**
     * The sample of [foot] on screen at [positionMillis]: the last one at or before it, or null before the foot's
     * first sample or more than [staleAfterMillis] after the last one before the position.
     */
    fun currentSample(foot: Foot, positionMillis: Double): PlaybackSample? {
        val track = track(foot)
        val i = track.upperBound(positionMillis) - 1
        if (i < 0 || positionMillis - track.timeAt(i) > staleAfterMillis(foot)) return null
        return track.sample(i)
    }

    /**
     * Both feet at [positionMillis], with the balance computed exactly as for live data. Before a foot that joined
     * late ([joins]) was there, there is no balance: the other foot alone would show as carrying everything.
     */
    fun frameAt(positionMillis: Double, stanceHalfWidth: Double = 1.0): PlaybackFrame {
        val left = currentSample(Foot.LEFT, positionMillis)
        val right = currentSample(Foot.RIGHT, positionMillis)
        val beforeJoin = joins.values.any { positionMillis < it }
        val balance = if (beforeJoin) null else BalanceCalculator.compute(left, right, stanceHalfWidth)
        return PlaybackFrame(positionMillis, left, right, balance)
    }

    /** Earliest sample time of any foot strictly after [tMillis], or null. */
    fun nextSampleTime(tMillis: Double): Double? {
        var best = Double.POSITIVE_INFINITY
        for (track in tracks) {
            val i = track.upperBound(tMillis)
            if (i < track.size && track.timeAt(i) < best) best = track.timeAt(i)
        }
        return if (best == Double.POSITIVE_INFINITY) null else best
    }

    /** Latest sample time of any foot strictly before [tMillis], or null. */
    fun previousSampleTime(tMillis: Double): Double? {
        var best = Double.NEGATIVE_INFINITY
        for (track in tracks) {
            val i = track.lowerBound(tMillis) - 1
            if (i >= 0 && track.timeAt(i) > best) best = track.timeAt(i)
        }
        return if (best == Double.NEGATIVE_INFINITY) null else best
    }

    /**
     * The next frame after [tMillis]: the samples starting with the next one and up to [frameMergeMillis] after
     * it count as one frame, which is shown at its last sample (so every foot of the frame is on screen).
     */
    fun nextFrameTime(tMillis: Double): Double? {
        val first = nextSampleTime(tMillis) ?: return null
        var last = first
        while (true) {
            val next = nextSampleTime(last) ?: break
            if (next - first > frameMergeMillis) break
            last = next
        }
        return last
    }

    /**
     * The frame before [tMillis] (at its last sample). Samples up to [frameMergeMillis] before [tMillis] belong to
     * the frame at [tMillis] itself and are skipped.
     */
    fun previousFrameTime(tMillis: Double): Double? = previousSampleTime(tMillis - frameMergeMillis)

    /** First marker strictly after [tMillis]. */
    fun nextMarker(tMillis: Double): CsvMarker? = markers.firstOrNull { it.tMillis > tMillis }

    /** Last marker strictly before [tMillis]. */
    fun previousMarker(tMillis: Double): CsvMarker? = markers.lastOrNull { it.tMillis < tMillis }

    companion object {
        private const val DEFAULT_PERIOD_MILLIS = 10.0
        private const val STALE_PERIODS = 5.0

        /**
         * Streams a [CsvRecorder][com.smartsocks.core.recording.CsvRecorder] file straight into a timeline: no
         * row objects are kept, so memory is the compact tracks only. Blocking: call it off the main thread.
         * @throws IllegalArgumentException as [CsvSessionReader.stream].
         */
        fun read(lines: Iterable<String>): SessionTimeline {
            val builder = SessionTimelineBuilder()
            val info = CsvSessionReader.stream(lines, builder::add)
            return builder.build(info)
        }

        private fun longer(a: Double?, b: Double?): Double? = when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }
    }
}

/** Builds a [SessionTimeline] row by row, e.g. from [CsvSessionReader.stream]. Single use, not thread-safe. */
class SessionTimelineBuilder {
    private val builders = Foot.entries.map { FootTrackBuilder(it) }

    /** Rows may come in any order; they are sorted per foot (only if needed) when building. */
    fun add(row: CsvRow) = builders[row.foot.ordinal].add(row)

    fun build(
        header: Map<String, String> = emptyMap(),
        markers: List<CsvMarker> = emptyList(),
        joins: List<CsvFootJoin> = emptyList(),
    ): SessionTimeline = build(CsvSessionInfo(header, markers, joins = joins))

    fun build(info: CsvSessionInfo): SessionTimeline = SessionTimeline(tracks(), info)

    internal fun tracks(): List<FootTrack> = builders.map { it.build() }
}

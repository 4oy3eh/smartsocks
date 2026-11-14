package com.smartsocks.app.live

import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.FootTrack
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.settings.SensorSelection
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min

/**
 * The recent samples of one foot at full stream rate, for the live charts: fixed-capacity primitive ring buffers,
 * nothing allocated per sample.
 *
 * **One writer** ([append], [clear]: the foot's stream collector) and **one reader** ([HistoryWindow.copy], the UI
 * once per frame) on different threads, without locks; [markBreak] may come from any thread. The writer fills a
 * slot, then publishes it by bumping the volatile [written] counter; the reader reads the counter first and only
 * copies published slots. The writer can only damage a slot the reader copies by lapping the ring during that copy,
 * so the reader takes at most [readableSamples] (half the ring), reads the counter again after the copy and drops
 * what the writer may have reached meanwhile, including the slot it may be filling right then. That second read is
 * ordered after the copy by a volatile write just before it (see [HistoryWindow.copy]). At 200 Hz the writer would
 * need about 40 s to lap half the ring; a frame copy takes well under a millisecond.
 *
 * Gaps: a sample more than [maxGapNanos] after the previous one, or the first after [markBreak] (the controller
 * calls it before every run of a session: reconnect, session rebuild), starts a new segment (lines are not joined
 * across it); the history itself is kept, so a foot that drops out leaves its last seconds visible and a reconnect
 * continues on the same time axis (all sessions stamp from the controller's shared clock). Only a timestamp that
 * goes backwards, which a new session's clock could produce in principle, clears it: the window search needs
 * increasing times.
 */
class FootHistory(val capacity: Int = DEFAULT_CAPACITY, private val maxGapNanos: Long = DEFAULT_MAX_GAP_NANOS) {
    init {
        require(capacity >= 2)
    }

    internal val times = LongArray(capacity)
    internal val totals = FloatArray(capacity)
    internal val loads = Array(SENSOR_COUNT) { FloatArray(capacity) }

    /** COP, NaN when the foot is in the air. */
    internal val copX = FloatArray(capacity)
    internal val copY = FloatArray(capacity)
    internal val contact = BooleanArray(capacity)

    /** No line leads to this sample from the previous one. */
    internal val breaks = BooleanArray(capacity)

    /** Samples ever appended; sample `k` lives in slot `k % capacity`. Published after the slot is filled. */
    @Volatile
    internal var written: Long = 0
        private set

    /** Samples before this index were cleared. Set before [written] moves on, see [clear]. */
    @Volatile
    internal var firstValid: Long = 0
        private set

    /**
     * Bumped by [markBreak] (any thread); the writer compares it with [breaksSeen]. A counter instead of a flag, so
     * that a request arriving while the writer takes the previous one is never lost (the value still differs).
     */
    @Volatile
    private var breakRequests = 0

    /** Writer only. */
    private var breaksSeen = 0

    /** Writer only: the next sample starts a segment (first sample, after [clear]). */
    private var gapPending = true

    /** The most samples a reader takes in one copy: the other half of the ring is the writer's headroom. */
    val readableSamples: Int get() = capacity / 2

    /** Number of samples currently kept (for tests and diagnostics). */
    val size: Int get() = min(written - firstValid, capacity.toLong()).toInt()

    /** The newest timestamp, or null when empty. Reader side safe. */
    val latestNanos: Long?
        get() {
            val w = written
            if (w <= firstValid) return null
            return times[((w - 1) % capacity).toInt()]
        }

    /** Writer only. */
    fun append(state: FootState) {
        val t = state.timestampNanos
        val w = written
        val requests = breakRequests
        var gap = gapPending || requests != breaksSeen
        breaksSeen = requests
        gapPending = false
        if (w > firstValid) {
            val last = times[((w - 1) % capacity).toInt()]
            if (t <= last) {
                clear()
                gap = true
            } else if (t - last > maxGapNanos) {
                gap = true
            }
        }
        val i = (written % capacity).toInt()
        times[i] = t
        totals[i] = state.totalLoad.toFloat()
        val l = state.loads
        for (s in 0 until SENSOR_COUNT) loads[s][i] = l[s].toFloat()
        val cop = state.cop
        copX[i] = cop?.x?.toFloat() ?: Float.NaN
        copY[i] = cop?.y?.toFloat() ?: Float.NaN
        contact[i] = state.inContact
        breaks[i] = gap
        written = written + 1
    }

    /** The next sample starts a new segment (the link was interrupted). May be called from any thread. */
    fun markBreak() {
        breakRequests++ // two racing callers may lose one increment, but the value still changes
    }

    /** Forgets everything. Writer only. */
    fun clear() {
        firstValid = written
        gapPending = true
    }

    companion object {
        /** About 80 s at 200 Hz, so a 30 s window is always within the readable half. */
        const val DEFAULT_CAPACITY = 16_384

        const val DEFAULT_MAX_GAP_NANOS = 250_000_000L
    }
}

/** Both feet's [FootHistory]. */
class LiveHistory(capacity: Int = FootHistory.DEFAULT_CAPACITY) {
    val left = FootHistory(capacity)
    val right = FootHistory(capacity)

    fun foot(foot: Foot): FootHistory = if (foot == Foot.LEFT) left else right
}

/**
 * A copy of the samples of one [FootHistory] inside a time window, in reusable arrays sized once: what a chart draws
 * in one frame. Times are seconds relative to the window's "now" (so ≤ 0 for the past). Besides the copied values it
 * holds [selected], the sum of the loads of the sensors in [selection], added up while copying (no extra history is
 * kept). Not thread-safe: owned by the UI thread.
 */
class HistoryWindow(val capacity: Int) {
    /** Written right before the re-check in [copy], to order it after the copy's reads. */
    @Volatile
    private var fence = 0

    /** Test hook: runs between the copy and its re-check (a writer racing the reader). */
    internal var beforeRecheck: (() -> Unit)? = null

    /** Seconds relative to now, increasing. */
    val t = FloatArray(capacity)
    val total = FloatArray(capacity)
    val loads = Array(SENSOR_COUNT) { FloatArray(capacity) }

    /** Sum of the loads of the sensors in [selection]; a non-finite load (none recorded) counts as 0. */
    val selected = FloatArray(capacity)
    val copX = FloatArray(capacity)
    val copY = FloatArray(capacity)
    val contact = BooleanArray(capacity)
    val breaks = BooleanArray(capacity)

    /** The sensors summed into [selected] by the last copy: a [SensorSelection.mask]. */
    var selection: Int = SensorSelection.ALL_MASK
        private set

    /** Valid entries: indices `0 until size`. */
    var size: Int = 0
        private set

    /** Index of the first entry inside the window (entry 0 may be the one just before it). */
    var firstInside: Int = 0
        private set

    /**
     * Part of the window is missing although the foot had samples there: they are no longer in the history (a
     * window copied long after its time, e.g. while paused), or the window needs more than the history's readable
     * half (a very fast device, see [ChartFrame]).
     */
    var truncated: Boolean = false
        private set

    /**
     * Copies the samples of [history] stamped in (`nowNanos - spanNanos`, `nowNanos`], plus the one just before the
     * window when there is one (so a line enters from the left edge). Never more than the history's readable half
     * or this window's capacity: the newest samples win. [selection]: the sensors summed into [selected].
     */
    fun copy(history: FootHistory, nowNanos: Long, spanNanos: Long, selection: Int = SensorSelection.ALL_MASK) {
        this.selection = selection
        val w = history.written // acquire: slots below w are complete
        val firstValid = history.firstValid
        val lowest = max(firstValid, w - min(history.readableSamples, capacity))
        size = 0
        firstInside = 0
        truncated = false
        if (w <= lowest) return
        val cap = history.capacity
        val times = history.times
        val from = nowNanos - spanNanos
        // First sample stamped after `from` (times increase from `lowest` to `w - 1`).
        var lo = lowest
        var hi = w
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[(mid % cap).toInt()] <= from) lo = mid + 1 else hi = mid
        }
        val start = if (lo > lowest) lo - 1 else lo
        // Older samples existed but cannot be read, and the oldest readable one is not before the window.
        val incomplete = lowest > firstValid && lo == lowest
        // Newer than now: not shown (a sample clock leading the frame clock by a little).
        var end = w
        while (end > start && times[((end - 1) % cap).toInt()] > nowNanos) end--
        var n = 0
        var k = start
        while (k < end) {
            val i = (k % cap).toInt()
            t[n] = ((times[i] - nowNanos) / 1e9).toFloat()
            total[n] = history.totals[i]
            var sum = 0f
            for (s in 0 until SENSOR_COUNT) {
                val v = history.loads[s][i]
                loads[s][n] = v
                if ((selection shr s) and 1 == 1 && v.isFinite()) sum += v
            }
            selected[n] = sum
            copX[n] = history.copX[i]
            copY[n] = history.copY[i]
            contact[n] = history.contact[i]
            breaks[n] = history.breaks[i] || k == start
            n++
            k++
        }
        beforeRecheck?.invoke()
        // The writer may have lapped into what we copied meanwhile (practically impossible). The slot of sample `w2`
        // may be half written right now, so everything before `w2 - cap + 1` is suspect: drop it. The volatile write
        // keeps the copy's plain reads before the volatile read below (no read moves after a volatile write, and a
        // volatile read never moves before one); a volatile read alone would not.
        fence = n
        val overwrittenBelow = history.written - cap + 1
        var lost = false
        if (overwrittenBelow > start) {
            val drop = (overwrittenBelow - start).coerceAtMost(n.toLong()).toInt()
            shiftLeft(drop, n)
            n -= drop
            if (n > 0) breaks[0] = true
            lost = true
        }
        size = n
        firstInside = if (n > 0 && t[0] < -spanNanos / 1e9f) 1 else 0
        truncated = incomplete || lost
    }

    /**
     * Copies the recorded samples of [track] in (`endMillis - spanMillis`, `endMillis`], plus the one just before,
     * with times in seconds relative to [endMillis] (so the chart draws them exactly like live data). A gap longer
     * than [maxGapMillis] (the link was down) breaks the line. With more samples than [capacity] every k-th is
     * taken. [selection]: the sensors summed into [selected]. Nothing is allocated.
     */
    fun copy(
        track: FootTrack,
        endMillis: Double,
        spanMillis: Double,
        maxGapMillis: Double,
        selection: Int = SensorSelection.ALL_MASK,
    ) {
        clear()
        this.selection = selection
        if (track.isEmpty()) return
        val from = endMillis - spanMillis
        val inside = track.range(from, endMillis)
        if (inside.isEmpty()) return
        val start = if (inside.first > 0) inside.first - 1 else inside.first
        val end = inside.last + 1
        val stride = (end - start + capacity - 1) / capacity
        var n = 0
        var k = start
        var previous = Double.NaN
        while (k < end && n < capacity) {
            val time = track.timeAt(k)
            t[n] = ((time - endMillis) / 1000).toFloat()
            total[n] = track.totalLoadAt(k).toFloat()
            var sum = 0f
            for (s in 0 until SENSOR_COUNT) {
                val v = track.loadAt(k, s).toFloat()
                loads[s][n] = v
                if ((selection shr s) and 1 == 1 && v.isFinite()) sum += v
            }
            selected[n] = sum
            copX[n] = track.copXAt(k).toFloat()
            copY[n] = track.copYAt(k).toFloat()
            contact[n] = track.inContactAt(k)
            breaks[n] = n == 0 || time - previous > maxGapMillis * stride
            previous = time
            n++
            k += stride
        }
        size = n
        firstInside = if (n > 0 && t[0] < -spanMillis / 1000) 1 else 0
    }

    fun clear() {
        size = 0
        firstInside = 0
        truncated = false
    }

    /** Largest total load inside the window (not the sample before it); NaN when none. */
    fun maxTotal(): Float = maxOf(total)

    /** Largest sum of the selected sensors' loads inside the window; NaN when none. */
    fun maxSelected(): Float = maxOf(selected)

    /** Largest single-sensor load inside the window among the sensors in [mask] (all by default); NaN when none. */
    fun maxLoad(mask: Int = SensorSelection.ALL_MASK): Float {
        var m = Float.NaN
        for (s in 0 until SENSOR_COUNT) if ((mask shr s) and 1 == 1) m = maxOrNaN(m, maxOf(loads[s]))
        return m
    }

    private fun maxOf(values: FloatArray): Float {
        var m = Float.NaN
        for (i in firstInside until size) m = maxOrNaN(m, values[i])
        return m
    }

    private fun shiftLeft(drop: Int, n: Int) {
        if (drop <= 0) return
        t.copyInto(t, 0, drop, n)
        total.copyInto(total, 0, drop, n)
        for (s in loads) s.copyInto(s, 0, drop, n)
        selected.copyInto(selected, 0, drop, n)
        copX.copyInto(copX, 0, drop, n)
        copY.copyInto(copY, 0, drop, n)
        contact.copyInto(contact, 0, drop, n)
        breaks.copyInto(breaks, 0, drop, n)
    }
}

/**
 * What the chart draws in one frame: both feet's windows ending at the same [nowNanos]. Not thread-safe: owned by
 * the UI thread, refreshed once per frame by [update].
 *
 * A window holds at most `capacity` samples (default: the history's readable half, 8192): 30 s up to about 270 Hz.
 * For a faster device (the protocol tops out at 200 Hz, so only one that ignores the rate command) the window shows
 * the newest 8192 samples and [truncated] says so.
 */
class ChartFrame(capacity: Int = FootHistory.DEFAULT_CAPACITY / 2) {
    val left = HistoryWindow(capacity)
    val right = HistoryWindow(capacity)

    var nowNanos: Long = 0
        private set

    var spanSeconds: Float = 10f
        private set

    fun foot(foot: Foot): HistoryWindow = if (foot == Foot.LEFT) left else right

    /** Some of the window's data is no longer available, see [HistoryWindow.truncated]. */
    val truncated: Boolean get() = left.truncated || right.truncated

    /** Largest total load of either foot; NaN when neither has data (a foot without data never hides the other). */
    fun maxTotal(): Float = maxOrNaN(left.maxTotal(), right.maxTotal())

    /** Largest sum of the selected sensors of either foot; NaN when neither has data. */
    fun maxSelected(): Float = maxOrNaN(left.maxSelected(), right.maxSelected())

    /**
     * A window of [timeline] ending at session time [endMillis]: what the player's chart draws. [selection]: the
     * sensors summed into [HistoryWindow.selected] (a [SensorSelection.mask]).
     */
    fun update(
        timeline: SessionTimeline,
        endMillis: Double,
        spanSeconds: Int,
        selection: Int = SensorSelection.ALL_MASK,
    ) {
        this.nowNanos = 0
        this.spanSeconds = spanSeconds.toFloat()
        val span = spanSeconds * 1000.0
        left.copy(timeline.track(Foot.LEFT), endMillis, span, timeline.staleAfterMillis(Foot.LEFT), selection)
        right.copy(timeline.track(Foot.RIGHT), endMillis, span, timeline.staleAfterMillis(Foot.RIGHT), selection)
    }

    fun update(history: LiveHistory, nowNanos: Long, spanSeconds: Int, selection: Int = SensorSelection.ALL_MASK) {
        this.nowNanos = nowNanos
        this.spanSeconds = spanSeconds.toFloat()
        val span = spanSeconds * 1_000_000_000L
        left.copy(history.left, nowNanos, span, selection)
        right.copy(history.right, nowNanos, span, selection)
    }
}

/** The larger of two values, ignoring NaN (NaN only if both are). */
internal fun maxOrNaN(a: Float, b: Float): Float = when {
    b.isNaN() -> a
    a.isNaN() -> b
    else -> max(a, b)
}

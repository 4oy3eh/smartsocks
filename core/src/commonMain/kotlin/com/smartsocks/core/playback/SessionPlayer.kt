package com.smartsocks.core.playback

import com.smartsocks.core.model.Foot
import com.smartsocks.core.session.monotonicClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** An A–B repeat range, [startMillis] < [endMillis]. */
data class LoopRange(val startMillis: Double, val endMillis: Double)

data class PlaybackState(
    val positionMillis: Double,
    val playing: Boolean = false,
    val speed: Double = 1.0,
    val loop: LoopRange? = null,
    /**
     * The position is at the end of the timeline. [SessionPlayer.play] then restarts from the start (of the loop,
     * if one is set).
     */
    val atEnd: Boolean = false,
)

/**
 * Plays a [SessionTimeline] like a video. While playing, the position follows the host clock ([clockNanos]) times
 * [PlaybackState.speed] and [state] is updated once per tick ([tickMillis], about display rate), never per
 * sample; the UI turns a position into pictures with [frameAt].
 *
 * Without a loop playback stops at the end; with one, reaching its end wraps to its start (keeping the overshoot,
 * so the loop's timing stays exact). Seeking outside the loop is allowed: from before its start playback runs into
 * it, from beyond its end it wraps to the start at the next tick.
 *
 * Not thread-safe: call it from the thread that [scope] dispatches to (normally the UI thread), which also runs
 * the ticker. The ticker is a child of [scope]; when [scope] is cancelled playback stops (`playing` turns false).
 * [stanceHalfWidth] is passed to the balance of every [frameAt].
 */
class SessionPlayer(
    val timeline: SessionTimeline,
    private val scope: CoroutineScope,
    private val clockNanos: () -> Long = monotonicClock(),
    val events: GaitEvents = GaitEvents.detect(timeline),
    private val tickMillis: Long = DEFAULT_TICK_MILLIS,
    val stanceHalfWidth: Double = 1.0,
) {
    init {
        require(tickMillis > 0) { "tickMillis must be positive" }
    }

    private val _state = MutableStateFlow(PlaybackState(timeline.startMillis, atEnd = timeline.durationMillis <= 0.0))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** [frameAt] of every new position. */
    val frames: Flow<PlaybackFrame> = state
        .distinctUntilChangedBy { it.positionMillis }
        .map { frameAt(it.positionMillis) }

    private var ticker: Job? = null

    /** Position and host time the current playback run is measured from; reset on every change. */
    private var anchorPosition = timeline.startMillis
    private var anchorNanos = 0L

    fun play() {
        if (_state.value.playing) return
        if (_state.value.atEnd) moveTo(_state.value.loop?.startMillis ?: timeline.startMillis)
        anchor(_state.value.positionMillis)
        _state.value = _state.value.copy(playing = true)
        // An empty or zero-length timeline: nothing to play.
        if (stopIfAtEnd()) return
        val job = scope.launch { tickLoop() }
        ticker = job
        // Also runs if the scope is (or gets) cancelled before the ticker ever ran.
        job.invokeOnCompletion {
            if (ticker === job) {
                ticker = null
                _state.value = _state.value.copy(playing = false)
            }
        }
    }

    fun pause() {
        if (!_state.value.playing) return
        advance()
        stop()
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    /** Moves to [tMillis], clamped to the timeline; keeps playing if it was. */
    fun seek(tMillis: Double) {
        require(!tMillis.isNaN()) { "tMillis must be a number" }
        moveTo(tMillis)
    }

    /** Allowed range 0.1..4.0. The position does not jump: the time played so far keeps the old speed. */
    fun setSpeed(speed: Double) {
        require(speed in MIN_SPEED..MAX_SPEED) { "speed must be in $MIN_SPEED..$MAX_SPEED, was $speed" }
        if (_state.value.playing) advance()
        anchor(_state.value.positionMillis)
        _state.value = _state.value.copy(speed = speed)
    }

    /**
     * Pauses and moves [steps] frames forward (positive) or back (negative). A frame is a sample time of any foot;
     * samples of the two feet closer than [SessionTimeline.frameMergeMillis] are one frame (see
     * [SessionTimeline.nextFrameTime]). Stops at the first/last frame.
     */
    fun stepFrame(steps: Int = 1) {
        require(steps != 0) { "steps must not be 0" }
        pause()
        var t = _state.value.positionMillis
        val forward = steps > 0
        for (i in 0 until if (forward) steps else -steps) {
            t = (if (forward) timeline.nextFrameTime(t) else timeline.previousFrameTime(t)) ?: break
        }
        moveTo(t)
    }

    /**
     * Repeats [aMillis]..[bMillis] (clamped to the timeline). If the position is outside, it moves to [aMillis].
     * @throws IllegalArgumentException unless a < b, also after clamping.
     */
    fun setLoop(aMillis: Double, bMillis: Double) {
        val a = aMillis.coerceIn(timeline.startMillis, timeline.endMillis)
        val b = bMillis.coerceIn(timeline.startMillis, timeline.endMillis)
        require(a < b) { "loop start must be before its end within the timeline: $aMillis..$bMillis" }
        if (_state.value.playing) advance()
        _state.value = _state.value.copy(loop = LoopRange(a, b))
        val position = _state.value.positionMillis
        if (position < a || position > b) moveTo(a) else anchor(position)
    }

    fun clearLoop() {
        if (_state.value.playing) advance()
        anchor(_state.value.positionMillis)
        _state.value = _state.value.copy(loop = null)
        stopIfAtEnd()
    }

    /** Seeks to the next gait event after the position; false (and no change) if there is none. */
    fun nextEvent(foot: Foot? = null, type: GaitEventType? = null): Boolean =
        jump(events.nextEvent(currentPosition(), foot, type)?.tMillis)

    /**
     * Seeks to the previous gait event. While playing, an event less than [PREVIOUS_GRACE_MILLIS] of host time
     * (scaled by the speed) behind the position is the one just jumped to, so it goes to the one before that, as a
     * media player's "previous" does.
     */
    fun previousEvent(foot: Foot? = null, type: GaitEventType? = null): Boolean {
        val position = currentPosition()
        var event = events.previousEvent(position, foot, type)
        if (event != null && justPassed(position, event.tMillis)) {
            event = events.previousEvent(event.tMillis, foot, type) ?: event
        }
        return jump(event?.tMillis)
    }

    fun nextMarker(): Boolean = jump(timeline.nextMarker(currentPosition())?.tMillis)

    /** Like [previousEvent], for markers. */
    fun previousMarker(): Boolean {
        val position = currentPosition()
        var marker = timeline.previousMarker(position)
        if (marker != null && justPassed(position, marker.tMillis)) {
            marker = timeline.previousMarker(marker.tMillis) ?: marker
        }
        return jump(marker?.tMillis)
    }

    /** What to show at [positionMillis] (by default the current position). */
    fun frameAt(positionMillis: Double = _state.value.positionMillis): PlaybackFrame =
        timeline.frameAt(positionMillis, stanceHalfWidth)

    private fun justPassed(position: Double, tMillis: Double): Boolean =
        _state.value.playing && position - tMillis < PREVIOUS_GRACE_MILLIS * _state.value.speed

    private fun jump(t: Double?): Boolean {
        if (t == null) return false
        moveTo(t)
        return true
    }

    private suspend fun tickLoop() {
        while (true) {
            delay(tickMillis)
            advance()
        }
    }

    /** The position right now, including the time since the last tick. */
    private fun currentPosition(): Double {
        if (_state.value.playing) advance()
        return _state.value.positionMillis
    }

    /** Brings the position up to the host clock; wraps the loop or stops at the end. */
    private fun advance() {
        val s = _state.value
        if (!s.playing) return
        val now = clockNanos()
        var position = anchorPosition + (now - anchorNanos) / NANOS_PER_MILLI * s.speed
        val loop = s.loop
        if (loop != null && position >= loop.endMillis) {
            // Played through the end: keep the overshoot. Started beyond it (a seek): back to the start.
            position = if (anchorPosition <= loop.endMillis) {
                loop.startMillis + (position - loop.endMillis) % (loop.endMillis - loop.startMillis)
            } else {
                loop.startMillis
            }
            anchorPosition = position
            anchorNanos = now
        }
        position = position.coerceAtMost(timeline.endMillis)
        _state.value = s.copy(positionMillis = position, atEnd = position >= timeline.endMillis)
        stopIfAtEnd()
    }

    /** Clamped seek that keeps the playing state; stops if it lands on the end without a loop. */
    private fun moveTo(tMillis: Double) {
        val position = tMillis.coerceIn(timeline.startMillis, timeline.endMillis)
        anchor(position)
        _state.value = _state.value.copy(positionMillis = position, atEnd = position >= timeline.endMillis)
        stopIfAtEnd()
    }

    private fun stopIfAtEnd(): Boolean {
        val s = _state.value
        if (!s.playing || !s.atEnd || s.loop != null) return false
        stop()
        return true
    }

    private fun stop() {
        val job = ticker
        ticker = null // first, so that its completion handler leaves the state alone
        job?.cancel()
        _state.value = _state.value.copy(playing = false)
    }

    private fun anchor(position: Double) {
        anchorPosition = position
        anchorNanos = clockNanos()
    }

    companion object {
        const val MIN_SPEED = 0.1
        const val MAX_SPEED = 4.0
        const val DEFAULT_TICK_MILLIS = 16L

        /** Host time after a jump during which "previous" skips the item just jumped to. */
        const val PREVIOUS_GRACE_MILLIS = 400.0
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}

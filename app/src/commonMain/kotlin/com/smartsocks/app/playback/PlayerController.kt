package com.smartsocks.app.playback

import com.smartsocks.app.live.LiveGeometry
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.app.recording.StoredSession
import com.smartsocks.core.playback.GaitEventType
import com.smartsocks.core.playback.GaitEvents
import com.smartsocks.core.playback.PlaybackState
import com.smartsocks.core.playback.SessionPlayer
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.settings.LiveView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

/** A recording read into memory and indexed: everything the player needs, built off the main thread. */
class LoadedSession(
    val session: StoredSession,
    val timeline: SessionTimeline,
    val events: GaitEvents,
    val overview: PlaybackOverview,
)

/**
 * The player screen's state and commands on top of core's [SessionPlayer] (position, speed, A–B loop, jumps). Lives
 * as long as the session is open, not as long as the screen: it survives an Android rotation, so position, speed
 * and loop stay. Like [SessionPlayer] it is not thread-safe: use it from the UI thread that [scope] runs on.
 */
class PlayerController(
    val loaded: LoadedSession,
    scope: CoroutineScope,
    clockNanos: () -> Long = monotonicNanos(),
    tickMillis: Long = SessionPlayer.DEFAULT_TICK_MILLIS,
) {
    val timeline: SessionTimeline get() = loaded.timeline
    val session: StoredSession get() = loaded.session
    val overview: PlaybackOverview get() = loaded.overview

    val player = SessionPlayer(
        loaded.timeline,
        scope,
        clockNanos,
        loaded.events,
        tickMillis,
        stanceHalfWidth = LiveGeometry.STANCE_HALF_WIDTH,
    )

    val state: StateFlow<PlaybackState> get() = player.state

    val views = PlaybackViews(loaded.timeline, fullScaleLoad = max(FULL_SCALE_FLOOR, overview.maxSensorLoad * 0.8))

    /** Heel strikes of both feet, sorted: the "step" jumps and the slider's ticks. */
    val steps: DoubleArray = loaded.events.events
        .filter { it.type == GaitEventType.HEEL_STRIKE }
        .map { it.tMillis }
        .toDoubleArray()

    private val _view = MutableStateFlow(LiveView.COP)

    /** The visualisation shown (not saved: the player opens on the COP view). */
    val view: StateFlow<LiveView> = _view.asStateFlow()

    fun setView(view: LiveView) {
        _view.value = view
    }

    private val pendingA = MutableStateFlow<Double?>(null)

    /** "A" is set and waits for "B"; null otherwise (also while a loop is active). */
    val pendingLoopStart: StateFlow<Double?> = pendingA.asStateFlow()

    val position: Double get() = state.value.positionMillis

    fun snapshot(positionMillis: Double = position): LiveSnapshot = views.snapshot(player.frameAt(positionMillis))

    fun toggle() = player.toggle()

    fun seek(tMillis: Double) {
        if (!tMillis.isNaN()) player.seek(tMillis)
    }

    /** [fraction] 0..1 of the whole session (the slider). */
    fun seekFraction(fraction: Double) = seek(timeline.startMillis + fraction.coerceIn(0.0, 1.0) * duration)

    fun stepFrame(steps: Int) = player.stepFrame(steps)

    /** Next heel strike of either foot. */
    fun nextStep(): Boolean = player.nextEvent(type = GaitEventType.HEEL_STRIKE)

    fun previousStep(): Boolean = player.previousEvent(type = GaitEventType.HEEL_STRIKE)

    fun nextMarker(): Boolean = player.nextMarker()

    fun previousMarker(): Boolean = player.previousMarker()

    fun setSpeed(speed: Double) = player.setSpeed(speed.coerceIn(SessionPlayer.MIN_SPEED, SessionPlayer.MAX_SPEED))

    /** The next of [SPEEDS] up (or down with [faster] false); stays at the ends. */
    fun stepSpeed(faster: Boolean) {
        val current = state.value.speed
        val next = if (faster) {
            SPEEDS.firstOrNull { it > current + 1e-9 }
        } else {
            SPEEDS.lastOrNull { it < current - 1e-9 }
        }
        if (next != null) setSpeed(next)
    }

    /**
     * Sets "A" at the position. With a loop already set and the position before its end, the loop starts here now;
     * otherwise A waits for [setLoopEnd].
     */
    fun setLoopStart() {
        val a = position
        val loop = state.value.loop
        if (loop != null && a < loop.endMillis - MIN_LOOP_MILLIS) {
            player.setLoop(a, loop.endMillis)
            pendingA.value = null
        } else {
            if (loop != null) player.clearLoop()
            pendingA.value = a
        }
    }

    /**
     * Sets "B" at the position: the loop runs from A (pending, or the current loop's start, or the session start) to
     * here; B before A swaps them. Ignored (false) when A and B are too close.
     */
    fun setLoopEnd(): Boolean {
        val b = position
        val a = pendingA.value ?: state.value.loop?.startMillis ?: timeline.startMillis
        if (abs(b - a) < MIN_LOOP_MILLIS) return false
        player.setLoop(min(a, b), max(a, b))
        pendingA.value = null
        return true
    }

    fun clearLoop() {
        pendingA.value = null
        player.clearLoop()
    }

    val duration: Double get() = timeline.durationMillis

    // Chart: a window of the chart's span that follows the position, the playhead a quarter from its right edge.

    /** The session time at the right edge of a chart window of [spanMillis] showing [positionMillis]. */
    fun chartWindowEnd(positionMillis: Double, spanMillis: Double): Double = positionMillis + spanMillis * CHART_LEAD

    /** Tap on the chart at [x] (pixels) of a plot from [plotLeft] to [plotRight]: seek to that moment. */
    fun seekFromChart(x: Float, plotLeft: Float, plotRight: Float, spanMillis: Double) {
        val end = chartWindowEnd(position, spanMillis)
        seek(chartTimeAt(x, plotLeft, plotRight, end, spanMillis))
    }

    /** Drag on the chart by [dx] pixels: the picture follows the finger (dragging right goes back in time). */
    fun dragChart(dx: Float, plotLeft: Float, plotRight: Float, spanMillis: Double) {
        val width = plotRight - plotLeft
        if (width <= 0f) return
        seek(position - dx / width * spanMillis)
    }

    companion object {
        val SPEEDS = listOf(0.1, 0.25, 0.5, 1.0, 2.0, 4.0)
        const val MIN_LOOP_MILLIS = 20.0
        const val CHART_LEAD = 0.25
        private const val FULL_SCALE_FLOOR = 40.0

        /** The session time at [x] on a chart whose plot spans [plotLeft]..[plotRight] and ends at [endMillis]. */
        fun chartTimeAt(x: Float, plotLeft: Float, plotRight: Float, endMillis: Double, spanMillis: Double): Double {
            val width = plotRight - plotLeft
            if (width <= 0f) return endMillis
            return endMillis + (x.coerceIn(plotLeft, plotRight) - plotRight) / width * spanMillis
        }

        fun speedText(speed: Double): String = when (speed) {
            0.1 -> "0.1×"
            0.25 -> "0.25×"
            0.5 -> "0.5×"
            else -> "${if (speed == speed.toLong().toDouble()) speed.toLong().toString() else speed.toString()}×"
        }

        /** "1:05.3" (tenths) or "12:01:05.3"; [tenths] false drops them. */
        fun timeText(millis: Double, tenths: Boolean = true): String {
            val negative = millis < 0
            val total = (abs(millis) / 100).toLong() // tenths of a second
            val t = total % 10
            val s = (total / 10) % 60
            val m = (total / 600) % 60
            val h = total / 36000
            val body = buildString {
                if (h > 0) append(h).append(':').append(m.toString().padStart(2, '0')) else append(m)
                append(':').append(s.toString().padStart(2, '0'))
                if (tenths) append('.').append(t)
            }
            return if (negative) "−$body" else body
        }

        private fun monotonicNanos(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeNanoseconds }
        }
    }
}

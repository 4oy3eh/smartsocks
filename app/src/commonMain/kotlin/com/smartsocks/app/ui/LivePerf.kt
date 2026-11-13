package com.smartsocks.app.ui

import com.smartsocks.app.settings.fixed
import kotlin.math.max
import kotlin.time.TimeSource

/**
 * Development aid: frame pacing of the live screen and the CPU time the chart spends on the UI thread per frame
 * (copying the history and recording its drawing; Skia's own rasterisation is not included). Reported by the
 * desktop app when `SMARTSOCKS_PERF=1`. UI thread only; does nothing unless [enabled].
 */
object LivePerf {
    var enabled = false

    private val origin = TimeSource.Monotonic.markNow()
    private var lastFrame = 0L
    private var frames = 0
    private var intervalSum = 0L
    private var intervalMax = 0L
    private var workSum = 0L
    private var workMax = 0L
    private var frameWork = 0L

    /** A displayed frame of the live screen (its frame clock); closes the previous frame's tally. */
    fun frame(frameNanos: Long) {
        if (!enabled) return
        if (lastFrame != 0L) {
            val interval = frameNanos - lastFrame
            frames++
            intervalSum += interval
            intervalMax = max(intervalMax, interval)
            workSum += frameWork
            workMax = max(workMax, frameWork)
        }
        lastFrame = frameNanos
        frameWork = 0
    }

    /** The frame loop goes to sleep (nothing streams): the time until the next frame is not a frame interval. */
    fun idle() {
        if (!enabled) return
        lastFrame = 0
        frameWork = 0
    }

    internal fun start(): Long = if (enabled) origin.elapsedNow().inWholeNanoseconds else 0L

    /** Adds the time since [start] to the current frame's work. */
    internal fun work(start: Long) {
        if (enabled) frameWork += origin.elapsedNow().inWholeNanoseconds - start
    }

    /** "frames 600, fps 120.0, interval avg 8.33 ms max 9.10 ms, chart work avg 1.00 ms max 2.00 ms"; null if idle. */
    fun report(): String? {
        if (frames == 0) return null
        val text = "frames $frames, fps ${fixed(1e9 * frames / intervalSum, 1)}, " +
            "interval avg ${ms(intervalSum / frames)} max ${ms(intervalMax)}, " +
            "chart work (copy + draw) avg ${ms(workSum / frames)} max ${ms(workMax)}"
        frames = 0
        intervalSum = 0
        intervalMax = 0
        workSum = 0
        workMax = 0
        lastFrame = 0
        return text
    }

    private fun ms(nanos: Long) = "${fixed(nanos / 1e6, 2)} ms"
}

package com.smartsocks.core.playback

import com.smartsocks.core.model.Foot

enum class GaitEventType {
    /** Contact begins (initial contact; usually, but not necessarily, the heel). */
    HEEL_STRIKE,

    /** Contact ends. */
    TOE_OFF,

    /** Highest total load of a complete stance (between a [HEEL_STRIKE] and the next [TOE_OFF]). */
    PEAK_LOAD,
}

data class GaitEvent(val foot: Foot, val type: GaitEventType, val tMillis: Double)

/**
 * Steps found in a [SessionTimeline], derived from the recorded `contact` flag, for "jump to next step" and step
 * statistics. [events] is sorted by time (then foot, then type).
 */
class GaitEvents(events: List<GaitEvent>) {
    val events: List<GaitEvent> = events.sortedWith(compareBy({ it.tMillis }, { it.foot }, { it.type }))

    fun forFoot(foot: Foot): List<GaitEvent> = events.filter { it.foot == foot }

    /** First event strictly after [tMillis], optionally only of [foot] and/or [type]. */
    fun nextEvent(tMillis: Double, foot: Foot? = null, type: GaitEventType? = null): GaitEvent? {
        var i = firstAfter(tMillis)
        while (i < events.size) {
            val e = events[i]
            if (e.matches(foot, type)) return e
            i++
        }
        return null
    }

    /** Last event strictly before [tMillis], optionally only of [foot] and/or [type]. */
    fun previousEvent(tMillis: Double, foot: Foot? = null, type: GaitEventType? = null): GaitEvent? {
        var i = firstAtOrAfter(tMillis) - 1
        while (i >= 0) {
            val e = events[i]
            if (e.matches(foot, type)) return e
            i--
        }
        return null
    }

    private fun GaitEvent.matches(foot: Foot?, type: GaitEventType?) =
        (foot == null || this.foot == foot) && (type == null || this.type == type)

    private fun firstAfter(t: Double): Int = search { it.tMillis <= t }

    private fun firstAtOrAfter(t: Double): Int = search { it.tMillis < t }

    /** First index for which [before] is false; [before] must be true for a prefix of [events]. */
    private inline fun search(before: (GaitEvent) -> Boolean): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (before(events[mid])) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val DEFAULT_MIN_PHASE_MILLIS = 40.0

        /**
         * Debounced contact transitions of every foot. A contact change only counts once the new state has lasted
         * at least [minPhaseMillis] (measured from its first sample to the first sample of the next change, the
         * last stretch extended by one sample period of the foot); shorter flickers are ignored, and an
         * event is stamped at the first sample of the stretch that lasted. The initial state is that of the first
         * stretch that lasts; nothing before it produces an event. A recording that starts in contact gets no heel
         * strike for that first stance, and [GaitEventType.PEAK_LOAD] is only reported for complete stances.
         */
        fun detect(timeline: SessionTimeline, minPhaseMillis: Double = DEFAULT_MIN_PHASE_MILLIS): GaitEvents {
            require(minPhaseMillis >= 0.0) { "minPhaseMillis must not be negative" }
            val out = ArrayList<GaitEvent>()
            for (foot in timeline.feet) {
                detectFoot(timeline.track(foot), minPhaseMillis, timeline.samplePeriodMillis(foot), out)
            }
            return GaitEvents(out)
        }

        private fun detectFoot(track: FootTrack, minPhase: Double, period: Double, out: MutableList<GaitEvent>) {
            if (track.isEmpty()) return
            var state: Boolean? = null // unknown until the first stretch that lasts
            var stanceStart = -1 // index of the accepted heel strike of the current stance
            var runStart = 0
            while (runStart < track.size) {
                val runState = track.inContactAt(runStart)
                var runEnd = runStart + 1
                while (runEnd < track.size && track.inContactAt(runEnd) == runState) runEnd++
                val duration = if (runEnd < track.size) {
                    track.timeAt(runEnd) - track.timeAt(runStart)
                } else {
                    track.timeAt(track.size - 1) - track.timeAt(runStart) + period
                }
                if (state == null) {
                    if (duration >= minPhase) state = runState
                } else if (runState != state && duration >= minPhase) {
                    state = runState
                    val t = track.timeAt(runStart)
                    if (runState) {
                        out += GaitEvent(track.foot, GaitEventType.HEEL_STRIKE, t)
                        stanceStart = runStart
                    } else {
                        out += GaitEvent(track.foot, GaitEventType.TOE_OFF, t)
                        if (stanceStart >= 0) out += peak(track, stanceStart, runStart)
                        stanceStart = -1
                    }
                }
                runStart = runEnd
            }
        }

        /** Time of the highest total load in [from, until); the first one on ties. */
        private fun peak(track: FootTrack, from: Int, until: Int): GaitEvent {
            var best = from
            for (i in from + 1 until until) if (track.totalLoadAt(i) > track.totalLoadAt(best)) best = i
            return GaitEvent(track.foot, GaitEventType.PEAK_LOAD, track.timeAt(best))
        }
    }
}

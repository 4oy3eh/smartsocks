package com.smartsocks.app.live

import kotlin.math.roundToLong

/**
 * How long [LiveController] waits before retrying a foot whose link dropped or failed to open: doubling from
 * [INITIAL_MILLIS] up to [MAX_MILLIS] with every try that does not get the link streaming, each wait shortened by a
 * random part of up to [JITTER] (so two feet that dropped together do not retry in lockstep, and a wait never
 * exceeds the cap). A try that streams, but drops again within [STABLE_MILLIS], keeps the wait where it was; a link
 * that streamed at least [STABLE_MILLIS] starts the sequence over. See [Progress].
 */
object ReconnectBackoff {
    const val INITIAL_MILLIS = 1_000L
    const val MAX_MILLIS = 30_000L
    const val JITTER = 0.1
    const val STABLE_MILLIS = 10_000L

    /** The first step whose wait is [MAX_MILLIS]: steps do not grow beyond it. */
    const val MAX_STEP = 6

    /**
     * Where a foot is in the sequence: [attempt] is the retry number shown (since the link was last stable), [step]
     * picks the wait ([delayMillis]). Starts at zero; [next] after every link that ended.
     */
    data class Progress(val attempt: Int = 0, val step: Int = 0) {
        /**
         * After a link (or a try) ended. [streamedMillis]: how long it streamed, null if it never got there. Never
         * streamed: one step up. Streamed briefly: the same step (at least the first). Streamed [STABLE_MILLIS] or
         * more: the first retry again.
         */
        fun next(streamedMillis: Long?): Progress = when {
            streamedMillis == null -> Progress(attempt + 1, minOf(step + 1, MAX_STEP))
            streamedMillis >= STABLE_MILLIS -> Progress(1, 1)
            else -> Progress(attempt + 1, maxOf(step, 1))
        }
    }

    /**
     * The wait at [step] (1 = the first retry after a drop, see [Progress]): 1 s, 2 s, 4 s, 8 s, 16 s, 30 s, 30 s…
     * less up to 10 %; [random] in 0..1 picks how much (0: the full wait).
     */
    fun delayMillis(step: Int, random: Double): Long {
        require(step >= 1) { "step $step" }
        // 2^5 s already exceeds the cap: no need to shift further (and no overflow for huge steps).
        val base = minOf(INITIAL_MILLIS shl minOf(step - 1, 5), MAX_MILLIS)
        return (base * (1 - JITTER * random.coerceIn(0.0, 1.0))).roundToLong()
    }

    /** The countdown shown while waiting [remainingMillis]: whole seconds, rounded up (never 0 while waiting). */
    fun secondsLeft(remainingMillis: Long): Int = ((remainingMillis + 999) / 1000).toInt().coerceAtLeast(1)
}

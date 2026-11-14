package com.smartsocks.app.live

import com.smartsocks.app.settings.fixed
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.FootRegion
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.session.StreamStats
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The app's names for the sensors, in channel order: the one place they come from. Side and row are taken from the
 * [layout] ([SensorLayout.isMedial], [SensorLayout.region]), which is where the medial/lateral assumption about the
 * hardware lives (`SensorLayout.ODD_CHANNELS_MEDIAL`). The right insole is the mirror image of the left one, so
 * channel i has the same name on both feet.
 */
class SensorNames(val layout: SensorLayout) {
    /** Channel numbers: "1" ... "6". */
    val numbers: List<String> = List(SENSOR_COUNT) { "${it + 1}" }

    /** On the medial (big-toe) side. */
    val medial: List<Boolean> = List(SENSOR_COUNT) { layout.isMedial(it) }

    /** "Forefoot medial", "Heel lateral". */
    val full: List<String> = List(SENSOR_COUNT) { "${regionName(layout.region(it))} ${side(it)}" }

    /** "Fore med", "Heel lat": chips and legends. */
    val short: List<String> = List(SENSOR_COUNT) { "${regionShort(layout.region(it))} ${side(it).take(3)}" }

    /** "1 · Fore medial": number, row and side. */
    val labels: List<String> = List(SENSOR_COUNT) { "${it + 1} · ${regionShort(layout.region(it))} ${side(it)}" }

    private fun side(i: Int) = if (layout.isMedial(i)) MEDIAL else LATERAL

    companion object {
        const val MEDIAL = "medial"
        const val LATERAL = "lateral"

        val Default = SensorNames(LiveGeometry.layout)

        fun regionName(region: FootRegion): String = when (region) {
            FootRegion.FORE -> "Forefoot"
            FootRegion.MID -> "Midfoot"
            FootRegion.HEEL -> "Heel"
        }

        fun regionShort(region: FootRegion): String = when (region) {
            FootRegion.FORE -> "Fore"
            FootRegion.MID -> "Mid"
            FootRegion.HEEL -> "Heel"
        }
    }
}

/** One sensor of the numbers view, formatted. */
data class SensorNumbers(
    /** "1 · Fore medial", see [SensorNames.labels]. */
    val name: String,
    /** kΩ without the unit, "open" for an open circuit (or over the range), "–" while not streaming. */
    val resistance: String,
    /** µS without the unit. */
    val load: String,
    /** Share of the foot's load, 0..1 (0 in the air or while not streaming). */
    val share: Float,
    /** Colour intensity of the load on the shared scale, 0..1, see [loadLevel] (0 while not streaming). */
    val level: Float = 0f,
)

/** Everything the numbers view shows for one foot, formatted. Equal content compares equal (no needless redraws). */
data class FootNumbers(
    val foot: Foot,
    val status: FootStatus,
    /** Streaming: the values below are live; otherwise they are dashes. */
    val live: Boolean,
    val sensors: List<SensorNumbers>,
    val total: String,
    /** This foot's share of both feet's load, "52%". */
    val share: String,
    /** Medial positive on either foot. */
    val copX: String,
    val copY: String,
    val contact: String,
    val baseline: String,
    /** "98/s · 100 Hz". */
    val rate: String,
    val dropped: String,
    val rateMismatch: Boolean,
    /** Replaces the link state in the header (playback), see [FootView.caption]. */
    val caption: String? = null,
) {
    companion object {
        const val NONE = "–"

        private const val LEVEL_STEPS = 64f

        /**
         * +1 if medial is +x on [foot]'s insole, −1 if it is −x: the right insole is the left one mirrored, so its
         * medial side (towards the other foot) is −x.
         */
        fun medialSign(foot: Foot): Double = if (foot == Foot.LEFT) 1.0 else -1.0

        /**
         * [balanceShare]: this foot's share of the combined load (0..1), null when neither foot carries load.
         * [fullScaleLoad]: the load (µS) drawn at full colour, shared by both feet (see [LiveSnapshot.fullScaleLoad]).
         */
        fun of(
            view: FootView,
            stats: StreamStats?,
            balanceShare: Double?,
            fullScaleLoad: Double = Double.NaN,
            names: SensorNames = SensorNames.Default,
        ): FootNumbers {
            val state = view.state
            val status = view.status
            if (state == null) {
                return FootNumbers(
                    foot = view.foot,
                    status = status,
                    live = false,
                    sensors = List(SENSOR_COUNT) { SensorNumbers(names.labels[it], NONE, NONE, 0f) },
                    total = NONE,
                    share = NONE,
                    copX = NONE,
                    copY = NONE,
                    contact = NONE,
                    baseline = NONE,
                    rate = NONE,
                    dropped = NONE,
                    rateMismatch = false,
                    caption = view.caption,
                )
            }
            val cop = state.cop
            return FootNumbers(
                foot = view.foot,
                status = status,
                live = true,
                sensors = List(SENSOR_COUNT) {
                    SensorNumbers(
                        names.labels[it],
                        kiloOhms(state.resistanceOhms[it]),
                        microSiemens(state.loads[it]),
                        state.shares[it].toFloat().takeIf { s -> s.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                        // In steps, so that a level that barely moved compares equal (no redraw).
                        (loadLevel(state.loads[it], fullScaleLoad) * LEVEL_STEPS).roundToInt() / LEVEL_STEPS,
                    )
                },
                total = "${microSiemens(state.totalLoad)} µS",
                share = balanceShare?.takeIf { it.isFinite() }?.let { "${(it * 100).roundToInt()}%" } ?: NONE,
                copX = cop?.let { signed(it.x * medialSign(view.foot)) } ?: NONE,
                copY = cop?.let { signed(it.y) } ?: NONE,
                contact = if (state.inContact) "Yes" else "No",
                baseline = baselineText(state.baselineSource),
                rate = rateText(stats ?: StreamStats(), status.rate),
                dropped = stats?.let { bytes(it.droppedBytes) } ?: NONE,
                rateMismatch = status.rate.rateMismatch,
                caption = view.caption,
            )
        }

        /** "12.3" kΩ (2 decimals below 10 kΩ, none from 100 kΩ), "open" for an infinite or invalid resistance. */
        fun kiloOhms(ohms: Double): String {
            if (!ohms.isFinite() || ohms < 0) return "open"
            val k = ohms / 1000
            return when {
                k < 10 -> fixed(k, 2)
                k < 100 -> fixed(k, 1)
                else -> fixed(k, 0)
            }
        }

        /** One decimal; "–" for a non-finite value. */
        fun microSiemens(value: Double): String = if (value.isFinite()) fixed(value, 1) else NONE

        /** "+0.12", "−0.34" (a real minus sign, so signed columns line up), "0.00". */
        fun signed(value: Double): String {
            val text = fixed(abs(value), 2)
            return when {
                text == fixed(0.0, 2) -> text
                value > 0 -> "+$text"
                else -> "−$text"
            }
        }

        fun baselineText(source: BaselineSource): String = when (source) {
            BaselineSource.CALIBRATED -> "Calibrated"
            BaselineSource.AUTO -> "Auto-zero"
            BaselineSource.NONE -> "None"
        }

        private fun rateText(stats: StreamStats, rate: LinkRate): String {
            val nominal = if (rate.nominalHz > 0) rate.nominalHz else stats.nominalHz
            return if (nominal > 0) "${rate.packetsPerSecond}/s · $nominal Hz" else "${rate.packetsPerSecond}/s"
        }

        private fun bytes(n: Long): String = if (n == 1L) "1 byte" else "$n bytes"
    }
}

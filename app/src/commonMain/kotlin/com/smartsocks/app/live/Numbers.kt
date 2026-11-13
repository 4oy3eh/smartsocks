package com.smartsocks.app.live

import com.smartsocks.app.settings.fixed
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.session.StreamStats
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Anatomical sensor names in channel order, derived from the layout of the LEFT insole (+x medial, +y towards the
 * toes); the right insole is its mirror image, so channel i has the same name on both feet.
 */
class SensorNames(layout: SensorLayout) {
    /** "Forefoot medial", "Heel lateral". */
    val full: List<String>

    /** "Fore med", "Heel lat": legends. */
    val short: List<String>

    init {
        val positions = layout.positions(Foot.LEFT)
        full = positions.map { "${region(it.y).first} ${if (it.x >= 0) "medial" else "lateral"}" }
        short = positions.map { "${region(it.y).second} ${if (it.x >= 0) "med" else "lat"}" }
    }

    private fun region(y: Double): Pair<String, String> = when {
        y > REGION_LIMIT -> "Forefoot" to "Fore"
        y < -REGION_LIMIT -> "Heel" to "Heel"
        else -> "Midfoot" to "Mid"
    }

    companion object {
        private const val REGION_LIMIT = 0.45

        val Default = SensorNames(LiveGeometry.layout)
    }
}

/** One row of the numbers table, formatted. */
data class SensorNumbers(
    val name: String,
    /** kΩ without the unit, "open" for an open circuit (or over the range), "–" while not streaming. */
    val resistance: String,
    /** µS without the unit. */
    val load: String,
    /** Share of the foot's load, 0..1 (0 in the air or while not streaming). */
    val share: Float,
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
    val copX: String,
    val copY: String,
    val contact: String,
    val baseline: String,
    /** "98/s · 100 Hz". */
    val rate: String,
    val dropped: String,
    val rateMismatch: Boolean,
) {
    companion object {
        const val NONE = "–"

        /** [balanceShare]: this foot's share of the combined load (0..1), null when neither foot carries load. */
        fun of(
            view: FootView,
            stats: StreamStats?,
            balanceShare: Double?,
            names: SensorNames = SensorNames.Default,
        ): FootNumbers {
            val state = view.state
            val status = view.status
            if (state == null) {
                return FootNumbers(
                    foot = view.foot,
                    status = status,
                    live = false,
                    sensors = List(SENSOR_COUNT) { SensorNumbers(names.full[it], NONE, NONE, 0f) },
                    total = NONE,
                    share = NONE,
                    copX = NONE,
                    copY = NONE,
                    contact = NONE,
                    baseline = NONE,
                    rate = NONE,
                    dropped = NONE,
                    rateMismatch = false,
                )
            }
            val cop = state.cop
            return FootNumbers(
                foot = view.foot,
                status = status,
                live = true,
                sensors = List(SENSOR_COUNT) {
                    SensorNumbers(
                        names.full[it],
                        kiloOhms(state.resistanceOhms[it]),
                        microSiemens(state.loads[it]),
                        state.shares[it].toFloat().takeIf { s -> s.isFinite() }?.coerceIn(0f, 1f) ?: 0f,
                    )
                },
                total = "${microSiemens(state.totalLoad)} µS",
                share = balanceShare?.takeIf { it.isFinite() }?.let { "${(it * 100).roundToInt()}%" } ?: NONE,
                copX = cop?.let { signed(it.x) } ?: NONE,
                copY = cop?.let { signed(it.y) } ?: NONE,
                contact = if (state.inContact) "Yes" else "No",
                baseline = baselineText(state.baselineSource),
                rate = rateText(stats ?: StreamStats(), status.rate),
                dropped = stats?.let { bytes(it.droppedBytes) } ?: NONE,
                rateMismatch = status.rate.rateMismatch,
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

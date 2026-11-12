package com.smartsocks.core.playback

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.recording.CsvMarker
import com.smartsocks.core.recording.CsvRow
import com.smartsocks.core.recording.RecordedSession

/** A synthetic row: [load] spread evenly over the sensors, in contact (with a COP) iff [contact]. */
internal fun row(
    t: Double,
    foot: Foot = Foot.LEFT,
    load: Double = 0.0,
    contact: Boolean = load > 0.0,
    cop: Point? = if (contact) Point(0.1, 0.2) else null,
    baseline: BaselineSource = BaselineSource.NONE,
) = CsvRow(
    tMillis = t,
    foot = foot,
    resistanceOhms = DoubleArray(6) { if (contact) 1000.0 * (it + 1) else Double.POSITIVE_INFINITY },
    loads = DoubleArray(6) { load / 6 },
    totalLoad = load,
    cop = cop,
    rawCop = cop?.let { Point(it.x + 0.01, it.y) },
    inContact = contact,
    baseline = baseline,
)

internal fun recorded(
    rows: List<CsvRow>,
    markers: List<CsvMarker> = emptyList(),
    rateHz: Int? = 100,
): RecordedSession = RecordedSession(
    header = if (rateHz == null) emptyMap() else mapOf("sample_rate_hz" to rateHz.toString()),
    rows = rows,
    markers = markers,
)

/** Rows every [periodMillis] in [fromMillis, untilMillis); contact follows [contactAt], the load then [loadAt]. */
internal fun rows(
    foot: Foot,
    fromMillis: Int,
    untilMillis: Int,
    periodMillis: Int = 10,
    loadAt: (Int) -> Double = { 50.0 },
    contactAt: (Int) -> Boolean,
): List<CsvRow> = (fromMillis until untilMillis step periodMillis).map { t ->
    val c = contactAt(t)
    row(t.toDouble(), foot, load = if (c) loadAt(t) else 0.0, contact = c)
}

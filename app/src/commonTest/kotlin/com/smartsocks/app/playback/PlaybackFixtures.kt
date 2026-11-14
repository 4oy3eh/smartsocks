package com.smartsocks.app.playback

import com.smartsocks.app.recording.SessionHeader
import com.smartsocks.app.recording.StoredSession
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.playback.GaitEvents
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.playback.SessionTimelineBuilder
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.recording.CsvFootJoin
import com.smartsocks.core.recording.CsvMarker
import com.smartsocks.core.recording.CsvRow

/**
 * [seconds] of both feet at [hz]: each foot in contact for the first 600 ms of every second (the right foot half a
 * second later), total load rising to 300 µS mid-stance, COP moving heel to toe. [rightJoinsAt]: the right foot
 * connected only then (a late join): no samples before, and the join is on record.
 */
fun syntheticTimeline(
    seconds: Int = 10,
    hz: Int = 100,
    markers: List<CsvMarker> = listOf(CsvMarker(2500.0, "Mark 1"), CsvMarker(7000.0, "Mark 2")),
    feet: Set<Foot> = Foot.entries.toSet(),
    rightJoinsAt: Double? = null,
): SessionTimeline {
    val builder = SessionTimelineBuilder()
    val period = 1000.0 / hz
    for (i in 0 until seconds * hz) {
        val t = i * period
        for (foot in feet) {
            if (foot == Foot.RIGHT && rightJoinsAt != null && t < rightJoinsAt) continue
            val phase = ((t + if (foot == Foot.RIGHT) 500.0 else 0.0) % 1000.0) / 600.0
            val contact = phase < 1.0
            val total = if (contact) 300.0 * kotlin.math.sin(phase * kotlin.math.PI) + 25 else 0.0
            builder.add(
                CsvRow(
                    tMillis = t,
                    foot = foot,
                    resistanceOhms = DoubleArray(SENSOR_COUNT) { if (contact) 20_000.0 else Double.POSITIVE_INFINITY },
                    loads = DoubleArray(SENSOR_COUNT) { total / SENSOR_COUNT },
                    totalLoad = total,
                    cop = if (contact) Point(0.0, -1.0 + 2 * phase) else null,
                    rawCop = if (contact) Point(0.0, -1.0 + 2 * phase) else null,
                    inContact = contact,
                    baseline = BaselineSource.AUTO,
                ),
            )
        }
    }
    val joins = listOfNotNull(rightJoinsAt?.let { CsvFootJoin(Foot.RIGHT, it) })
    val fromStart = if (rightJoinsAt == null) feet else feet - Foot.RIGHT
    return builder.build(
        mapOf("smartsocks-csv" to "2", "sample_rate_hz" to "$hz", "feet" to fromStart.joinToString("+") { it.name }),
        markers,
        joins,
    )
}

fun storedSession(id: String = "2026-11-13_14-05-22", rateHz: Int = 100) = StoredSession(
    id = id,
    sizeBytes = 1000,
    modifiedMillis = 0,
    header = SessionHeader(rateHz = rateHz, feet = Foot.entries.toSet()),
    summary = null,
    hasRaw = false,
)

fun loadedSession(timeline: SessionTimeline = syntheticTimeline()) =
    LoadedSession(storedSession(), timeline, GaitEvents.detect(timeline), PlaybackOverview.of(timeline, 100))

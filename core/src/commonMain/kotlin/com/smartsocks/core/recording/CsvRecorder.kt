package com.smartsocks.core.recording

import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.TimedPacket

/**
 * Describes a recording in its header. [startTimeIso] comes from the caller: core has no wall clock. [feet]: the feet
 * recorded from the start; another foot may join later (see [CsvRecorder]). [layout]: the sensor layout the COP
 * columns were computed with (the session's).
 */
data class RecordingMetadata(
    val startTimeIso: String,
    val rate: SampleRate,
    val range: MeasurementRange,
    val feet: Set<Foot>,
    val note: String = "SmartSocks",
    val layout: SensorLayout = SensorLayout.Legacy,
)

/** A foot that joined a recording after it started, at its first sample [tMillis] (the file's time axis). */
data class CsvFootJoin(val foot: Foot, val tMillis: Double)

internal const val CSV_FORMAT_KEY = "smartsocks-csv"
internal const val CSV_FORMAT_VERSION = 2
internal const val RAW_FORMAT_VERSION = 2
internal const val RAW_FORMAT_KEY = "smartsocks-raw"
internal const val RAW_COLUMNS = "t_ms,host_ms,foot,hex"

/** Version 1 had no `host_ms`. */
internal const val RAW_COLUMNS_V1 = "t_ms,foot,hex"
internal const val MARKER_PREFIX = "# marker "

/** `# foot_joined,<foot>,<t_ms>`: no `=` in it, so readers that predate it skip it as a comment. */
internal const val FOOT_JOINED_PREFIX = "# foot_joined,"

/**
 * Header key of the sensor layout: the LEFT insole's positions in channel order, `x:y` pairs separated by `;`
 * (`# sensor_layout=0.2590:0.9660;-0.2590:0.9660;...`). A `key=value` line like the others, which readers that
 * predate it keep as an unknown header entry.
 */
internal const val LAYOUT_KEY = "sensor_layout"

internal fun encodeLayout(layout: SensorLayout): String =
    layout.positions(Foot.LEFT).joinToString(";") { "${formatNumber(it.x, 4)}:${formatNumber(it.y, 4)}" }

/** Null if [text] is not a valid layout. */
internal fun decodeLayout(text: String): SensorLayout? {
    val points = text.trim().split(';').map { pair ->
        val xy = pair.split(':')
        if (xy.size != 2) return null
        val x = xy[0].trim().toDoubleOrNull() ?: return null
        val y = xy[1].trim().toDoubleOrNull() ?: return null
        Point(x, y)
    }
    return runCatching { SensorLayout(points) }.getOrNull()
}

internal val CSV_COLUMNS: List<String> = buildList {
    add("t_ms")
    add("foot")
    for (i in 1..SENSOR_COUNT) add("r$i")
    for (i in 1..SENSOR_COUNT) add("load$i")
    addAll(listOf("total_load", "cop_x", "cop_y", "raw_cop_x", "raw_cop_y", "contact", "baseline"))
}

/** Version 1 differed only in the last column, `calibrated` (1/0) instead of `baseline`. */
internal val CSV_COLUMNS_V1: List<String> = CSV_COLUMNS.dropLast(1) + "calibrated"

/**
 * Writes [FootState]s as CSV: `#` header lines, a column line, then one row per sample. Resistance `r1..r6` is in
 * Ohms, loads in µS, time in ms since the first recorded sample. `baseline` is `none`, `auto` or `calibrated`.
 *
 * `cop_x`/`cop_y` (smoothed) and `raw_cop_x`/`raw_cop_y` are in the coordinates of the sensor layout written in the
 * `# sensor_layout=` header ([LAYOUT_KEY]): +y towards the toes, **+x to the person's right on both feet** (so +x is
 * medial on the left foot and lateral on the right one), in the layout's units; empty while the foot is in the air.
 * They are only meaningful with that layout: [CsvSessionReader] converts them when it reads with another one. Files
 * without the header (all before it) used [SensorLayout.legacy] with odd channels medial.
 *
 * Rows are in **arrival order**, not sorted by `t_ms`. With two feet in one file the first recorded sample defines
 * t = 0, and the other foot's sample clock may be anchored slightly earlier, so `t_ms` can be a little negative.
 *
 * The header's `feet` are the feet recorded from the start. The first sample of any other foot (one that connected
 * only after the recording started) is preceded by a `# foot_joined,<foot>,<t_ms>` line with that sample's time; its
 * rows share the time axis, so the feet stay aligned. Readers of format version 2 that predate the line skip it.
 * Not thread-safe: feed it from one coroutine.
 */
class CsvRecorder(private val sink: RecordingSink, metadata: RecordingMetadata) {
    private var startNanos: Long? = null
    private var lastNanos = 0L

    /** Per [Foot.ordinal]: in the header, or joined since. */
    private val present = BooleanArray(Foot.entries.size).also { for (foot in metadata.feet) it[foot.ordinal] = true }
    private val _joins = mutableListOf<CsvFootJoin>()

    /** The feet that joined after the start, in the order they did; see [CsvRecorder]. */
    val joins: List<CsvFootJoin> get() = _joins

    init {
        sink.writeLine("# $CSV_FORMAT_KEY=$CSV_FORMAT_VERSION")
        sink.writeLine("# start_time=${metadata.startTimeIso}")
        sink.writeLine("# sample_rate_hz=${metadata.rate.hz}")
        sink.writeLine("# range=${metadata.range.name}")
        sink.writeLine("# feet=${metadata.feet.joinToString("+") { it.name }}")
        sink.writeLine("# note=${metadata.note.oneLine()}")
        sink.writeLine("# $LAYOUT_KEY=${encodeLayout(metadata.layout)}")
        sink.writeLine(CSV_COLUMNS.joinToString(","))
    }

    fun record(state: FootState) {
        val start = startNanos ?: state.timestampNanos.also { startNanos = it }
        lastNanos = state.timestampNanos
        val t = formatMillis(state.timestampNanos - start)
        if (!present[state.foot.ordinal]) {
            present[state.foot.ordinal] = true
            _joins += CsvFootJoin(state.foot, parseNumber(t))
            sink.writeLine("$FOOT_JOINED_PREFIX${state.foot.name},$t")
        }
        val line = StringBuilder()
        line.append(t)
        line.append(',').append(state.foot.name)
        for (ohms in state.resistanceOhms) line.append(',').append(formatNumber(ohms, 1))
        for (load in state.loads) line.append(',').append(formatNumber(load, 3))
        line.append(',').append(formatNumber(state.totalLoad, 3))
        appendPoint(line, state.cop)
        appendPoint(line, state.rawCop)
        line.append(',').append(if (state.inContact) '1' else '0')
        line.append(',').append(state.baselineSource.name.lowercase())
        sink.writeLine(line.toString())
    }

    /** Inserts a labelled time mark (the legacy app's "break" button) at the time of the last recorded sample. */
    fun marker(label: String) {
        val t = formatMillis(startNanos?.let { lastNanos - it } ?: 0L)
        sink.writeLine("${MARKER_PREFIX}t_ms=$t label=${label.oneLine()}")
    }

    fun close() = sink.close()

    private fun appendPoint(line: StringBuilder, p: Point?) {
        if (p == null) {
            line.append(",,")
        } else {
            line.append(',').append(formatNumber(p.x, 4)).append(',').append(formatNumber(p.y, 4))
        }
    }
}

/**
 * Writes every packet as `t_ms,host_ms,foot,hex` (47 bytes, lowercase hex), for decoding the unknown fields
 * offline. `t_ms` is the sample-clock stamp, `host_ms` the real receive time; both are relative to the first
 * recorded packet (its stamp and its host time), rows are in arrival order and t_ms may be slightly negative for
 * the other foot, as in [CsvRecorder].
 */
class RawPacketRecorder(private val sink: RecordingSink) {
    private var startNanos: Long? = null
    private var startHostNanos = 0L

    init {
        sink.writeLine("# $RAW_FORMAT_KEY=$RAW_FORMAT_VERSION")
        sink.writeLine(RAW_COLUMNS)
    }

    fun record(timed: TimedPacket) {
        val start = startNanos ?: timed.timestampNanos.also {
            startNanos = it
            startHostNanos = timed.hostNanos
        }
        val t = formatMillis(timed.timestampNanos - start)
        val host = formatMillis(timed.hostNanos - startHostNanos)
        sink.writeLine("$t,$host,${timed.foot.name},${timed.packet.bytes.toHex()}")
    }

    fun close() = sink.close()
}

private fun String.oneLine(): String = replace('\r', ' ').replace('\n', ' ')

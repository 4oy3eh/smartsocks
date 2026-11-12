package com.smartsocks.core.recording

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.protocol.SENSOR_COUNT

/** One recorded sample. Mirrors [com.smartsocks.core.analytics.FootState] minus the derived shares. */
class CsvRow(
    val tMillis: Double,
    val foot: Foot,
    val resistanceOhms: DoubleArray,
    val loads: DoubleArray,
    val totalLoad: Double,
    val cop: Point?,
    val rawCop: Point?,
    val inContact: Boolean,
    val baseline: BaselineSource,
)

data class CsvMarker(val tMillis: Double, val label: String)

/** [header] holds the `# key=value` lines (without the marker lines). */
class RecordedSession(val header: Map<String, String>, val rows: List<CsvRow>, val markers: List<CsvMarker>)

/** Everything of a recording except its rows, for [CsvSessionReader.stream]. */
class CsvSessionInfo(val header: Map<String, String>, val markers: List<CsvMarker>)

/** Reads what [CsvRecorder] wrote, in format version 2 and the older version 1 (`calibrated` 1/0 as the baseline). */
object CsvSessionReader {
    /**
     * Keeps every row in memory: fine for short recordings. For long ones use [stream].
     * @throws IllegalArgumentException on a missing/unsupported format version or a malformed row.
     */
    fun read(lines: Iterable<String>): RecordedSession {
        val rows = mutableListOf<CsvRow>()
        val info = stream(lines) { rows += it }
        return RecordedSession(info.header, rows, info.markers)
    }

    /**
     * Hands every row to [onRow] as soon as it is parsed, in file order, without keeping it: an hour at 200 Hz is
     * 1.4 million rows. [lines] are consumed once (e.g. a file's line sequence); blocking I/O, so call it off the
     * main thread.
     * @throws IllegalArgumentException as [read]; rows before the bad line have been delivered already.
     */
    fun stream(lines: Iterable<String>, onRow: (CsvRow) -> Unit): CsvSessionInfo {
        val header = LinkedHashMap<String, String>()
        val markers = mutableListOf<CsvMarker>()
        var sawColumns = false
        var version = 0

        for ((index, raw) in lines.withIndex()) {
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> Unit

                line.startsWith(MARKER_PREFIX) -> markers += parseMarker(line, index)

                line.startsWith("#") -> {
                    val body = line.removePrefix("#").trim()
                    val eq = body.indexOf('=')
                    if (eq > 0) header[body.substring(0, eq)] = body.substring(eq + 1)
                }

                !sawColumns -> {
                    version = checkVersion(header)
                    val expected = if (version == 1) CSV_COLUMNS_V1 else CSV_COLUMNS
                    require(line == expected.joinToString(",")) { "Line ${index + 1}: unexpected column header" }
                    sawColumns = true
                }

                else -> onRow(
                    try {
                        parseRow(line, version)
                    } catch (e: IllegalArgumentException) {
                        throw IllegalArgumentException("Line ${index + 1}: ${e.message}", e)
                    },
                )
            }
        }
        checkVersion(header)
        return CsvSessionInfo(header, markers)
    }

    private fun checkVersion(header: Map<String, String>): Int {
        val version = header[CSV_FORMAT_KEY]?.toIntOrNull()
        require(version == 1 || version == CSV_FORMAT_VERSION) {
            "Unsupported or missing $CSV_FORMAT_KEY version: $version"
        }
        return version!!
    }

    private fun parseMarker(line: String, index: Int): CsvMarker {
        val body = line.removePrefix(MARKER_PREFIX)
        require(body.startsWith("t_ms=")) { "Line ${index + 1}: malformed marker" }
        val space = body.indexOf(' ')
        val time = parseNumber(body.substring("t_ms=".length, if (space < 0) body.length else space))
        require(time.isFinite()) { "Line ${index + 1}: marker time must be finite" }
        val label = if (space < 0) "" else body.substring(space + 1).removePrefix("label=")
        return CsvMarker(time, label)
    }

    private fun parseRow(line: String, version: Int): CsvRow {
        val f = line.split(',')
        require(f.size == CSV_COLUMNS.size) { "expected ${CSV_COLUMNS.size} fields, got ${f.size}" }
        val loadsAt = 2 + SENSOR_COUNT
        val totalAt = loadsAt + SENSOR_COUNT
        val t = parseNumber(f[0])
        // inf/nan are valid for resistances (open circuit), never for the time axis.
        require(t.isFinite()) { "t_ms must be finite, was '${f[0]}'" }
        return CsvRow(
            tMillis = t,
            foot = Foot.valueOf(f[1]),
            resistanceOhms = DoubleArray(SENSOR_COUNT) { parseNumber(f[2 + it]) },
            loads = DoubleArray(SENSOR_COUNT) { parseNumber(f[loadsAt + it]) },
            totalLoad = parseNumber(f[totalAt]),
            cop = point(f[totalAt + 1], f[totalAt + 2]),
            rawCop = point(f[totalAt + 3], f[totalAt + 4]),
            inContact = f[totalAt + 5] == "1",
            baseline = if (version == 1) {
                if (f[totalAt + 6] == "1") BaselineSource.CALIBRATED else BaselineSource.NONE
            } else {
                BaselineSource.valueOf(f[totalAt + 6].uppercase())
            },
        )
    }

    private fun point(x: String, y: String): Point? = if (x.isEmpty() ||
        y.isEmpty()
    ) {
        null
    } else {
        Point(parseNumber(x), parseNumber(y))
    }
}

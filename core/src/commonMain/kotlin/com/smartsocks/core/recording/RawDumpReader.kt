package com.smartsocks.core.recording

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.PacketFormat

/** Lines of a text read once from the start (e.g. a file's); [close] releases what backs them. */
class LineSource(lines: Iterator<String>, private val onClose: () -> Unit = {}) :
    Iterator<String> by lines,
    AutoCloseable {
    override fun close() = onClose()
}

/**
 * Reads what [RawPacketRecorder] wrote, one packet at a time and without keeping any: format version 2
 * (`t_ms,host_ms,foot,hex`) and the older version 1 (`t_ms,foot,hex`). [next] moves to the next packet of [foot]
 * (of every foot with null); only the packets of the feet in [bytesOf] have their [packet] bytes decoded (and their
 * hex checked), the others only their time and foot ([hasBytes] false).
 *
 * A packet's [tMillis] is its `t_ms` (the sample-clock stamp), or its `host_ms` where `t_ms` is not a finite number.
 * As in [CsvSessionReader.stream], a malformed **last** line is dropped ([truncatedLastLine]: a dump cut short by a
 * crash, or still being written); anywhere else it is an error, thrown by the [next] that reads the line after it.
 * Blocking I/O as [lines] does it; not thread-safe.
 */
class RawDumpReader(
    private val lines: Iterator<String>,
    private val foot: Foot? = null,
    private val bytesOf: Set<Foot> = Foot.entries.toSet(),
) {
    private val _header = LinkedHashMap<String, String>()

    /** The `# key=value` lines read so far (all of them once the first packet is read). */
    val header: Map<String, String> get() = _header

    /** The format version, from the column line; 0 before it. */
    var version: Int = 0
        private set

    /** The current packet's time on the dump's axis, ms. */
    var tMillis: Double = 0.0
        private set

    /** The current packet's receive time, ms; only read where `t_ms` is not usable (NaN otherwise, and in v1). */
    var hostMillis: Double = Double.NaN
        private set

    var packetFoot: Foot = Foot.LEFT
        private set

    /** [packet] holds the current packet's bytes: its foot is in [bytesOf]. */
    var hasBytes: Boolean = false
        private set

    /** The current packet's 47 bytes, exactly as recorded (see [hasBytes]). Overwritten by the next [next]. */
    val packet = ByteArray(PacketFormat.SIZE)

    /** The dump ended in a malformed line, which was left out. Known once [next] returned false. */
    var truncatedLastLine: Boolean = false
        private set

    private var lineNumber = 0
    private var pendingError: IllegalArgumentException? = null

    /**
     * Moves to the next packet (of [foot]); false at the end of the dump.
     * @throws IllegalArgumentException on a missing or unsupported format, or a malformed line that is not the last.
     */
    fun next(): Boolean {
        while (lines.hasNext()) {
            val raw = lines.next()
            val line = if (raw.endsWith(CR)) raw.substring(0, raw.length - 1) else raw
            lineNumber++
            if (line.isBlank()) continue
            pendingError?.let { throw it }
            when {
                line[0] == '#' -> {
                    val body = line.substring(1).trim()
                    val eq = body.indexOf('=')
                    if (eq > 0) _header[body.substring(0, eq)] = body.substring(eq + 1)
                }

                version == 0 -> version = columnsVersion(line)

                else -> try {
                    if (parse(line)) return true
                } catch (e: IllegalArgumentException) {
                    pendingError = IllegalArgumentException("Line $lineNumber: ${e.message}", e)
                }
            }
        }
        truncatedLastLine = pendingError != null
        if (version == 0) checkVersion()
        return false
    }

    private fun checkVersion(): Int {
        val declared = _header[RAW_FORMAT_KEY]?.trim()?.toIntOrNull()
        require(declared == 1 || declared == RAW_FORMAT_VERSION) {
            "Not a raw packet dump (unsupported or missing $RAW_FORMAT_KEY version: $declared)"
        }
        return declared!!
    }

    private fun columnsVersion(line: String): Int {
        val declared = checkVersion()
        val expected = if (declared == 1) RAW_COLUMNS_V1 else RAW_COLUMNS
        require(line == expected) { "Line $lineNumber: unexpected column header" }
        return declared
    }

    /** True for a packet of [foot]; throws for a malformed line. */
    private fun parse(line: String): Boolean {
        val c1 = line.indexOf(',')
        val c2 = if (c1 < 0) -1 else line.indexOf(',', c1 + 1)
        val c3 = if (c2 < 0 || version == 1) -1 else line.indexOf(',', c2 + 1)
        val footStart: Int
        val hexStart: Int
        if (version == 1) {
            require(c2 > 0 && line.indexOf(',', c2 + 1) < 0) { "expected 3 fields" }
            footStart = c1 + 1
            hexStart = c2 + 1
        } else {
            require(c3 > 0 && line.indexOf(',', c3 + 1) < 0) { "expected 4 fields" }
            footStart = c2 + 1
            hexStart = c3 + 1
        }
        val f = footAt(line, footStart, hexStart - 1) ?: throw IllegalArgumentException("unknown foot")
        if (foot != null && f != foot) return false
        val decode = f in bytesOf
        if (decode) {
            require(decodeHex(line, hexStart, line.length, packet)) { "expected ${PacketFormat.SIZE} hex bytes" }
        }
        val t = parseMillis(line, 0, c1)
        val host = if (t.isFinite() || version == 1) Double.NaN else parseMillis(line, c1 + 1, c2)
        tMillis = when {
            t.isFinite() -> t
            host.isFinite() -> host
            else -> throw IllegalArgumentException("no finite time")
        }
        hostMillis = host
        packetFoot = f
        hasBytes = decode
        return true
    }

    private fun footAt(line: String, start: Int, end: Int): Foot? {
        for (f in Foot.entries) {
            if (end - start == f.name.length && line.regionMatches(start, f.name, 0, f.name.length)) return f
        }
        return null
    }

    internal companion object {
        private const val CR = '\r'
        private const val MAX_WHOLE_DIGITS = 15
        private const val MAX_FRACTION_DIGITS = 9

        /**
         * `line[start until end]` as a number, NaN if it is none. What the recorder writes (`-?digits.digits`) is
         * read without allocating; anything else (`inf`, an exponent) goes through [String.toDoubleOrNull].
         */
        fun parseMillis(line: String, start: Int, end: Int): Double {
            if (start >= end) return Double.NaN
            var i = start
            val negative = line[i] == '-'
            if (negative) i++
            var whole = 0L
            var digits = 0
            while (i < end && line[i] in '0'..'9' && digits < MAX_WHOLE_DIGITS) {
                whole = whole * 10 + (line[i] - '0')
                i++
                digits++
            }
            if (digits > 0 && i == end) return if (negative) -whole.toDouble() else whole.toDouble()
            if (digits > 0 && line[i] == '.') {
                i++
                var fraction = 0L
                var scale = 1L
                var fractionDigits = 0
                while (i < end && line[i] in '0'..'9' && fractionDigits < MAX_FRACTION_DIGITS) {
                    fraction = fraction * 10 + (line[i] - '0')
                    scale *= 10
                    i++
                    fractionDigits++
                }
                if (i == end && fractionDigits > 0) {
                    val value = whole + fraction.toDouble() / scale
                    return if (negative) -value else value
                }
            }
            return line.substring(start, end).toDoubleOrNull() ?: Double.NaN
        }
    }
}

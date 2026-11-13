package com.smartsocks.app.recording

import com.smartsocks.core.model.Foot
import com.smartsocks.core.recording.RecordingSink

/** What a recording's `#` header says about it (read from the first lines only). */
data class SessionHeader(val startTimeIso: String? = null, val rateHz: Int? = null, val feet: Set<Foot> = emptySet()) {
    companion object {
        /** From the header map of [com.smartsocks.core.recording.CsvSessionReader]; unknown values are left out. */
        fun of(header: Map<String, String>): SessionHeader = SessionHeader(
            startTimeIso = header["start_time"]?.takeIf { it.isNotBlank() },
            rateHz = header["sample_rate_hz"]?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            feet = header["feet"].orEmpty().split('+').mapNotNull { name ->
                Foot.entries.firstOrNull { it.name == name.trim() }
            }.toSet(),
        )
    }
}

/**
 * What is only known after reading a whole recording: written by the recorder when it finishes and cached after the
 * first full read (a recording cut short by a crash has none until it is opened once).
 */
data class SessionSummary(
    val durationMillis: Double,
    val feet: Set<Foot>,
    val rows: Long,
    val markers: Int,
    /** Samples the recorder had to drop because the storage could not keep up. */
    val droppedRows: Long = 0,
    /** Raw packets dropped for the same reason (the raw file only). */
    val droppedRaw: Long = 0,
) {
    fun encode(): String = buildString {
        append("$FORMAT_KEY=1\n")
        append("duration_ms=").append(durationMillis).append('\n')
        append("feet=").append(feet.sorted().joinToString("+") { it.name }).append('\n')
        append("rows=").append(rows).append('\n')
        append("markers=").append(markers).append('\n')
        append("dropped_rows=").append(droppedRows).append('\n')
        append("dropped_raw=").append(droppedRaw).append('\n')
    }

    companion object {
        const val FORMAT_KEY = "smartsocks-summary"

        /** Null for anything that is not a complete summary (e.g. a half-written file). */
        fun decode(text: String): SessionSummary? {
            val values = text.lineSequence().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq <= 0) null else line.substring(0, eq).trim() to line.substring(eq + 1).trim()
            }.toMap()
            if (values[FORMAT_KEY] != "1") return null
            return SessionSummary(
                durationMillis = values["duration_ms"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?: return null,
                feet = SessionHeader.of(mapOf("feet" to values["feet"].orEmpty())).feet,
                rows = values["rows"]?.toLongOrNull() ?: return null,
                markers = values["markers"]?.toIntOrNull() ?: return null,
                droppedRows = values["dropped_rows"]?.toLongOrNull() ?: 0,
                droppedRaw = values["dropped_raw"]?.toLongOrNull() ?: 0,
            )
        }
    }
}

/** A recording in a [SessionStore], as the sessions list shows it. */
data class StoredSession(
    /** Identifies the recording in its store: the base file name, e.g. `2026-11-13_14-05-22`. */
    val id: String,
    /** Recording and raw packet dump together. */
    val sizeBytes: Long,
    /** When the file was last written, epoch milliseconds: the list's order (newest first). */
    val modifiedMillis: Long,
    val header: SessionHeader,
    val summary: SessionSummary?,
    val hasRaw: Boolean,
) {
    /** Duration if known, from the summary. */
    val durationMillis: Double? get() = summary?.durationMillis

    /** Feet with data (summary), else the feet the header announced. */
    val feet: Set<Foot> get() = summary?.feet?.takeIf { it.isNotEmpty() } ?: header.feet
}

/** The files of a recording being written. [csv] gets the processed samples, [raw] (if asked for) every packet. */
class NewRecording(val id: String, val startTimeIso: String, val csv: RecordingSink, val raw: RecordingSink?)

/**
 * Where recordings live: a directory on desktop, the app's files on Android. Every method does blocking I/O: call
 * them off the main thread. Methods throw (I/O errors, [IllegalArgumentException] for an invalid name) rather than
 * fail silently.
 */
interface SessionStore {
    /** Where the recordings are, for the user ("Documents\SmartSocks"). */
    val locationText: String

    /** Every recording, newest first; reads only headers and summaries, never whole recordings. */
    fun list(): List<StoredSession>

    /** Creates the files of a new recording, named after the local time now (made unique if taken). */
    fun create(saveRaw: Boolean): NewRecording

    /**
     * Stores the [summary] of recording [id] next to it, atomically. Without [replace] an existing summary stays
     * (the recorder's final one always wins over one computed from a read).
     */
    fun writeSummary(id: String, summary: SessionSummary, replace: Boolean = true)

    /**
     * Opens recording [id] and hands its lines (consumed once) and its size in bytes to [block]; closes it after.
     * @throws NoSuchElementException if there is no such recording.
     */
    fun <T> read(id: String, block: (lines: Iterable<String>, sizeBytes: Long) -> T): T

    /** Deletes the recording with its raw dump and summary. */
    fun delete(id: String)

    /**
     * Renames recording [id] (all its files) to [newName], trimmed.
     * @throws IllegalArgumentException if [newName] is not a valid file name or is taken.
     */
    fun rename(id: String, newName: String): StoredSession
}

object SessionNames {
    private val forbidden = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    /** Why [name] cannot name a recording, or null if it can. Windows rules, so files can move between systems. */
    fun problem(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "Enter a name"
            n.length > 100 -> "Keep it under 100 characters"
            n.any { it in forbidden || it < ' ' } -> "A name cannot contain < > : \" / \\ | ? *"
            n.endsWith('.') -> "A name cannot end with a dot"
            n.endsWith(".raw") || n.endsWith(".summary") -> "Choose another name"
            else -> null
        }
    }

    /** `2026-11-13_14-05-22` → `2026-11-13 14:05:22`; any other name as it is. */
    fun display(id: String): String {
        val m = TIMESTAMP.matchEntire(id) ?: return id
        val (date, h, min, s, suffix) = m.destructured
        return "$date $h:$min:$s$suffix"
    }

    private val TIMESTAMP = Regex("""(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})(.*)""")
}

/**
 * Passes [source] through while counting its characters (≈ bytes: recordings are ASCII), reporting the count every
 * [every] lines through [onProgress] and calling [checkCancelled] there too (throw from it to stop a long read).
 */
class CountingLines(
    private val source: Iterable<String>,
    private val every: Int = 4096,
    private val checkCancelled: () -> Unit = {},
    private val onProgress: (chars: Long) -> Unit,
) : Iterable<String> {
    override fun iterator(): Iterator<String> = object : Iterator<String> {
        private val inner = source.iterator()
        private var chars = 0L
        private var lines = 0

        override fun hasNext(): Boolean {
            val more = inner.hasNext()
            if (!more) onProgress(chars)
            return more
        }

        override fun next(): String {
            val line = inner.next()
            chars += line.length + 1
            if (++lines % every == 0) {
                checkCancelled()
                onProgress(chars)
            }
            return line
        }
    }
}

package com.smartsocks.app.recording

import com.smartsocks.core.recording.InMemoryRecordingSink
import com.smartsocks.core.recording.RecordingSink

/**
 * A [SessionStore] in memory, for tests; [failCreate] makes [create] throw, [failWriteAfter] makes the CSV sink fail
 * after that many lines (a full disk). [onRead] runs before each line handed to a reader (index from 0).
 */
class MemorySessionStore(var failCreate: Boolean = false, var failWriteAfter: Int = Int.MAX_VALUE) : SessionStore {
    /** [sink] is what the recorder writes to (counts flushes, may fail), [csv] what it wrote. */
    class Entry(val csv: InMemoryRecordingSink, val raw: InMemoryRecordingSink?, val sink: FailingSink)

    val entries = LinkedHashMap<String, Entry>()

    /** Recordings put in directly (e.g. a corrupt one), by id. */
    val texts = LinkedHashMap<String, List<String>>()

    /** Summaries of every recording, recorded or put in. */
    val summaries = LinkedHashMap<String, SessionSummary>()

    var reads = 0
        private set

    var onRead: ((id: String, index: Int) -> Unit)? = null

    override val locationText: String = "memory"

    private var counter = 0

    fun lines(id: String): List<String> = texts[id] ?: entries.getValue(id).csv.lines

    override fun list(): List<StoredSession> = (entries.keys + texts.keys).distinct().reversed().map { id ->
        val lines = lines(id)
        val header = lines.takeWhile { it.startsWith("#") }.mapNotNull {
            val body = it.removePrefix("#").trim()
            val eq = body.indexOf('=')
            if (eq > 0) body.substring(0, eq) to body.substring(eq + 1) else null
        }.toMap()
        StoredSession(
            id = id,
            sizeBytes = lines.sumOf { it.length + 1L },
            modifiedMillis = 0,
            header = SessionHeader.of(header),
            summary = summaries[id],
            hasRaw = entries[id]?.raw != null,
        )
    }

    override fun create(saveRaw: Boolean): NewRecording {
        if (failCreate) throw IllegalStateException("Disk full")
        val id = "rec-${++counter}"
        val csv = InMemoryRecordingSink()
        val entry = Entry(csv, if (saveRaw) InMemoryRecordingSink() else null, FailingSink(csv, failWriteAfter))
        entries[id] = entry
        return NewRecording(id, "2026-11-13T14:05:22+01:00", entry.sink, entry.raw)
    }

    override fun writeSummary(id: String, summary: SessionSummary, replace: Boolean) {
        if (!replace && id in summaries) return
        summaries[id] = summary
    }

    override fun <T> read(id: String, block: (lines: Iterable<String>, sizeBytes: Long) -> T): T {
        reads++
        val lines = lines(id)
        val hook = onRead
        val handed = if (hook == null) {
            lines
        } else {
            Iterable {
                val inner = lines.iterator()
                var index = 0
                object : Iterator<String> {
                    override fun hasNext() = inner.hasNext()

                    override fun next(): String {
                        hook(id, index++)
                        return inner.next()
                    }
                }
            }
        }
        return block(handed, lines.sumOf { it.length + 1L })
    }

    override fun delete(id: String) {
        entries.remove(id)
        texts.remove(id)
        summaries.remove(id)
    }

    override fun rename(id: String, newName: String): StoredSession {
        SessionNames.problem(newName)?.let { throw IllegalArgumentException(it) }
        entries.remove(id)?.let { entries[newName] = it }
        texts.remove(id)?.let { texts[newName] = it }
        summaries.remove(id)?.let { summaries[newName] = it }
        return list().first { it.id == newName }
    }
}

/** Passes lines on until [failAfter] of them were written, then throws; counts flushes. */
class FailingSink(private val inner: RecordingSink, var failAfter: Int = Int.MAX_VALUE) : RecordingSink {
    private var written = 0

    var flushes = 0
        private set

    /** Lines written at the last flush. */
    var flushedLines = 0
        private set

    override fun writeLine(line: String) {
        if (written >= failAfter) throw IllegalStateException("No space left on device")
        written++
        inner.writeLine(line)
    }

    override fun flush() {
        flushes++
        flushedLines = written
    }

    override fun close() = inner.close()
}

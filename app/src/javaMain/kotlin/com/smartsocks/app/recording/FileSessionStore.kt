package com.smartsocks.app.recording

import com.smartsocks.core.recording.LineSource
import com.smartsocks.core.recording.RecordingSink
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Recordings as files in [dir]: `<name>.csv` (processed samples), `<name>.raw.csv` (packets, optional) and
 * `<name>.summary` (written when a recording finishes). [now] is the local time new recordings are named after;
 * [move] renames one file (tests make it fail).
 */
class FileSessionStore(
    val dir: File,
    override val locationText: String = dir.path,
    private val now: () -> OffsetDateTime = { OffsetDateTime.now() },
    private val move: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
) : SessionStore {
    fun csvFile(id: String) = File(dir, "$id$CSV")

    fun rawFile(id: String) = File(dir, "$id$RAW")

    private fun summaryFile(id: String) = File(dir, "$id$SUMMARY")

    /** The files of recording [id] that exist: the recording first. */
    fun files(id: String): List<File> = listOf(csvFile(id), rawFile(id)).filter { it.isFile }

    override fun list(): List<StoredSession> {
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(CSV) && !it.name.endsWith(RAW) }
            .mapNotNull { file ->
                val id = file.name.removeSuffix(CSV)
                runCatching { stored(id) }.getOrNull()
            }
            .sortedWith(compareByDescending<StoredSession> { it.modifiedMillis }.thenByDescending { it.id })
            .toList()
    }

    private fun stored(id: String): StoredSession {
        val csv = csvFile(id)
        if (!csv.isFile) throw NoSuchElementException("No recording $id")
        val raw = rawFile(id)
        return StoredSession(
            id = id,
            sizeBytes = csv.length() + (if (raw.isFile) raw.length() else 0L),
            modifiedMillis = csv.lastModified(),
            header = SessionHeader.of(readHeader(csv)),
            summary = summaryFile(id).takeIf { it.isFile }?.let { SessionSummary.decode(it.readText()) },
            hasRaw = raw.isFile,
        )
    }

    /** The `# key=value` lines before the column line. */
    private fun readHeader(file: File): Map<String, String> {
        val header = LinkedHashMap<String, String>()
        file.bufferedReader().use { reader ->
            for (i in 0 until MAX_HEADER_LINES) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("#")) break
                val body = line.removePrefix("#").trim()
                val eq = body.indexOf('=')
                if (eq > 0) header[body.substring(0, eq)] = body.substring(eq + 1)
            }
        }
        return header
    }

    override fun create(saveRaw: Boolean): NewRecording {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val time = now()
        val base = time.toLocalDateTime().format(NAME_FORMAT)
        var id = base
        var n = 2
        while (taken(id)) id = "$base-${n++}"
        val csv = FileSink(csvFile(id))
        val raw = if (saveRaw) {
            try {
                FileSink(rawFile(id))
            } catch (e: IOException) {
                csv.close()
                throw e
            }
        } else {
            null
        }
        return NewRecording(id, time.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), csv, raw)
    }

    private fun taken(id: String) = csvFile(id).exists() || rawFile(id).exists() || summaryFile(id).exists()

    override fun writeSummary(id: String, summary: SessionSummary, replace: Boolean) {
        val target = summaryFile(id).toPath()
        if (!replace && Files.exists(target)) return
        // A name of its own: two writers never share a temporary file.
        val tmp = File.createTempFile("$id$SUMMARY.", ".tmp", dir).toPath()
        try {
            Files.write(tmp, summary.encode().toByteArray(Charsets.UTF_8))
            val options = if (replace) arrayOf(StandardCopyOption.REPLACE_EXISTING) else emptyArray()
            try {
                Files.move(tmp, target, *options, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, target, *options)
            }
        } catch (_: FileAlreadyExistsException) {
            // Not replacing, and another writer was first: its summary stays.
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override fun <T> read(id: String, block: (lines: Iterable<String>, sizeBytes: Long) -> T): T {
        val file = csvFile(id)
        if (!file.isFile) throw NoSuchElementException("The recording $id is gone")
        return file.bufferedReader(Charsets.UTF_8, BUFFER_CHARS).use { reader ->
            block(Iterable { reader.lineSequence().iterator() }, file.length())
        }
    }

    override fun find(id: String): StoredSession? = if (csvFile(id).isFile) {
        runCatching {
            stored(id)
        }.getOrNull()
    } else {
        null
    }

    override fun openRaw(id: String): LineSource {
        val file = rawFile(id)
        if (!file.isFile) throw NoSuchElementException("The recording $id has no raw packet dump")
        // NIO opens it with FILE_SHARE_DELETE on Windows: the recording can still be deleted or renamed meanwhile.
        val reader = BufferedReader(
            InputStreamReader(Files.newInputStream(file.toPath()), Charsets.UTF_8),
            BUFFER_CHARS,
        )
        return LineSource(reader.lineSequence().iterator(), reader::close)
    }

    /** The recording itself last: if a file cannot be deleted, the recording stays listed, never an orphan. */
    override fun delete(id: String) {
        for (file in listOf(rawFile(id), summaryFile(id), csvFile(id))) {
            if (file.exists() && !file.delete()) throw IOException("Cannot delete ${file.name}")
        }
    }

    override fun rename(id: String, newName: String): StoredSession {
        val name = newName.trim()
        SessionNames.problem(name)?.let { throw IllegalArgumentException(it) }
        if (name == id) return stored(id)
        if (!csvFile(id).isFile) throw NoSuchElementException("The recording $id is gone")
        val pairs = listOf(
            csvFile(id) to csvFile(name),
            rawFile(id) to rawFile(name),
            summaryFile(id) to summaryFile(name),
        )
        // A target that is the source itself (only the case differs, on a case-insensitive disk) is not "taken".
        val taken = pairs.any { (from, to) ->
            to.exists() && !(from.exists() && Files.isSameFile(from.toPath(), to.toPath()))
        }
        if (taken) throw IllegalArgumentException("A recording named \"$name\" already exists")
        val moves = pairs.filter { it.first.exists() }
        val done = mutableListOf<Pair<File, File>>()
        for ((from, to) in moves) {
            if (!move(from, to)) {
                // Put back what was moved, so the recording never ends up split between two names.
                for ((f, t) in done.asReversed()) move(t, f)
                throw IOException("Cannot rename ${from.name} (is it open in another program?)")
            }
            done += from to to
        }
        return stored(name)
    }

    /** Buffered UTF-8 lines; [flush] pushes them to the OS, [close] syncs the file to disk. */
    private class FileSink(file: File) : RecordingSink {
        private val stream = FileOutputStream(file)
        private val writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8), BUFFER_CHARS)

        override fun writeLine(line: String) {
            writer.write(line)
            writer.write('\n'.code)
        }

        override fun flush() = writer.flush()

        override fun close() {
            try {
                writer.flush()
                runCatching { stream.fd.sync() }
            } finally {
                writer.close()
            }
        }
    }

    companion object {
        const val CSV = ".csv"
        const val RAW = ".raw.csv"
        const val SUMMARY = ".summary"
        private const val MAX_HEADER_LINES = 32
        private const val BUFFER_CHARS = 64 * 1024
        private val NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }
}

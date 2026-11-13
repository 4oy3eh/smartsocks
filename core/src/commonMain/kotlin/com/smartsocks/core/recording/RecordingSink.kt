package com.smartsocks.core.recording

/** Destination of recorded text, one line at a time. Platform code implements it over a file. */
interface RecordingSink {
    fun writeLine(line: String)

    /** Pushes buffered lines to the storage, so that a crash loses at most what came after. */
    fun flush() = Unit

    fun close()
}

/** Keeps everything in memory; for tests and for short recordings that are saved afterwards. */
class InMemoryRecordingSink : RecordingSink {
    private val _lines = mutableListOf<String>()
    val lines: List<String> get() = _lines

    var closed: Boolean = false
        private set

    override fun writeLine(line: String) {
        check(!closed) { "Sink is closed" }
        _lines += line
    }

    override fun close() {
        closed = true
    }
}

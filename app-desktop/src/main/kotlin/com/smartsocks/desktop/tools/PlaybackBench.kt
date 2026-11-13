package com.smartsocks.desktop.tools

import com.smartsocks.app.playback.LoadedSession
import com.smartsocks.app.playback.loadSession
import com.smartsocks.app.recording.FileSessionStore
import com.smartsocks.app.recording.SessionSummary
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.sim.SimulatedRecording
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import kotlin.system.measureNanoTime

/**
 * Development tool: writes a simulated two-foot 200 Hz recording of the given length (default 10 minutes) with the
 * raw packet dump, then opens it as the player does (streaming read → timeline → gait events → overview) and prints
 * the time and the heap the loaded session takes. Args: `[minutes] [dir]`; with a dir the recording is kept there
 * (e.g. for `SMARTSOCKS_SESSIONS_DIR` + `SMARTSOCKS_PLAY`), otherwise a temporary folder is deleted afterwards.
 */
fun main(args: Array<String>) {
    val minutes = args.getOrNull(0)?.toDoubleOrNull() ?: 10.0
    val keep = args.getOrNull(1)?.let(::File)
    val dir = keep ?: Files.createTempDirectory("smartsocks-bench").toFile()
    try {
        val store = FileSessionStore(dir)
        val rec = store.create(saveRaw = true)
        lateinit var result: SimulatedRecording.Result
        val markers = listOf(minutes * 60 * 0.2 to "Mark 1", minutes * 60 * 0.6 to "Mark 2")
        val writeNanos = measureNanoTime {
            result = SimulatedRecording.write(
                rec.csv,
                rec.raw,
                minutes * 60,
                SampleRate.HZ_200,
                markers = markers,
                startTimeIso = rec.startTimeIso,
            )
        }
        store.writeSummary(rec.id, SessionSummary(result.durationMillis, Foot.entries.toSet(), result.rows, 2))
        val csv = store.csvFile(rec.id).length()
        val raw = store.rawFile(rec.id).length()
        println("wrote ${rec.id}: ${result.rows} rows, csv ${mb(csv)}, raw ${mb(raw)} in ${ms(writeNanos)}")

        val session = store.list().single { it.id == rec.id }
        val memory = ManagementFactory.getMemoryMXBean()
        repeat(3) { round ->
            gc()
            val before = memory.heapMemoryUsage.used
            var lastProgress = 0f
            lateinit var loaded: LoadedSession
            val nanos = measureNanoTime { loaded = loadSession(store, session) { lastProgress = it } }
            gc()
            val after = memory.heapMemoryUsage.used
            println(
                "open #${round + 1}: ${ms(nanos)}, retained heap ${mb(after - before)} " +
                    "(${loaded.timeline.track(Foot.LEFT).size + loaded.timeline.track(Foot.RIGHT).size} samples, " +
                    "${loaded.events.events.size} gait events, progress ended at $lastProgress)",
            )
            // Keep it reachable until measured.
            check(loaded.timeline.durationMillis > 0)
        }
        println("max heap ${mb(Runtime.getRuntime().maxMemory())}")
    } finally {
        if (keep == null) dir.deleteRecursively()
    }
}

private fun gc() {
    repeat(3) {
        System.gc()
        Thread.sleep(100)
    }
}

private fun ms(nanos: Long) = "%.0f ms".format(nanos / 1e6)

private fun mb(bytes: Long) = "%.1f MB".format(bytes / (1024.0 * 1024.0))

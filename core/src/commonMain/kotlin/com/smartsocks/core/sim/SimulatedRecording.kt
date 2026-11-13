package com.smartsocks.core.sim

import com.smartsocks.core.analytics.FootProcessor
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.RawPacket
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.recording.CsvRecorder
import com.smartsocks.core.recording.RawPacketRecorder
import com.smartsocks.core.recording.RecordingMetadata
import com.smartsocks.core.recording.RecordingSink
import com.smartsocks.core.session.TimedPacket

/**
 * Writes a recording of simulated feet as fast as the sinks take it, without links or waiting: the packets of
 * [SimulatedSockTransport.packetAt], decoded and processed by [FootProcessor] exactly as a live session does, into
 * [CsvRecorder] (and [RawPacketRecorder]). For demo sessions, screenshots and load tests (a 10-minute recording in
 * seconds). Single-threaded.
 */
object SimulatedRecording {
    class Result(val rows: Long, val durationMillis: Double)

    /**
     * [seconds] of [feet] at [rate]; the right foot's samples are stamped half a period after the left one's (two
     * independent sample clocks). [markers] are (seconds, label), inserted at the first sample at or after them.
     */
    fun write(
        csv: RecordingSink,
        raw: RecordingSink?,
        seconds: Double,
        rate: SampleRate = SampleRate.HZ_200,
        range: MeasurementRange = MeasurementRange.K64,
        feet: Set<Foot> = Foot.entries.toSet(),
        mode: SimMode = SimMode.Walking,
        markers: List<Pair<Double, String>> = emptyList(),
        startTimeIso: String = "2026-01-01T00:00:00Z",
        processorSettings: ProcessorSettings = ProcessorSettings(),
        config: SimConfig = SimConfig(),
    ): Result {
        require(seconds > 0) { "seconds must be positive" }
        val order = Foot.entries.filter { it in feet }
        require(order.isNotEmpty()) { "no feet" }
        val csvRecorder = CsvRecorder(csv, RecordingMetadata(startTimeIso, rate, range, feet))
        val rawRecorder = raw?.let { RawPacketRecorder(it) }
        val sims = order.map { SimulatedSockTransport(it, config.copy(mode = mode)) }
        val processors = order.map { FootProcessor(it, settings = processorSettings) }
        val periodNanos = rate.periodNanos
        val count = (seconds * rate.hz).toLong()
        val pending = markers.sortedBy { it.first }.toMutableList()
        var rows = 0L
        try {
            for (i in 0 until count) {
                for (k in order.indices) {
                    val foot = order[k]
                    val t = i * periodNanos + if (foot == Foot.RIGHT && order.size == 2) periodNanos / 2 else 0L
                    val packet = RawPacket(sims[k].packetAt(i.toDouble() / rate.hz, range))
                    val ohms = DoubleArray(SENSOR_COUNT) { range.countsToOhms(packet.count(it)) }
                    csvRecorder.record(processors[k].process(SensorFrame(foot, t, ohms)))
                    rawRecorder?.record(TimedPacket(foot, t, packet, t))
                    rows++
                }
                while (pending.isNotEmpty() && pending[0].first * 1e9 <= i * periodNanos) {
                    csvRecorder.marker(pending.removeAt(0).second)
                }
            }
        } finally {
            csvRecorder.close()
            rawRecorder?.close()
        }
        return Result(rows, ((count - 1) * periodNanos + if (order.size == 2) periodNanos / 2 else 0L) / 1e6)
    }
}

package com.smartsocks.core.recording

import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.CenterOfPressure
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketEncoder
import com.smartsocks.core.protocol.RawPacket
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.SockSession
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.session.TimedPacket
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecordingTest {
    private val metadata = RecordingMetadata(
        startTimeIso = "2026-11-12T10:15:30Z",
        rate = SampleRate.HZ_100,
        range = MeasurementRange.K64,
        feet = setOf(Foot.LEFT, Foot.RIGHT),
        note = "test run",
    )

    private fun state(
        foot: Foot = Foot.LEFT,
        nanos: Long = 0,
        ohms: DoubleArray = DoubleArray(6) { 1000.0 * (it + 1) },
        cop: Point? = Point(0.25, -0.5),
    ) = FootState(
        foot = foot,
        timestampNanos = nanos,
        resistanceOhms = ohms,
        loads = DoubleArray(6) { 1.5 * (it + 1) },
        totalLoad = 31.5,
        shares = DoubleArray(6),
        cop = cop,
        rawCop = cop?.let { Point(it.x + 0.01, it.y) },
        baselineSource = BaselineSource.CALIBRATED,
    )

    @Test
    fun `header describes the recording`() {
        val sink = InMemoryRecordingSink()
        CsvRecorder(sink, metadata)
        assertEquals(
            listOf(
                "# smartsocks-csv=2",
                "# start_time=2026-11-12T10:15:30Z",
                "# sample_rate_hz=100",
                "# range=K64",
                "# feet=LEFT+RIGHT",
                "# note=test run",
                "# sensor_layout=" + SensorLayout.Legacy.positions(Foot.LEFT).joinToString(";") {
                    "${formatNumber(it.x, 4)}:${formatNumber(it.y, 4)}"
                },
                "t_ms,foot,r1,r2,r3,r4,r5,r6,load1,load2,load3,load4,load5,load6," +
                    "total_load,cop_x,cop_y,raw_cop_x,raw_cop_y,contact,baseline",
            ),
            sink.lines,
        )
    }

    @Test
    fun `row is formatted with dot decimals and relative time`() {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata)
        recorder.record(state(nanos = 5_000_000_000))
        recorder.record(state(foot = Foot.RIGHT, nanos = 5_012_345_678))
        assertEquals(
            "0.000,LEFT,1000.0,2000.0,3000.0,4000.0,5000.0,6000.0,1.500,3.000,4.500,6.000,7.500,9.000,31.500,0.2500,-0.5000,0.2600,-0.5000,1,calibrated",
            sink.lines[8],
        )
        assertTrue(sink.lines[9].startsWith("12.346,RIGHT,"))
    }

    @Test
    fun `infinite resistance and missing cop`() {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata)
        recorder.record(state(ohms = DoubleArray(6) { Double.POSITIVE_INFINITY }, cop = null))
        val row = sink.lines.last()
        assertTrue(row.startsWith("0.000,LEFT,inf,inf,inf,inf,inf,inf,"))
        assertTrue(row.endsWith(",31.500,,,,,0,calibrated"))
    }

    @Test
    fun `numbers ignore locale and round half up`() {
        assertEquals("0.050", formatNumber(0.05, 3))
        assertEquals("-1.2346", formatNumber(-1.23456, 4))
        assertEquals("0.0", formatNumber(-0.01, 1))
        assertEquals("12", formatNumber(11.6, 0))
        assertEquals("0.001", formatMillis(1_000))
        assertEquals("1000.000", formatMillis(1_000_000_000))
    }

    @Test
    fun `marker is stamped with the last sample time`() {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata)
        recorder.marker("before")
        recorder.record(state(nanos = 1_000_000_000))
        recorder.record(state(nanos = 1_250_000_000))
        recorder.marker("break one")
        assertEquals("# marker t_ms=0.000 label=before", sink.lines[8])
        assertEquals("# marker t_ms=250.000 label=break one", sink.lines.last())
    }

    /** A file of one LEFT sample in contact (sensor 1 loaded most) recorded with [layout], or without the header. */
    private fun layoutFile(layout: SensorLayout?): List<String> {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata.copy(layout = layout ?: SensorLayout.legacy(oddMedial = true)))
        val loads = doubleArrayOf(30.0, 10.0, 5.0, 5.0, 0.0, 0.0)
        val positions = (layout ?: SensorLayout.legacy(oddMedial = true)).positions(Foot.LEFT)
        val cop = CenterOfPressure.compute(loads, positions, 0.0)!!.position
        recorder.record(
            FootState(
                Foot.LEFT, 0, DoubleArray(6) { 1e4 }, loads, loads.sum(), DoubleArray(6),
                Point(cop.x * 0.9, cop.y * 0.9), // smoothed: lags behind the raw COP
                cop,
                BaselineSource.AUTO,
            ),
        )
        recorder.record(state(nanos = 10_000_000, cop = null)) // in the air: no COP
        return if (layout == null) sink.lines.filterNot { it.startsWith("# sensor_layout=") } else sink.lines
    }

    @Test
    fun `the layout header round trips and old readers keep it as an unknown entry`() {
        val mirrored = SensorLayout.legacy(oddMedial = false)
        val lines = layoutFile(mirrored)
        val text = lines.first { it.startsWith("# sensor_layout=") }.substringAfter('=')
        assertEquals(mirrored, decodeLayout(text))
        assertNull(decodeLayout("1:2;3"), "malformed")
        assertNull(decodeLayout(text.replace("-0.2590", "0.2590")), "a row without a lateral sensor")
        val session = CsvSessionReader.read(lines, mirrored)
        assertEquals(text, session.header["sensor_layout"])
    }

    @Test
    fun `a file read with its own layout keeps its COP`() {
        for (layout in listOf(SensorLayout.legacy(oddMedial = true), SensorLayout.legacy(oddMedial = false))) {
            val lines = layoutFile(layout)
            val info = CsvSessionReader.stream(lines, layout) {}
            assertEquals(CopConversion.NONE, info.copConversion)
            assertEquals(layout, info.recordedLayout)
            val row = CsvSessionReader.read(lines, layout).rows[0]
            val raw = CenterOfPressure.compute(row.loads, layout.positions(Foot.LEFT), 0.0)!!.position
            assertEquals(raw.x * 0.9, row.cop!!.x, 1e-4, "the recorded smoothed COP, untouched")
            assertEquals(raw.x, row.rawCop!!.x, 1e-4)
        }
    }

    @Test
    fun `a file without the header is the old layout and is mirrored for the flipped one`() {
        val old = layoutFile(null)
        val asRecorded = CsvSessionReader.read(old, SensorLayout.legacy(oddMedial = true))
        val info = CsvSessionReader.stream(old, SensorLayout.legacy(oddMedial = false)) {}
        assertEquals(SensorLayout.legacy(oddMedial = true), info.recordedLayout, "missing header: odd channels medial")
        assertEquals(CopConversion.MIRRORED, info.copConversion)
        val flipped = CsvSessionReader.read(old, SensorLayout.legacy(oddMedial = false))
        val before = asRecorded.rows[0]
        val after = flipped.rows[0]
        assertEquals(-before.cop!!.x, after.cop!!.x, 1e-12)
        assertEquals(before.cop!!.y, after.cop!!.y, 1e-12)
        // The raw COP is what the current layout gives for the loads.
        val expected = CenterOfPressure.compute(after.loads, SensorLayout.legacy(false).positions(Foot.LEFT), 0.0)!!
        assertEquals(expected.position.x, after.rawCop!!.x, 1e-3)
        assertEquals(expected.position.y, after.rawCop!!.y, 1e-3)
        assertNull(flipped.rows[1].cop, "in the air stays in the air")
        assertNull(flipped.rows[1].rawCop)
    }

    @Test
    fun `another layout gets its COP recomputed from the loads`() {
        val measured = SensorLayout(
            listOf(
                Point(0.3, 1.0),
                Point(-0.2, 0.9),
                Point(0.25, 0.1),
                Point(-0.3, -0.1),
                Point(0.2, -1.0),
                Point(-0.2, -0.95),
            ),
        )
        val old = layoutFile(null)
        val info = CsvSessionReader.stream(old, measured) {}
        assertEquals(CopConversion.RECOMPUTED, info.copConversion)
        val row = CsvSessionReader.read(old, measured).rows[0]
        val expected = CenterOfPressure.compute(row.loads, measured.positions(Foot.LEFT), 0.0)!!.position
        assertEquals(expected.x, row.rawCop!!.x, 1e-9)
        assertEquals(expected.y, row.rawCop!!.y, 1e-9)
        assertEquals(row.rawCop, row.cop, "the smoothed COP cannot be redone: the raw one")
        // A malformed header line counts as missing.
        val broken = old.toMutableList().apply { add(1, "# sensor_layout=garbage") }
        assertEquals(
            CopConversion.NONE,
            CsvSessionReader.stream(broken, SensorLayout.legacy(oddMedial = true)) {
            }.copConversion,
        )
    }

    @Test
    fun `raw packet is written as lowercase hex`() {
        val bytes = PacketEncoder.encode(intArrayOf(0x0102, 0xABCD, 0, 0, 0, 0, 0, 0))
        val sink = InMemoryRecordingSink()
        val recorder = RawPacketRecorder(sink)
        recorder.record(TimedPacket(Foot.RIGHT, 2_000_000_000, RawPacket(bytes), 2_500_000_000))
        recorder.record(TimedPacket(Foot.RIGHT, 2_010_000_000, RawPacket(bytes), 2_540_000_000))

        assertEquals("# smartsocks-raw=2", sink.lines[0])
        assertEquals("t_ms,host_ms,foot,hex", sink.lines[1])
        val hex = "f0" + "00".repeat(28) + "0201cdab" + "00".repeat(13) + "55"
        assertEquals(94, hex.length)
        assertEquals("0.000,0.000,RIGHT,$hex", sink.lines[2])
        assertEquals("10.000,40.000,RIGHT,$hex", sink.lines[3])
    }

    @Test
    fun `round trip through the reader`() {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata)
        recorder.record(state(nanos = 100_000_000))
        recorder.marker("break")
        recorder.record(
            state(
                foot = Foot.RIGHT,
                nanos = 110_000_000,
                ohms = DoubleArray(6) {
                    Double.POSITIVE_INFINITY
                },
                cop = null,
            ),
        )

        val session = CsvSessionReader.read(sink.lines)
        assertEquals("2", session.header["smartsocks-csv"])
        assertEquals("100", session.header["sample_rate_hz"])
        assertEquals("LEFT+RIGHT", session.header["feet"])
        assertEquals(listOf(CsvMarker(0.0, "break")), session.markers)
        assertEquals(2, session.rows.size)

        val a = session.rows[0]
        assertEquals(Foot.LEFT, a.foot)
        assertEquals(0.0, a.tMillis)
        assertEquals(3000.0, a.resistanceOhms[2])
        assertEquals(4.5, a.loads[2], 1e-9)
        assertEquals(31.5, a.totalLoad, 1e-9)
        assertEquals(Point(0.25, -0.5), a.cop)
        assertEquals(Point(0.26, -0.5), a.rawCop)
        assertTrue(a.inContact)
        assertEquals(BaselineSource.CALIBRATED, a.baseline)

        val b = session.rows[1]
        assertEquals(Foot.RIGHT, b.foot)
        assertEquals(10.0, b.tMillis)
        assertTrue(b.resistanceOhms.all { it == Double.POSITIVE_INFINITY })
        assertNull(b.cop)
        assertNull(b.rawCop)
        assertFalse(b.inContact)
    }

    @Test
    fun `reader rejects unknown version`() {
        val lines = listOf("# smartsocks-csv=3")
        val error = runCatching { CsvSessionReader.read(lines) }.exceptionOrNull()
        assertNotNull(error)
    }

    @Test
    fun `reader checks the version before the columns`() {
        val lines = listOf("# smartsocks-csv=9", "t_ms,something,else")
        val message = runCatching { CsvSessionReader.read(lines) }.exceptionOrNull()?.message
        assertTrue(message?.contains("version") == true, "message: $message")
    }

    @Test
    fun `reader reads version 1 files`() {
        val columns = "t_ms,foot,r1,r2,r3,r4,r5,r6,load1,load2,load3,load4,load5,load6,total_load," +
            "cop_x,cop_y,raw_cop_x,raw_cop_y,contact,calibrated"
        val row = "5.000,LEFT,1000,1000,1000,1000,1000,1000,1,1,1,1,1,1,6,0.1,0.2,0.1,0.2,1,"
        val lines = listOf("# smartsocks-csv=1", columns, row + "1", row.replace("5.000", "6.000") + "0")
        val rows = CsvSessionReader.read(lines).rows
        assertEquals(listOf(BaselineSource.CALIBRATED, BaselineSource.NONE), rows.map { it.baseline })
    }

    @Test
    fun `reader reports the line of a bad enum value`() {
        val sink = InMemoryRecordingSink()
        CsvRecorder(sink, metadata).record(state(nanos = 0))
        // Followed by a good row: an error, not a last line cut short.
        val bad = sink.lines.map { if (it.contains(",LEFT,")) it.replace(",LEFT,", ",MIDDLE,") else it } +
            sink.lines.last()
        val line = bad.indexOfFirst { it.contains(",MIDDLE,") } + 1
        val message = runCatching { CsvSessionReader.read(bad) }.exceptionOrNull()?.message
        assertTrue(message?.startsWith("Line $line") == true, "message: $message")
    }

    @Test
    fun `records both feet from running sessions and closes on cancel`() = runTest {
        fun session(foot: Foot) = SockSession(
            foot,
            SimulatedSockTransport(foot, SimConfig(connectDelayMillis = 0, mode = SimMode.Standing)),
            StreamSettings(SampleRate.HZ_100, MeasurementRange.K64),
            clockNanos = { testScheduler.currentTime * 1_000_000 },
        )
        val sessions = listOf(session(Foot.LEFT), session(Foot.RIGHT))
        val csv = InMemoryRecordingSink()
        val raw = InMemoryRecordingSink()
        val left = sessions[0]

        val csvJob = launch { sessions.recordTo(CsvRecorder(csv, metadata)) }
        val rawJob = launch { left.recordRawTo(RawPacketRecorder(raw)) }
        sessions.forEach { backgroundScope.launch { it.run() } }
        runCurrent()
        advanceTimeBy(2_000)

        val rows = CsvSessionReader.read(csv.lines).rows
        assertEquals(200.0, rows.count { it.foot == Foot.LEFT }.toDouble(), 15.0)
        assertEquals(200.0, rows.count { it.foot == Foot.RIGHT }.toDouble(), 15.0)
        assertEquals(200.0, (raw.lines.size - 2).toDouble(), 15.0)

        assertFalse(csv.closed)
        csvJob.cancel()
        rawJob.cancel()
        runCurrent()
        assertTrue(csv.closed)
        assertTrue(raw.closed)
    }

    private fun recording(rows: Int = 3): MutableList<String> {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata)
        for (i in 0 until rows) {
            recorder.record(state(nanos = i * 10_000_000L))
            if (i == 1) recorder.marker("Mark 1")
        }
        return sink.lines.toMutableList()
    }

    @Test
    fun `a last line cut short by a crash is left out`() {
        val lines = recording()
        val full = lines.removeAt(lines.size - 1)
        // Every cut of the last row: none of them may count as a sample, and none is an error.
        for (cut in 1 until full.length) {
            val read = CsvSessionReader.read(lines + full.substring(0, cut))
            assertEquals(2, read.rows.size, "cut at $cut: ${full.substring(0, cut)}")
            assertTrue(CsvSessionReader.stream(lines + full.substring(0, cut)) {}.truncatedLastLine)
        }
        val complete = CsvSessionReader.stream(lines + full) {}
        assertFalse(complete.truncatedLastLine)
        // Trailing blank lines do not hide the cut line, nor make it an error.
        assertEquals(2, CsvSessionReader.read(lines + full.take(10) + "" + "\r").rows.size)
    }

    @Test
    fun `a marker cut short at the end is left out too`() {
        val lines = recording(rows = 2)
        val read = CsvSessionReader.read(lines + "# marker t_ms=")
        assertEquals(1, read.markers.size)
        assertEquals(2, read.rows.size)
    }

    @Test
    fun `a malformed line in the middle is still an error with its number`() {
        val lines = recording()
        val bad = lines.indexOfFirst { it.startsWith("10.000,") }
        lines[bad] = lines[bad].take(12)
        val message = runCatching { CsvSessionReader.read(lines) }.exceptionOrNull()?.message
        assertTrue(message?.startsWith("Line ${bad + 1}:") == true, "message: $message")
        val badMarker = recording().apply { set(indexOfFirst { it.startsWith("# marker") }, "# marker x") }
        val markerMessage = runCatching { CsvSessionReader.read(badMarker) }.exceptionOrNull()?.message
        assertTrue(markerMessage?.contains("malformed marker") == true, "message: $markerMessage")
    }

    @Test
    fun `a foot that joins late is announced before its first row`() {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata.copy(feet = setOf(Foot.LEFT)))
        recorder.record(state(nanos = 1_000_000_000))
        recorder.record(state(nanos = 1_010_000_000))
        recorder.record(state(foot = Foot.RIGHT, nanos = 6_000_000_000))
        recorder.record(state(nanos = 6_005_000_000))
        recorder.record(state(foot = Foot.RIGHT, nanos = 6_010_000_000))
        assertEquals("# feet=LEFT", sink.lines[4])
        val join = sink.lines.indexOf("# foot_joined,RIGHT,5000.000")
        assertTrue(join > 0, "${sink.lines}")
        assertTrue(sink.lines[join + 1].startsWith("5000.000,RIGHT,"))
        assertEquals(1, sink.lines.count { it.startsWith("# foot_joined") }, "announced once")
        assertEquals(listOf(CsvFootJoin(Foot.RIGHT, 5000.0)), recorder.joins)

        val read = CsvSessionReader.read(sink.lines)
        assertEquals(listOf(CsvFootJoin(Foot.RIGHT, 5000.0)), read.joins)
        assertEquals(5, read.rows.size)
        assertEquals("LEFT", read.header["feet"])
        assertTrue(read.header.keys.none { it.contains("joined") }, "not a header value: ${read.header}")
    }

    @Test
    fun `feet of the header never join and files without joins have none`() {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata)
        recorder.record(state(nanos = 0))
        recorder.record(state(foot = Foot.RIGHT, nanos = 5_000_000_000))
        assertTrue(recorder.joins.isEmpty())
        assertTrue(sink.lines.none { it.startsWith("# foot_joined") })
        assertTrue(CsvSessionReader.read(sink.lines).joins.isEmpty())
        // A version 1 file (written before joins existed) reads without any.
        val columns = "t_ms,foot,r1,r2,r3,r4,r5,r6,load1,load2,load3,load4,load5,load6,total_load," +
            "cop_x,cop_y,raw_cop_x,raw_cop_y,contact,calibrated"
        val v1 = listOf("# smartsocks-csv=1", "# feet=LEFT", columns, "0.000,LEFT,1,1,1,1,1,1,1,1,1,1,1,1,6,,,,,0,0")
        assertTrue(CsvSessionReader.stream(v1) {}.joins.isEmpty())
    }

    @Test
    fun `a malformed join line is skipped and a join without rows after it is not reported`() {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(sink, metadata.copy(feet = setOf(Foot.LEFT)))
        recorder.record(state(nanos = 0))
        recorder.record(state(foot = Foot.RIGHT, nanos = 50_000_000))
        recorder.record(state(nanos = 60_000_000))
        val lines = sink.lines
        val join = lines.indexOf("# foot_joined,RIGHT,50.000")
        // Damaged in the middle: only information, the file still reads with all its rows (and no join).
        for (bad in listOf(
            "# foot_joined,MIDDLE,5.000",
            "# foot_joined,RIGHT,x",
            "# foot_joined,RIGHT",
            "# foot_joined,",
        )) {
            val damaged = lines.toMutableList().apply { set(join, bad) }
            val read = CsvSessionReader.read(damaged)
            assertEquals(3, read.rows.size, bad)
            assertTrue(read.joins.isEmpty(), bad)
        }
        // Cut right after the join line (or in it): no row of the foot followed, so no join.
        val cut = lines.take(join + 1)
        assertTrue(CsvSessionReader.read(cut).joins.isEmpty())
        assertTrue(CsvSessionReader.read(lines.take(join) + "# foot_joined,RIGH").joins.isEmpty())
        // A row of another foot after it does not count either.
        assertTrue(CsvSessionReader.read(cut + lines.last()).joins.isEmpty())
        assertEquals(listOf(CsvFootJoin(Foot.RIGHT, 50.0)), CsvSessionReader.read(lines).joins)
    }
}

package com.smartsocks.core.playback

import com.smartsocks.core.analytics.BalanceCalculator
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.recording.CsvMarker
import com.smartsocks.core.recording.CsvRecorder
import com.smartsocks.core.recording.CsvSessionReader
import com.smartsocks.core.recording.InMemoryRecordingSink
import com.smartsocks.core.recording.RecordingMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTimelineTest {
    @Test
    fun `unsorted rows are sorted per foot`() {
        val timeline = SessionTimeline(
            recorded(
                listOf(
                    row(20.0, load = 3.0),
                    row(5.0, Foot.RIGHT),
                    row(0.0, load = 1.0),
                    row(10.0, load = 2.0),
                ),
            ),
        )
        val left = timeline.track(Foot.LEFT)
        assertEquals(3, left.size)
        assertEquals(listOf(0.0, 10.0, 20.0), (0 until left.size).map { left.timeAt(it) })
        assertEquals(listOf(1.0, 2.0, 3.0), (0 until left.size).map { left.totalLoadAt(it) })
        assertEquals(0.0, timeline.startMillis)
        assertEquals(20.0, timeline.endMillis)
        assertEquals(20.0, timeline.durationMillis)
        assertEquals(setOf(Foot.LEFT, Foot.RIGHT), timeline.feet)
    }

    @Test
    fun `indexAt finds the last sample at or before and clamps`() {
        val timeline = SessionTimeline(recorded(listOf(0.0, 10.0, 20.0, 30.0).map { row(it) }))
        assertEquals(0, timeline.indexAt(Foot.LEFT, -5.0), "before the first sample clamps to 0")
        assertEquals(0, timeline.indexAt(Foot.LEFT, 0.0))
        assertEquals(0, timeline.indexAt(Foot.LEFT, 9.999))
        assertEquals(1, timeline.indexAt(Foot.LEFT, 10.0))
        assertEquals(2, timeline.indexAt(Foot.LEFT, 25.0))
        assertEquals(3, timeline.indexAt(Foot.LEFT, 30.0))
        assertEquals(3, timeline.indexAt(Foot.LEFT, 1e9), "past the end clamps to the last")
    }

    @Test
    fun `duplicate timestamps keep file order and indexAt takes the last of them`() {
        val timeline = SessionTimeline(
            recorded(listOf(row(0.0, load = 1.0), row(10.0, load = 2.0), row(10.0, load = 3.0), row(20.0, load = 4.0))),
        )
        val track = timeline.track(Foot.LEFT)
        assertEquals(2.0, track.totalLoadAt(1))
        assertEquals(3.0, track.totalLoadAt(2))
        assertEquals(2, timeline.indexAt(Foot.LEFT, 10.0))
        assertEquals(1..2, timeline.range(Foot.LEFT, 10.0, 10.0))
    }

    @Test
    fun `a foot without rows is empty everywhere`() {
        val timeline = SessionTimeline(recorded(listOf(row(100.0), row(110.0))))
        assertEquals(setOf(Foot.LEFT), timeline.feet)
        val right = timeline.track(Foot.RIGHT)
        assertTrue(right.isEmpty())
        assertNull(right.firstMillis)
        assertEquals(-1, timeline.indexAt(Foot.RIGHT, 105.0))
        assertTrue(timeline.range(Foot.RIGHT, 0.0, 1000.0).isEmpty())
        assertNull(timeline.currentSample(Foot.RIGHT, 105.0))
        assertEquals(100.0, timeline.startMillis)
        assertEquals(110.0, timeline.endMillis)
    }

    @Test
    fun `an empty session has a zero-length timeline`() {
        val timeline = SessionTimeline(recorded(emptyList()))
        assertTrue(timeline.feet.isEmpty())
        assertEquals(0.0, timeline.startMillis)
        assertEquals(0.0, timeline.durationMillis)
        val frame = timeline.frameAt(0.0)
        assertNull(frame.left)
        assertNull(frame.right)
        assertNull(frame.balance)
        assertNull(timeline.nextSampleTime(0.0))
    }

    @Test
    fun `range is inclusive and empty outside the data`() {
        val timeline = SessionTimeline(recorded((0..9).map { row(it * 10.0) }))
        assertEquals(2..5, timeline.range(Foot.LEFT, 20.0, 50.0))
        assertEquals(2..4, timeline.range(Foot.LEFT, 15.0, 49.0))
        assertEquals(0..9, timeline.range(Foot.LEFT, -100.0, 100.0))
        assertTrue(timeline.range(Foot.LEFT, 91.0, 200.0).isEmpty())
        assertTrue(timeline.range(Foot.LEFT, -50.0, -1.0).isEmpty())
        assertTrue(timeline.range(Foot.LEFT, 50.0, 20.0).isEmpty())
        assertTrue(timeline.range(Foot.LEFT, 21.0, 29.0).isEmpty())
    }

    @Test
    fun `sample view carries every recorded field`() {
        val timeline = SessionTimeline(
            recorded(
                listOf(
                    row(0.0, load = 60.0, cop = Point(0.25, -0.5), baseline = BaselineSource.AUTO),
                    row(10.0),
                ),
            ),
        )
        val a = timeline.sampleAt(Foot.LEFT, 0)
        assertEquals(Foot.LEFT, a.foot)
        assertEquals(0, a.index)
        assertEquals(0.0, a.tMillis)
        assertEquals(10.0, a.loads[3], 1e-6)
        assertEquals(3000.0, a.resistanceOhms[2], 1e-3)
        assertEquals(60.0, a.totalLoad)
        assertEquals(Point(0.25, -0.5), a.cop)
        assertEquals(0.26, a.rawCop!!.x, 1e-9)
        assertTrue(a.inContact)
        assertEquals(BaselineSource.AUTO, a.baselineSource)

        val b = timeline.sampleAt(Foot.LEFT, 1)
        assertNull(b.cop)
        assertNull(b.rawCop)
        assertFalse(b.inContact)
        assertTrue(b.resistanceOhms.all { it == Double.POSITIVE_INFINITY })
        assertTrue(timeline.track(Foot.LEFT).copXAt(1).isNaN())
    }

    @Test
    fun `current sample is null before a foot starts and once it is stale`() {
        val timeline = SessionTimeline(
            recorded(
                (0 until 100).map { row(it * 10.0, Foot.LEFT, load = 30.0) } +
                    (0 until 200).map { row(500.0 + it * 10.0, Foot.RIGHT, load = 10.0) },
            ),
        )
        assertEquals(10.0, timeline.samplePeriodMillis)
        assertEquals(50.0, timeline.staleAfterMillis(Foot.LEFT))

        assertNull(timeline.currentSample(Foot.RIGHT, 499.0), "right has not started yet")
        assertEquals(500.0, timeline.currentSample(Foot.RIGHT, 505.0)?.tMillis)
        assertEquals(990.0, timeline.currentSample(Foot.LEFT, 1030.0)?.tMillis, "held within the stale limit")
        assertNull(timeline.currentSample(Foot.LEFT, 1500.0), "left recording ended at 990 ms")

        val both = timeline.frameAt(700.0)
        assertEquals(700.0, both.left?.tMillis)
        assertEquals(0.75, both.balance!!.left, 1e-9)
        assertEquals(BalanceCalculator.compute(both.left, both.right), both.balance)

        val rightOnly = timeline.frameAt(1500.0)
        assertNull(rightOnly.left)
        assertNotNull(rightOnly.right)
        assertEquals(1.0, rightOnly.balance!!.right)
        assertEquals(rightOnly.right, rightOnly.sample(Foot.RIGHT))
    }

    @Test
    fun `sample period falls back to the median gap`() {
        val timeline = SessionTimeline(recorded(listOf(0.0, 5.0, 10.0, 15.0, 100.0).map { row(it) }, rateHz = null))
        assertEquals(5.0, timeline.samplePeriodMillis)
        assertEquals(10.0, SessionTimeline(recorded(listOf(row(0.0)), rateHz = null)).samplePeriodMillis)
    }

    @Test
    fun `next and previous sample times cover both feet`() {
        val timeline = SessionTimeline(
            recorded(listOf(row(0.0), row(10.0), row(20.0), row(5.0, Foot.RIGHT), row(15.0, Foot.RIGHT))),
        )
        assertEquals(5.0, timeline.nextSampleTime(0.0))
        assertEquals(10.0, timeline.nextSampleTime(5.0))
        assertEquals(10.0, timeline.nextSampleTime(7.0))
        assertNull(timeline.nextSampleTime(20.0))
        assertEquals(15.0, timeline.previousSampleTime(20.0))
        assertEquals(0.0, timeline.previousSampleTime(5.0))
        assertNull(timeline.previousSampleTime(0.0))
    }

    @Test
    fun `markers are sorted and searchable`() {
        val timeline = SessionTimeline(
            recorded(
                (0..100).map { row(it * 10.0) },
                markers = listOf(CsvMarker(500.0, "b"), CsvMarker(200.0, "a"), CsvMarker(900.0, "c")),
            ),
        )
        assertEquals(listOf("a", "b", "c"), timeline.markers.map { it.label })
        assertEquals("a", timeline.nextMarker(0.0)?.label)
        assertEquals("b", timeline.nextMarker(200.0)?.label)
        assertNull(timeline.nextMarker(900.0))
        assertEquals("b", timeline.previousMarker(900.0)?.label)
        assertNull(timeline.previousMarker(200.0))
    }

    @Test
    fun `a foot streaming slower than requested never blinks out between its samples`() {
        // Asked for 100 Hz, the device delivered 10 Hz on the left; the right foot did stream at 100 Hz.
        val timeline = SessionTimeline(
            recorded(
                (0 until 50).map { row(it * 100.0, Foot.LEFT, load = 30.0) } +
                    (0 until 500).map { row(it * 10.0, Foot.RIGHT, load = 30.0) },
                rateHz = 100,
            ),
        )
        assertEquals(100.0, timeline.samplePeriodMillis(Foot.LEFT))
        assertEquals(10.0, timeline.samplePeriodMillis(Foot.RIGHT))
        var t = 0.0
        while (t < 4900.0) {
            assertNotNull(timeline.currentSample(Foot.LEFT, t), "left at $t")
            t += 7.0
        }
        assertNull(timeline.currentSample(Foot.RIGHT, 4990.0 + 60.0), "the fast foot still goes stale quickly")
    }

    @Test
    fun `streamed timeline equals the one built from a read session`() {
        val sink = InMemoryRecordingSink()
        val metadata =
            RecordingMetadata("2026-11-12T10:00:00Z", SampleRate.HZ_100, MeasurementRange.K64, Foot.entries.toSet())
        val recorder = CsvRecorder(sink, metadata)
        for (i in 0 until 1000) {
            val foot = if (i % 2 == 0) Foot.LEFT else Foot.RIGHT
            recorder.record(state(foot, nanos = 1_000_000_000L + i * 5_000_000L, load = (i % 37).toDouble()))
            if (i == 500) recorder.marker("half")
        }
        val streamed = SessionTimeline.read(sink.lines)
        val built = SessionTimeline(CsvSessionReader.read(sink.lines))
        assertEquals(built.header, streamed.header)
        assertEquals(built.markers, streamed.markers)
        for (foot in Foot.entries) {
            val a = streamed.track(foot)
            val b = built.track(foot)
            assertEquals(500, a.size)
            for (i in 0 until a.size) {
                assertEquals(b.timeAt(i), a.timeAt(i))
                assertEquals(b.totalLoadAt(i), a.totalLoadAt(i))
                assertEquals(b.copAt(i), a.copAt(i))
                assertEquals(b.loadAt(i, 3), a.loadAt(i, 3))
            }
        }
    }

    @Test
    fun `builder sorts many unsorted rows stably and grows past its initial capacity`() {
        val builder = SessionTimelineBuilder()
        // 3000 rows in reverse time order, each time twice (loads 1 then 2 in arrival order).
        for (k in 2999 downTo 0) {
            builder.add(row((k / 2).toDouble(), load = if (k % 2 == 0) 1.0 else 2.0))
        }
        val track = builder.build().track(Foot.LEFT)
        assertEquals(3000, track.size)
        for (i in 1 until track.size) assertTrue(track.timeAt(i - 1) <= track.timeAt(i))
        // Arrival order within a timestamp: k odd (load 2) came before k even (load 1).
        assertEquals(2.0, track.totalLoadAt(0))
        assertEquals(1.0, track.totalLoadAt(1))
    }

    @Test
    fun `stable order keeps equal keys in place`() {
        val keys = doubleArrayOf(3.0, 1.0, 2.0, 1.0, 3.0, 0.0, 2.0)
        assertEquals(listOf(5, 1, 3, 2, 6, 0, 4), stableOrder(keys, keys.size).toList())
        assertEquals(emptyList<Int>(), stableOrder(DoubleArray(0), 0).toList())
    }

    @Test
    fun `builder rejects a non-finite time`() {
        assertFailsWith<IllegalArgumentException> { SessionTimelineBuilder().add(row(Double.NaN)) }
    }

    @Test
    fun `reader rejects a non-finite t_ms with its line but keeps inf resistances`() {
        val sink = InMemoryRecordingSink()
        CsvRecorder(sink, RecordingMetadata("x", SampleRate.HZ_100, MeasurementRange.K64, setOf(Foot.LEFT)))
            .record(state(Foot.LEFT, nanos = 0, load = 0.0))
        assertTrue(sink.lines.last().contains(",inf,"), "open-circuit resistance is written as inf")
        assertEquals(1, CsvSessionReader.read(sink.lines).rows.size)
        for (bad in listOf("nan", "inf", "-inf")) {
            // Followed by a good line: an error, not a last line cut short.
            val lines = sink.lines.dropLast(1) + (bad + "," + sink.lines.last().substringAfter(",")) + sink.lines.last()
            val message = assertFailsWith<IllegalArgumentException> { SessionTimeline.read(lines) }.message
            assertTrue(message?.startsWith("Line ${lines.size - 1}") == true, "message: $message")
        }
        val marker = sink.lines + "# marker t_ms=nan label=x" + sink.lines.last()
        assertFailsWith<IllegalArgumentException> { CsvSessionReader.read(marker) }
    }

    @Test
    fun `frame steps merge the two feet's nearly simultaneous samples`() {
        // After clock convergence the feet are 0.3 ms apart.
        val timeline = SessionTimeline(
            recorded((0 until 10).flatMap { listOf(row(it * 10.0, Foot.LEFT), row(it * 10.0 + 0.3, Foot.RIGHT)) }),
        )
        assertEquals(2.5, timeline.frameMergeMillis)
        val forward = generateSequence(timeline.nextFrameTime(-1.0)) { timeline.nextFrameTime(it) }.toList()
        assertEquals((0 until 10).map { it * 10.0 + 0.3 }, forward)
        val back = generateSequence(timeline.previousFrameTime(90.3)) { timeline.previousFrameTime(it) }.toList()
        assertEquals((8 downTo 0).map { it * 10.0 + 0.3 }, back)
        assertEquals(10.3, timeline.nextFrameTime(10.0), "from the first half of a frame to its end")
    }

    /** The left foot from 0 s, the right one connected only 5 s later: written by the recorder, read as a file. */
    private fun lateJoinLines(): List<String> {
        val sink = InMemoryRecordingSink()
        val metadata =
            RecordingMetadata("2026-11-14T10:00:00Z", SampleRate.HZ_100, MeasurementRange.K64, setOf(Foot.LEFT))
        val recorder = CsvRecorder(sink, metadata)
        val start = 3_000_000_000L
        for (i in 0 until 1000) {
            val nanos = start + i * 10_000_000L
            recorder.record(state(Foot.LEFT, nanos, load = 30.0))
            if (i >= 500) recorder.record(state(Foot.RIGHT, nanos + 2_000_000L, load = 10.0))
        }
        return sink.lines
    }

    @Test
    fun `a foot that joined late is shown from its join on and as no data before`() {
        val timeline = SessionTimeline.read(lateJoinLines())
        assertEquals(Foot.entries.toSet(), timeline.feet)
        assertEquals(mapOf(Foot.RIGHT to 5_002.0), timeline.joins)
        assertEquals(0.0, timeline.startMillis)
        assertEquals(5_002.0, timeline.track(Foot.RIGHT).firstMillis)
        assertEquals(500, timeline.track(Foot.RIGHT).size)

        val before = timeline.frameAt(4_000.0)
        assertNotNull(before.left)
        assertNull(before.right, "nothing of the right foot before it joined, not a stale sample")
        assertNull(before.balance, "no balance before both feet were there: not 100 % left")
        assertNull(timeline.frameAt(5_001.0).right)
        assertNull(timeline.frameAt(5_001.0).balance)
        val after = timeline.frameAt(5_010.0)
        assertEquals(5_002.0, after.right?.tMillis)
        assertEquals(0.75, after.balance!!.left, 1e-9)
        // Both feet on one time axis: the left foot's sample at the same moment.
        assertEquals(5_010.0, after.left?.tMillis)

        // The same from the rows kept in memory.
        assertEquals(timeline.joins, SessionTimeline(CsvSessionReader.read(lateJoinLines())).joins)
    }

    @Test
    fun `a file from before joins were recorded has none and still reads`() {
        val lines = lateJoinLines().filterNot { it.startsWith("# foot_joined") }
        val timeline = SessionTimeline.read(lines)
        assertTrue(timeline.joins.isEmpty())
        assertEquals(Foot.entries.toSet(), timeline.feet, "the feet come from the data")
        assertNull(timeline.frameAt(4_000.0).right)
        // Without the join on record it cannot know: the left foot alone carries everything, as before.
        assertEquals(1.0, timeline.frameAt(4_000.0).balance?.left)
    }

    private fun state(foot: Foot, nanos: Long, load: Double) = FootState(
        foot = foot,
        timestampNanos = nanos,
        resistanceOhms = DoubleArray(6) { if (load > 0) 1000.0 * (it + 1) else Double.POSITIVE_INFINITY },
        loads = DoubleArray(6) { load / 6 },
        totalLoad = load,
        shares = DoubleArray(6),
        cop = if (load > 0) Point(0.1 * (load % 3), -0.2) else null,
        rawCop = null,
        baselineSource = BaselineSource.AUTO,
    )
}

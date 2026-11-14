package com.smartsocks.core.sim

import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.GaitEventType
import com.smartsocks.core.playback.GaitEvents
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.recording.InMemoryRecordingSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatedRecordingTest {
    @Test
    fun `writes both walking feet with markers that read back as a timeline`() {
        val csv = InMemoryRecordingSink()
        val raw = InMemoryRecordingSink()
        val result = SimulatedRecording.write(
            csv,
            raw,
            seconds = 6.0,
            rate = SampleRate.HZ_200,
            markers = listOf(2.0 to "Mark 1", 4.5 to "Mark 2"),
        )
        assertTrue(csv.closed && raw.closed)
        assertEquals(2_400, result.rows)
        assertEquals(2_400 + 2, raw.lines.size)

        val timeline = SessionTimeline.read(csv.lines)
        assertEquals(Foot.entries.toSet(), timeline.feet)
        assertEquals(result.durationMillis, timeline.durationMillis, 1e-3)
        assertEquals(5_997.5, timeline.durationMillis, 1e-3)
        // At the newest sample: the right foot's, half a period after the left one's.
        assertEquals(listOf(2_002.5, 4_502.5), timeline.markers.map { it.tMillis })
        assertEquals("200", timeline.header["sample_rate_hz"])
        // Right foot half a period after the left one: two sample clocks.
        assertEquals(2.5, timeline.track(Foot.RIGHT).timeAt(0) - timeline.track(Foot.LEFT).timeAt(0), 1e-6)

        // 100 steps/min: one heel strike per foot every 1.2 s.
        val strikes = GaitEvents.detect(timeline).events.filter { it.type == GaitEventType.HEEL_STRIKE }
        assertEquals(9.0, strikes.size.toDouble(), 1.0)
        assertTrue(timeline.track(Foot.LEFT).let { t -> (0 until t.size).any { !t.inContactAt(it) } })
    }

    @Test
    fun `one foot at another range and rate`() {
        val csv = InMemoryRecordingSink()
        SimulatedRecording.write(
            csv,
            null,
            seconds = 1.0,
            rate = SampleRate.HZ_50,
            range = MeasurementRange.K128,
            feet = setOf(Foot.RIGHT),
            mode = SimMode.Standing,
        )
        val timeline = SessionTimeline.read(csv.lines)
        assertEquals(setOf(Foot.RIGHT), timeline.feet)
        assertEquals(50, timeline.track(Foot.RIGHT).size)
        assertEquals("K128", timeline.header["range"])
        // Standing: the foot carries load.
        assertTrue(timeline.track(Foot.RIGHT).totalLoadAt(49) > 50)
    }

    @Test
    fun `a foot that joins late starts there on the same time axis`() {
        val csv = InMemoryRecordingSink()
        val late = mapOf(Foot.RIGHT to 5.0)
        SimulatedRecording.write(csv, null, seconds = 8.0, rate = SampleRate.HZ_100, joinAtSeconds = late)
        assertTrue(csv.lines.contains("# feet=LEFT"))
        val timeline = SessionTimeline.read(csv.lines)
        assertEquals(Foot.entries.toSet(), timeline.feet)
        assertEquals(mapOf(Foot.RIGHT to 5_005.0), timeline.joins)
        assertEquals(5_005.0, timeline.track(Foot.RIGHT).firstMillis)
        assertEquals(0.0, timeline.track(Foot.LEFT).firstMillis)
        assertEquals(300, timeline.track(Foot.RIGHT).size)
    }
}

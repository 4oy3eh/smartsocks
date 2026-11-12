package com.smartsocks.core.playback

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.recording.CsvRecorder
import com.smartsocks.core.recording.CsvSessionReader
import com.smartsocks.core.recording.InMemoryRecordingSink
import com.smartsocks.core.recording.RecordingMetadata
import com.smartsocks.core.recording.recordTo
import com.smartsocks.core.session.SockSession
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlaybackEndToEndTest {
    @Test
    fun `recorded simulated walk replays with steps at the simulated cadence`() = runTest {
        val cadence = 100.0 // steps/min: one stride per foot every 1.2 s, the feet half a stride apart
        val clock = { testScheduler.currentTime * 1_000_000 }
        val sessions = Foot.entries.map { foot ->
            SockSession(
                foot,
                SimulatedSockTransport(
                    foot,
                    SimConfig(mode = SimMode.Walking, cadenceStepsPerMin = cadence, connectDelayMillis = 0),
                ),
                StreamSettings(SampleRate.HZ_100, MeasurementRange.K64),
                clockNanos = clock,
            )
        }
        val sink = InMemoryRecordingSink()
        val metadata = RecordingMetadata(
            startTimeIso = "2026-11-12T10:00:00Z",
            rate = SampleRate.HZ_100,
            range = MeasurementRange.K64,
            feet = Foot.entries.toSet(),
        )
        val recording = launch { sessions.recordTo(CsvRecorder(sink, metadata)) }
        sessions.forEach { backgroundScope.launch { it.run() } }
        runCurrent()
        advanceTimeBy(12_000)
        recording.cancel()
        runCurrent()
        assertTrue(sink.closed)

        val timeline = SessionTimeline(CsvSessionReader.read(sink.lines))
        assertEquals(Foot.entries.toSet(), timeline.feet)
        // Minus connecting and configuring at the start.
        assertTrue(timeline.durationMillis in 11_500.0..12_000.0, "duration ${timeline.durationMillis}")
        assertEquals(10.0, timeline.samplePeriodMillis)

        val events = GaitEvents.detect(timeline)
        val strideMillis = 2 * 60_000 / cadence
        val strikes = Foot.entries.associateWith { foot ->
            events.forFoot(foot).filter { it.type == GaitEventType.HEEL_STRIKE }.map { it.tMillis }
        }
        for ((foot, times) in strikes) {
            assertTrue(times.size in 9..10, "$foot: ${times.size} heel strikes in 12 s")
            for (gap in times.zipWithNext { a, b -> b - a }) {
                assertEquals(strideMillis, gap, 30.0, "$foot stride")
            }
        }
        // The right foot strikes half a stride after the left.
        val left = strikes.getValue(Foot.LEFT)
        for (r in strikes.getValue(Foot.RIGHT)) {
            val offset = left.filter { it < r }.maxOrNull() ?: continue
            assertEquals(strideMillis / 2, r - offset, 40.0)
        }

        // Each foot's stance (strike to toe-off) is roughly the simulated 62% of the stride; its peak inside it.
        for (foot in Foot.entries) {
            val footEvents = events.forFoot(foot)
            for (strike in footEvents.filter { it.type == GaitEventType.HEEL_STRIKE }) {
                val toeOff = events.nextEvent(strike.tMillis, foot, GaitEventType.TOE_OFF) ?: continue
                val stance = toeOff.tMillis - strike.tMillis
                assertTrue(abs(stance - 0.62 * strideMillis) < 0.1 * strideMillis, "$foot stance $stance ms")
                val peak = assertNotNull(events.nextEvent(strike.tMillis - 1, foot, GaitEventType.PEAK_LOAD))
                assertTrue(peak.tMillis in strike.tMillis..toeOff.tMillis)
            }
        }

        // Halfway through a step both feet are on screen and the balance is defined.
        val player = SessionPlayer(timeline, backgroundScope, clock, events)
        assertTrue(player.nextEvent(Foot.LEFT, GaitEventType.PEAK_LOAD))
        val frame = player.frameAt()
        assertNotNull(frame.left)
        assertNotNull(frame.right)
        assertTrue(frame.left!!.inContact)
        assertTrue(frame.balance!!.left > 0.5, "the left foot carries more at its own load peak")
    }
}

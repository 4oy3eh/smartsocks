package com.smartsocks.app.recording

import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.noJitter
import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.ReplayClocks
import com.smartsocks.app.platform.replayDevice
import com.smartsocks.app.platform.simulatorTransport
import com.smartsocks.app.platform.unsupportedOnThisPlatform
import com.smartsocks.app.ui.recordingDetails
import com.smartsocks.app.ui.resultText
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.recording.CsvSessionReader
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimulatedRecording
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Recording feet that replay a recording: the file says the rate and range the packets really have. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayRecordingTest {
    /** What the source recording was made with; the app's stream settings are the defaults (100 Hz, 64 kΩ). */
    private val recorded = StreamSettings(SampleRate.HZ_50, MeasurementRange.K128)

    private class Fixture(val store: MemorySessionStore, val live: LiveController, val recorder: RecordingController)

    private fun TestScope.fixture(initial: AppSettings): Pair<Fixture, String> {
        val store = MemorySessionStore()
        val source = store.create(saveRaw = true)
        SimulatedRecording.write(source.csv, source.raw, 10.0, recorded.rate, recorded.range)
        val clocks = ReplayClocks { testScheduler.currentTime * 1_000_000 }
        val devices = DeviceFactory { foot, choice ->
            when (choice) {
                is DeviceChoice.Replay -> replayDevice(choice, store, clocks.of(choice.sessionId))
                is DeviceChoice.Simulator -> DeviceResult.Ready(simulatorTransport(foot, choice))
                else -> unsupportedOnThisPlatform(choice, "Test")
            }
        }
        fun AppSettings.named() = Foot.entries.fold(this) { s, foot ->
            val d = s.device(foot)
            if (d is DeviceChoice.Replay) s.withDevice(foot, d.copy(sessionId = source.id)) else s
        }
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial.named())), backgroundScope)
        val live = LiveController(settings, devices, backgroundScope, reconnectDelayMillis = noJitter) {
            testScheduler.currentTime * 1_000_000
        }
        val recorder =
            RecordingController(live, store, settings, backgroundScope, StandardTestDispatcher(testScheduler))
        return Fixture(store, live, recorder) to source.id
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private val replayBoth = AppSettings(
        left = DeviceChoice.Replay("", Foot.LEFT),
        right = DeviceChoice.Replay("", Foot.RIGHT),
    )

    @Test
    fun `recording a replay writes the recorded rate and range into the new file`() = runTest {
        val (f, source) = fixture(replayBoth)
        advance(1_000)
        assertTrue(f.recorder.start())
        advance(2_000)
        f.recorder.stopAndJoin()
        val id = assertIs<String>(f.recorder.lastResult.value?.id)
        val header = SessionHeader.of(CsvSessionReader.read(f.store.lines(id)).header)
        assertEquals(recorded, header.stream)
        assertEquals(Foot.entries.toSet(), header.feet)
        assertTrue(f.store.entries.getValue(id).raw!!.lines.size > 100)
        // And the new recording replays as recorded: the same settings once more.
        assertEquals(recorded, f.store.find(id)!!.header.stream)
        assertTrue(source != id)
    }

    @Test
    fun `feet at different rates or ranges are not recorded into one file`() = runTest {
        val (f, _) = fixture(replayBoth.copy(right = DeviceChoice.Simulator()))
        advance(1_000)
        assertEquals(Foot.entries.toSet(), f.recorder.streamingFeet())
        assertFalse(f.recorder.start())
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        val result = f.recorder.lastResult.value!!
        assertEquals(RecordingController.DIFFERENT_STREAMS, result.error)
        assertTrue("different sample rates" in resultText(result), resultText(result))
        assertEquals(1, f.store.entries.size, "no file created")
    }

    @Test
    fun `a foot that would join at other settings is left out`() = runTest {
        val (f, _) = fixture(replayBoth.copy(left = DeviceChoice.Simulator(), autoConnect = false))
        advance(100)
        f.live.left.connect()
        advance(1_000)
        assertTrue(f.recorder.start())
        advance(500)
        f.live.right.connect()
        advance(2_000)
        assertEquals(LinkState.Streaming, f.live.right.status.value.state)
        val recording = assertIs<RecordingState.Recording>(f.recorder.state.value)
        assertEquals(setOf(Foot.LEFT), recording.feet)
        assertEquals(setOf(Foot.RIGHT), recording.incompatible)
        assertTrue(recording.waiting.isEmpty())
        assertTrue("Right not recorded" in recordingDetails(recording), recordingDetails(recording))
        f.recorder.stopAndJoin()

        val result = f.recorder.lastResult.value!!
        assertEquals(setOf(Foot.LEFT), result.feet)
        assertEquals(setOf(Foot.RIGHT), result.incompatible)
        assertTrue(result.neverJoined.isEmpty())
        assertTrue("Right foot not recorded" in resultText(result), resultText(result))
        val file = CsvSessionReader.read(f.store.lines(result.id!!))
        assertTrue(file.rows.all { it.foot == Foot.LEFT } && file.joins.isEmpty())
        assertEquals(StreamSettings(), SessionHeader.of(file.header).stream)
        val raw = f.store.entries.getValue(result.id).raw!!.lines.drop(2)
        assertTrue(raw.all { ",LEFT," in it }, raw.firstOrNull { ",LEFT," !in it })
    }
}

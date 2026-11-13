package com.smartsocks.app.recording

import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.simulatorTransport
import com.smartsocks.app.platform.unsupportedOnThisPlatform
import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.RecordingSettings
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs work on [inner] while [open]; otherwise holds it until [release]. */
private class GateDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
    var open = true
    private val held = ArrayDeque<Pair<kotlin.coroutines.CoroutineContext, Runnable>>()

    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
        if (open) inner.dispatch(context, block) else held += context to block
    }

    fun release() {
        open = true
        while (held.isNotEmpty()) held.removeFirst().let { (c, b) -> inner.dispatch(c, b) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingControllerTest {
    private val simulators = DeviceFactory { foot, choice ->
        when (choice) {
            is DeviceChoice.Simulator -> DeviceResult.Ready(simulatorTransport(foot, choice))
            else -> unsupportedOnThisPlatform(choice, "Test")
        }
    }

    private class Fixture(
        val live: LiveController,
        val recorder: RecordingController,
        val store: MemorySessionStore,
        val settings: SettingsRepository,
    )

    /**
     * Both feet on walking simulators at 100 Hz (virtual time). The writer runs on [io] (by default the test's own
     * dispatcher); [scope] owns the recorder.
     */
    private fun TestScope.fixture(
        initial: AppSettings = AppSettings(),
        store: MemorySessionStore = MemorySessionStore(),
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        queueCapacity: Int = RecordingController.DEFAULT_QUEUE_CAPACITY,
        scope: CoroutineScope = backgroundScope,
    ): Fixture {
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial)), backgroundScope)
        val live = LiveController(settings, simulators, backgroundScope) { testScheduler.currentTime * 1_000_000 }
        val recorder = RecordingController(live, store, settings, scope, io, queueCapacity)
        return Fixture(live, recorder, store, settings)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun Fixture.timeline(id: String) = SessionTimeline.read(store.lines(id))

    @Test
    fun recordsBothFeetWithMarkersAndFinalizesOnStop() = runTest {
        val f = fixture()
        advance(1_000)
        assertEquals(Foot.entries.toSet(), f.recorder.streamingFeet())
        assertTrue(f.recorder.start())
        assertFalse(f.recorder.start(), "only one recording at a time")
        runCurrent()
        val recording = assertIs<RecordingState.Recording>(f.recorder.state.value)
        advance(2_000)
        assertEquals("Mark 1", f.recorder.mark())
        advance(1_000)
        assertEquals("Mark 2", f.recorder.mark())
        advance(2_000)
        val live = assertIs<RecordingState.Recording>(f.recorder.state.value)
        assertTrue(live.rows > 900 && live.bytes > 50_000, "$live")
        assertEquals(2, live.markers)

        f.recorder.stop()
        runCurrent()
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        val entry = f.store.entries.getValue(recording.id)
        assertTrue(entry.csv.closed)
        assertTrue(entry.raw!!.closed)
        assertNull(f.recorder.mark(), "no marks once stopped")

        val result = assertNotNull(f.recorder.lastResult.value)
        assertEquals(StopReason.User, result.reason)
        assertEquals(0, result.dropped)
        assertEquals(2, result.markers)
        // 5 s of two feet at 100 Hz.
        assertEquals(1000.0, result.rows.toDouble(), 30.0)

        val timeline = f.timeline(recording.id)
        assertEquals(Foot.entries.toSet(), timeline.feet)
        assertEquals(5000.0, timeline.durationMillis, 50.0)
        assertEquals(listOf("Mark 1", "Mark 2"), timeline.markers.map { it.label })
        assertEquals(1000.0, timeline.markers[1].tMillis - timeline.markers[0].tMillis, 20.0)
        assertEquals("100", timeline.header["sample_rate_hz"])
        assertEquals("LEFT+RIGHT", timeline.header["feet"])
        // Every packet of both feet in the raw file (two header lines).
        assertEquals(result.rows.toDouble(), entry.raw.lines.size - 2.0, 4.0)

        val summary = assertNotNull(f.store.summaries[recording.id])
        assertEquals(result.rows, summary.rows)
        assertEquals(2, summary.markers)
        assertEquals(timeline.durationMillis, summary.durationMillis, 1.0)
        assertEquals(1, f.recorder.finished.value)
    }

    @Test
    fun cannotRecordWhileNothingStreams() = runTest {
        val f = fixture(AppSettings(autoConnect = false))
        advance(1_000)
        assertTrue(f.recorder.streamingFeet().isEmpty())
        assertFalse(f.recorder.start())
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        assertTrue(f.store.entries.isEmpty())
    }

    @Test
    fun recordsOnlyTheStreamingFootAndWithoutRawWhenOff() = runTest {
        val f = fixture(AppSettings(right = DeviceChoice.None, recording = RecordingSettings(saveRaw = false)))
        advance(1_000)
        assertTrue(f.recorder.start())
        advance(1_000)
        f.recorder.stopAndJoin()
        val id = f.store.entries.keys.single()
        assertNull(f.store.entries.getValue(id).raw)
        val timeline = f.timeline(id)
        assertEquals(setOf(Foot.LEFT), timeline.feet)
        assertEquals("LEFT", timeline.header["feet"])
    }

    @Test
    fun aFootThatReconnectsKeepsRecordingIntoTheSameFile() = runTest {
        val f = fixture()
        advance(1_000)
        f.recorder.start()
        advance(2_000)
        f.live.left.disconnect()
        advance(1_500)
        assertEquals(LinkState.Idle, f.live.left.status.value.state)
        f.live.left.connect()
        advance(2_500) // 300 ms to connect, then about 2.2 s of samples
        f.recorder.stopAndJoin()

        val timeline = f.timeline(f.store.entries.keys.single())
        val left = timeline.track(Foot.LEFT)
        var gap = 0.0
        for (i in 1 until left.size) gap = maxOf(gap, left.timeAt(i) - left.timeAt(i - 1))
        assertTrue(gap > 1_500, "gap $gap")
        // About 2 s before and 2.2 s after the gap.
        assertEquals(420.0, left.size.toDouble(), 25.0)
        assertEquals(600.0, timeline.track(Foot.RIGHT).size.toDouble(), 10.0)
    }

    @Test
    fun streamAndDeviceChangesAreRefusedWhileRecording() = runTest {
        val f = fixture()
        advance(1_000)
        f.recorder.start()
        runCurrent()
        val before = f.settings.settings.value
        f.settings.update {
            it.copy(
                stream = it.stream.copy(rate = SampleRate.HZ_50, range = MeasurementRange.K128),
                right = DeviceChoice.None,
                left = DeviceChoice.Simulator(SimMode.Standing),
                theme = AppTheme.DARK,
            )
        }
        val during = f.settings.settings.value
        assertEquals(before.stream, during.stream, "no new rate or range: the header would be wrong")
        assertEquals(before.right, during.right, "no device change")
        assertEquals(DeviceChoice.Simulator(SimMode.Standing), during.left, "a simulator mode change is fine")
        assertEquals(AppTheme.DARK, during.theme, "everything else applies")
        f.settings.update { it.copy(left = DeviceChoice.Simulator(preloaded = true)) }
        assertEquals(DeviceChoice.Simulator(SimMode.Standing), f.settings.settings.value.left, "preloaded rebuilds")
        advance(2_000)
        // Nothing was rebuilt: both feet streamed on into the file at 100 Hz.
        assertEquals(LinkState.Streaming, f.live.right.status.value.state)
        f.recorder.stopAndJoin()
        val timeline = f.timeline(f.store.entries.keys.single())
        assertEquals("100", timeline.header["sample_rate_hz"])
        assertEquals(200.0, timeline.track(Foot.RIGHT).size.toDouble(), 10.0)

        // Once stopped, changes apply again.
        f.settings.update { it.copy(stream = it.stream.copy(rate = SampleRate.HZ_50)) }
        assertEquals(SampleRate.HZ_50, f.settings.settings.value.stream.rate)
    }

    @Test
    fun aReconnectWhileRecordingStillGoesIntoTheFile() = runTest {
        val f = fixture()
        advance(1_000)
        f.recorder.start()
        advance(1_000)
        f.live.right.disconnect()
        f.live.left.disconnect()
        advance(500)
        f.live.right.connect()
        f.live.left.connect()
        advance(2_000)
        f.recorder.stopAndJoin()
        val timeline = f.timeline(f.store.entries.keys.single())
        assertTrue(timeline.track(Foot.LEFT).lastMillis!! > 3_000, "${timeline.track(Foot.LEFT).lastMillis}")
    }

    @Test
    fun theFileIsFlushedOnceASecondEvenWhenNoSampleComes() = runTest {
        val f = fixture()
        advance(1_000)
        f.recorder.start()
        advance(500)
        f.live.left.disconnect()
        f.live.right.disconnect()
        advance(300)
        val sink = f.store.entries.values.single().sink
        val written = f.store.entries.values.single().csv.lines.size
        assertTrue(written > 50)
        advance(1_100)
        assertEquals(written, sink.flushedLines, "everything written is flushed without new samples")
        f.recorder.stopAndJoin()
    }

    @Test
    fun aStopBeforeTheFilesExistCreatesNothingAndNeverSticks() = runTest {
        val f = fixture()
        advance(1_000)
        assertTrue(f.recorder.start())
        f.recorder.stop() // before the writer ran
        runCurrent()
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        assertTrue(f.store.entries.isEmpty(), "no empty file")
        assertNull(f.recorder.lastResult.value?.id)
        assertNull(f.recorder.activeRecording.value)
        assertTrue(f.recorder.start(), "can record again")
        runCurrent()
        // Stopped right after the files were created: no sample yet, so the empty file goes away.
        f.recorder.stop()
        runCurrent()
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        assertTrue(f.store.entries.isEmpty())
        assertTrue(f.store.summaries.isEmpty())
    }

    @Test
    fun theActiveRecordingIsKnownUntilItsFilesAreClosed() = runTest {
        val f = fixture()
        advance(1_000)
        f.recorder.start()
        runCurrent()
        val id = f.store.entries.keys.single()
        assertEquals(id, f.recorder.activeRecording.value)
        advance(1_000)
        f.recorder.stop()
        assertIs<RecordingState.Stopping>(f.recorder.state.value, "Stopping is set before the queue closes")
        runCurrent()
        assertNull(f.recorder.activeRecording.value)
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        assertNotNull(f.store.summaries[id])
    }

    @Test
    fun aSlowDiskDropsSamplesAndCountsThemInsteadOfBlocking() = runTest {
        val io = GateDispatcher(StandardTestDispatcher(testScheduler))
        val f = fixture(io = io, queueCapacity = 50)
        advance(1_000)
        f.recorder.start()
        runCurrent() // files created, feeders subscribed
        io.open = false // the disk stalls: the writer gets no time
        advance(2_000)
        val state = assertIs<RecordingState.Recording>(f.recorder.state.value)
        // Two feet × 200 samples (and as many raw packets) in 2 s; only the queue's 50 fit. Counted apart.
        assertTrue(state.dropped in 330..400, "$state")
        assertTrue(state.droppedRaw in 330..400, "$state")
        // The live stream itself never stalled.
        assertEquals(LinkState.Streaming, f.live.left.status.value.state)
        assertTrue(f.live.left.status.value.rate.packetsPerSecond in 95..105)

        f.recorder.stop()
        io.release()
        runCurrent()
        val result = assertNotNull(f.recorder.lastResult.value)
        assertTrue(result.rows in 1..50, "${result.rows}")
        val summary = assertNotNull(f.store.summaries.values.single())
        assertEquals(result.dropped, summary.droppedRows)
        assertEquals(result.droppedRaw, summary.droppedRaw)
        assertEquals(400.0, (result.rows + result.dropped).toDouble(), 10.0)
    }

    @Test
    fun cancellingTheScopeFinalizesTheFile() = runTest {
        val owner = backgroundScope + Job(backgroundScope.coroutineContext[Job])
        val f = fixture(scope = owner)
        advance(1_000)
        f.recorder.start()
        advance(1_000)
        owner.cancel()
        runCurrent()
        val entry = f.store.entries.values.single()
        assertTrue(entry.csv.closed)
        assertTrue(entry.raw!!.closed)
        assertEquals(StopReason.AppClosed, f.recorder.lastResult.value?.reason)
        assertEquals(1000.0, f.timeline(f.store.entries.keys.single()).durationMillis, 30.0)
    }

    @Test
    fun aWriteErrorEndsTheRecordingWithTheReason() = runTest {
        val f = fixture(store = MemorySessionStore(failWriteAfter = 100))
        advance(1_000)
        f.recorder.start()
        advance(2_000)
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        val result = assertNotNull(f.recorder.lastResult.value)
        assertEquals(StopReason.Error, result.reason)
        assertEquals("No space left on device", result.error)
        assertTrue(f.store.entries.values.single().csv.closed)
        // What was written before the error is still a readable recording.
        assertTrue(f.timeline(f.store.entries.keys.single()).feet.isNotEmpty())
    }

    @Test
    fun aStoreThatCannotCreateReportsIt() = runTest {
        val f = fixture(store = MemorySessionStore(failCreate = true))
        advance(1_000)
        assertTrue(f.recorder.start())
        runCurrent()
        assertEquals(RecordingState.Idle, f.recorder.state.value)
        val result = assertNotNull(f.recorder.lastResult.value)
        assertEquals(StopReason.Error, result.reason)
        assertEquals("Disk full", result.error)
        assertNull(result.id)
        // A new attempt is possible.
        f.store.failCreate = false
        assertTrue(f.recorder.start())
        runCurrent()
        assertIs<RecordingState.Recording>(f.recorder.state.value)
        assertNull(f.recorder.lastResult.value)
    }
}

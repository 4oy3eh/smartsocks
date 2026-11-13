package com.smartsocks.app.playback

import com.smartsocks.app.live.HistoryWindow
import com.smartsocks.app.recording.CountingLines
import com.smartsocks.app.recording.MemorySessionStore
import com.smartsocks.app.recording.SessionSummary
import com.smartsocks.app.recording.StoredSession
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.playback.SessionTimelineBuilder
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.recording.CsvRecorder
import com.smartsocks.core.recording.CsvRow
import com.smartsocks.core.recording.InMemoryRecordingSink
import com.smartsocks.core.recording.RecordingMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsControllerTest {
    /** [seconds] of the left foot at 100 Hz, in contact all along, with a marker at 1 s. */
    private fun recording(seconds: Int = 3): List<String> {
        val sink = InMemoryRecordingSink()
        val recorder = CsvRecorder(
            sink,
            RecordingMetadata("2026-11-13T14:05:22+01:00", SampleRate.HZ_100, MeasurementRange.K64, setOf(Foot.LEFT)),
        )
        for (i in 0 until seconds * 100) {
            recorder.record(
                FootState(
                    foot = Foot.LEFT,
                    timestampNanos = i * 10_000_000L,
                    resistanceOhms = DoubleArray(SENSOR_COUNT) { 20_000.0 },
                    loads = DoubleArray(SENSOR_COUNT) { 50.0 },
                    totalLoad = 300.0,
                    shares = DoubleArray(SENSOR_COUNT) { 1.0 / SENSOR_COUNT },
                    cop = Point(0.0, 0.1),
                    rawCop = Point(0.0, 0.1),
                    baselineSource = BaselineSource.AUTO,
                ),
            )
            if (i == 100) recorder.marker("Mark 1")
        }
        recorder.close()
        return sink.lines
    }

    private fun TestScope.controller(
        store: MemorySessionStore,
        active: MutableStateFlow<String?> = MutableStateFlow(null),
    ) = SessionsController(
        store,
        backgroundScope,
        StandardTestDispatcher(testScheduler),
        { testScheduler.currentTime * 1_000_000 },
        activeRecording = active,
    )

    private fun TestScope.loaded(c: SessionsController): List<StoredSession> {
        runCurrent()
        return assertIs<SessionList.Loaded>(c.list.value).sessions
    }

    @Test
    fun opensARecordingIntoAPlayerAndRemembersItsSummary() = runTest {
        val store = MemorySessionStore()
        store.texts["a"] = recording()
        val c = controller(store)
        c.refresh()
        runCurrent()
        val session = assertIs<SessionList.Loaded>(c.list.value).sessions.single()
        assertNull(session.summary)
        assertEquals(100, session.header.rateHz)

        var opened = 0
        c.open(session) { opened++ }
        assertIs<OpenState.Reading>(c.opening.value)
        runCurrent()
        assertEquals(1, opened)
        assertNull(c.opening.value)
        val player = assertNotNull(c.player.value)
        assertEquals(2_990.0, player.duration, 1e-6)
        assertEquals(1, player.timeline.markers.size)
        // A recording without a summary (cut short) gets one from the read; the list shows it.
        val summary = assertNotNull(store.summaries["a"])
        assertEquals(2_990.0, summary.durationMillis, 1e-6)
        assertEquals(300, summary.rows)
        assertEquals(1, summary.markers)
        assertEquals(summary, loaded(c).single().summary)

        // Opening it again keeps the same player (and its position).
        player.seek(1_000.0)
        c.open(session) { opened++ }
        runCurrent()
        assertEquals(2, opened)
        assertEquals(1, store.reads)
        assertEquals(1_000.0, assertNotNull(c.player.value).position)

        c.closePlayer()
        assertNull(c.player.value)
    }

    @Test
    fun aCorruptRecordingReportsTheLineInsteadOfCrashing() = runTest {
        val store = MemorySessionStore()
        val lines = recording().toMutableList()
        val bad = lines.indexOfLast { it.startsWith("1500.000,") }
        lines[bad] = lines[bad].replace(",LEFT,", ",MIDDLE,")
        store.texts["broken"] = lines
        val c = controller(store)
        c.refresh()
        runCurrent()
        val session = assertIs<SessionList.Loaded>(c.list.value).sessions.single()
        var opened = false
        c.open(session) { opened = true }
        runCurrent()
        val failed = assertIs<OpenState.Failed>(c.opening.value)
        assertTrue(failed.message.startsWith("Line ${bad + 1}:"), failed.message)
        assertNull(c.player.value)
        assertTrue(!opened)
        c.dismissOpenError()
        assertNull(c.opening.value)
    }

    @Test
    fun openingCanBeCancelled() = runTest {
        val store = MemorySessionStore()
        store.texts["a"] = recording()
        val c = controller(store)
        c.refresh()
        runCurrent()
        val session = assertIs<SessionList.Loaded>(c.list.value).sessions.single()
        c.open(session)
        c.cancelOpen()
        runCurrent()
        assertNull(c.opening.value)
        assertNull(c.player.value)
        assertEquals(0, store.reads)
    }

    @Test
    fun deletingTheOpenSessionClosesThePlayerAndRenameReportsProblems() = runTest {
        val store = MemorySessionStore()
        store.texts["a"] = recording()
        store.texts["b"] = recording()
        val c = controller(store)
        c.refresh()
        runCurrent()
        val a = assertIs<SessionList.Loaded>(c.list.value).sessions.first { it.id == "a" }
        c.open(a)
        runCurrent()
        assertNotNull(c.player.value)

        assertEquals("A name cannot contain < > : \" / \\ | ? *", c.rename(a, "bad/name"))
        assertNull(c.rename(a, "walk test"))
        val ids = assertIs<SessionList.Loaded>(c.list.value).sessions.map { it.id }.toSet()
        assertEquals(setOf("walk test", "b"), ids)
        assertNull(c.player.value, "renamed: reopened under its new name")

        val b = assertIs<SessionList.Loaded>(c.list.value).sessions.first { it.id == "b" }
        c.open(b)
        runCurrent()
        c.delete(b)
        runCurrent()
        assertNull(c.player.value)
        assertEquals(listOf("walk test"), assertIs<SessionList.Loaded>(c.list.value).sessions.map { it.id })
    }

    @Test
    fun progressFollowsTheCharactersRead() {
        val reported = mutableListOf<Long>()
        val lines = List(10) { "0123456789" }
        val counted = CountingLines(lines, every = 4) { reported += it }.toList()
        assertEquals(lines, counted)
        assertEquals(listOf(44L, 88L, 110L), reported)
    }

    @Test
    fun aSummaryRoundTripsAndRejectsGarbage() {
        val s = SessionSummary(1234.5, setOf(Foot.RIGHT, Foot.LEFT), 2469, 3, 7)
        assertEquals(s, SessionSummary.decode(s.encode()))
        assertNull(SessionSummary.decode("duration_ms=5"))
        assertNull(SessionSummary.decode(s.encode().lines().take(2).joinToString("\n")), "cut short")
    }

    @Test
    fun theChartWindowOfARecordingIsCopiedLikeLiveHistory() {
        val builder = SessionTimelineBuilder()
        fun row(t: Double) = CsvRow(
            t,
            Foot.LEFT,
            DoubleArray(SENSOR_COUNT) { 1.0 },
            DoubleArray(SENSOR_COUNT) { t.toDouble() },
            t,
            Point(0.0, 0.0),
            null,
            true,
            BaselineSource.NONE,
        )
        // 0..990 ms at 100 Hz, then a 1 s gap (the link was down), then 2000..2990 ms.
        for (i in 0 until 100) builder.add(row(i * 10.0))
        for (i in 200 until 300) builder.add(row(i * 10.0))
        val track = builder.build().track(Foot.LEFT)

        val w = HistoryWindow(4096)
        w.copy(track, endMillis = 2_500.0, spanMillis = 2_000.0, maxGapMillis = 50.0)
        // 500..2500 ms plus the sample before: 490..990 (51) and 2000..2500 (51).
        assertEquals(102, w.size)
        assertEquals(1, w.firstInside)
        assertEquals(-2.01f, w.t[0], 1e-4f)
        assertEquals(0f, w.t[w.size - 1], 1e-6f)
        assertEquals(listOf(0, 51), (0 until w.size).filter { w.breaks[it] })
        assertEquals(2_500f, w.maxTotal())

        // More samples than the window holds: every other one, still from the window's start.
        val small = HistoryWindow(60)
        small.copy(track, endMillis = 2_500.0, spanMillis = 2_000.0, maxGapMillis = 50.0)
        assertEquals(51, small.size)
        assertEquals(listOf(0, 26), (0 until small.size).filter { small.breaks[it] })

        val empty = HistoryWindow(10)
        empty.copy(track, endMillis = 1_800.0, spanMillis = 500.0, maxGapMillis = 50.0)
        assertEquals(0, empty.size)
    }

    @Test
    fun aSummaryFromAReadNeverReplacesTheRecordersOwn() = runTest {
        val store = MemorySessionStore()
        store.texts["a"] = recording()
        val c = controller(store)
        c.refresh()
        val session = loaded(c).single()
        // The recorder finished (and summarised, with its drop counts) after the list was read.
        val final = SessionSummary(2_990.0, setOf(Foot.LEFT), 300, 1, droppedRows = 7)
        store.summaries["a"] = final
        c.open(session)
        runCurrent()
        assertNotNull(c.player.value)
        assertEquals(final, store.summaries["a"])
    }

    @Test
    fun aRecordingCutShortMidLineStillOpens() = runTest {
        val store = MemorySessionStore()
        val lines = recording().toMutableList()
        lines[lines.size - 1] = lines.last().take(17)
        store.texts["crashed"] = lines
        val c = controller(store)
        c.refresh()
        c.open(loaded(c).single())
        runCurrent()
        assertNull(c.opening.value)
        assertEquals(299, assertNotNull(c.player.value).timeline.track(Foot.LEFT).size)
    }

    @Test
    fun theRecordingInProgressCannotBeOpenedRenamedOrDeleted() = runTest {
        val store = MemorySessionStore()
        store.texts["live"] = recording()
        val active = MutableStateFlow<String?>("live")
        val c = controller(store, active)
        c.refresh()
        val session = loaded(c).single()
        assertTrue(c.isRecording(session))

        c.open(session)
        runCurrent()
        assertNull(c.opening.value)
        assertNull(c.player.value)
        assertEquals(0, store.reads)
        assertEquals(SessionsController.STILL_RECORDING, c.message.value)
        c.dismissMessage()

        assertEquals(SessionsController.STILL_RECORDING, c.rename(session, "other"))
        c.delete(session)
        runCurrent()
        assertEquals(SessionsController.STILL_RECORDING, c.message.value)
        assertEquals(listOf("live"), loaded(c).map { it.id })
        assertTrue(store.summaries.isEmpty(), "no summary written for it")

        // Finished: it opens normally now.
        active.value = null
        runCurrent()
        c.open(loaded(c).single())
        runCurrent()
        assertNotNull(c.player.value)
    }

    @Test
    fun aPlayerOfARecordingThatFinishesIsClosed() = runTest {
        val store = MemorySessionStore()
        store.texts["a"] = recording()
        val active = MutableStateFlow<String?>(null)
        val c = controller(store, active)
        c.refresh()
        val before = loaded(c).single().sizeBytes
        c.open(loaded(c).single())
        runCurrent()
        assertNotNull(c.player.value)
        // (A player can only hold the recording in progress through a race; it must not outlive the recording.)
        active.value = "a"
        runCurrent()
        assertNotNull(c.player.value, "only a finished recording evicts")
        store.texts["a"] = recording(seconds = 5)
        active.value = null
        runCurrent()
        assertNull(c.player.value, "the old, partial picture is gone")
        assertTrue(loaded(c).single().sizeBytes > before, "list reloaded")
    }

    @Test
    fun cancellingMidReadStopsTheReadAndWritesNothing() = runTest {
        val store = MemorySessionStore()
        store.texts["long"] = recording(seconds = 60)
        val c = controller(store)
        c.refresh()
        val session = loaded(c).single()
        var linesRead = 0
        store.onRead = { _, index ->
            linesRead = index + 1
            if (index == 1_000) c.cancelOpen()
        }
        c.open(session)
        runCurrent()
        assertTrue(linesRead in 1_001..4_200, "stopped at the next check: $linesRead")
        assertNull(c.opening.value)
        assertNull(c.player.value)
        assertTrue(store.summaries.isEmpty())
    }

    @Test
    fun openingAnotherSessionMidReadKeepsItsIndicator() = runTest {
        val store = MemorySessionStore()
        store.texts["a"] = recording(seconds = 60)
        store.texts["b"] = recording()
        val c = controller(store)
        c.refresh()
        val sessions = loaded(c)
        val a = sessions.first { it.id == "a" }
        val b = sessions.first { it.id == "b" }
        val seenWhileReadingB = mutableListOf<OpenState?>()
        store.onRead = { id, index ->
            if (id == "a" && index == 100) c.open(b)
            if (id == "b" && index == 0) seenWhileReadingB += c.opening.value
        }
        c.open(a)
        runCurrent()
        // When b's read starts, a has been cancelled: its cancellation must not have cleared b's indicator.
        assertEquals(b.id, assertIs<OpenState.Reading>(seenWhileReadingB.single()).session.id)
        assertNull(c.opening.value)
        assertEquals("b", assertNotNull(c.player.value).session.id)
    }
}

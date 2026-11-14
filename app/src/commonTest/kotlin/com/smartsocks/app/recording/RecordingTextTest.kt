package com.smartsocks.app.recording

import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.ui.recordingDetails
import com.smartsocks.app.ui.resultText
import com.smartsocks.app.ui.sessionDetails
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.DeviceChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the record bar, the result notice, the sessions list and the summary say about late feet. */
class RecordingTextTest {
    private val sim = DeviceChoice.Simulator()

    /** [wanted] by default for a foot on its way, as the live controller reports it. */
    private fun status(
        foot: Foot,
        state: LinkState,
        device: DeviceChoice = sim,
        wanted: Boolean = state.isRecovering || state == LinkState.Streaming,
    ) = FootStatus(
        foot,
        device,
        null,
        state,
        pendingConnect = wanted && state is LinkState.Unavailable,
        wanted = wanted,
    )

    @Test
    fun aFootOnItsWayIsWaitingAndAStreamingOneJoins() {
        val start = RecordingState.Recording("id", setOf(Foot.LEFT), 0)
        val left = status(Foot.LEFT, LinkState.Streaming)
        val reconnecting = status(Foot.RIGHT, LinkState.Reconnecting(2, 3, "No answer"))
        val waiting = start.following(listOf(left, reconnecting))
        assertEquals(setOf(Foot.LEFT), waiting.feet)
        assertEquals(setOf(Foot.RIGHT), waiting.waiting)
        assertSame(waiting, waiting.following(listOf(left, status(Foot.RIGHT, LinkState.Connecting))), "no change")

        val joined = waiting.following(listOf(left, status(Foot.RIGHT, LinkState.Streaming)))
        assertEquals(Foot.entries.toSet(), joined.feet)
        assertEquals(emptySet(), joined.waiting)
        // A recorded foot stays recorded when it drops: it is not "waiting" again.
        val dropped = joined.following(listOf(left, reconnecting))
        assertEquals(Foot.entries.toSet(), dropped.feet)
        assertEquals(emptySet(), dropped.waiting)
    }

    @Test
    fun aMomentOfIdleWhileTheConnectIsStillWantedKeepsTheFootWaiting() {
        val waiting = RecordingState.Recording("id", setOf(Foot.LEFT), 0, waiting = setOf(Foot.RIGHT))
        val left = status(Foot.LEFT, LinkState.Streaming)
        // E.g. its session rebuilt, before the new one connects: shown idle for a moment, the request still on.
        val passing = waiting.following(listOf(left, status(Foot.RIGHT, LinkState.Idle, wanted = true)))
        assertSame(waiting, passing)
        // The request off (the user disconnected it, or reconnecting gave up): no longer expected.
        val gone = waiting.following(listOf(left, status(Foot.RIGHT, LinkState.Disconnected("Link lost"))))
        assertEquals(emptySet(), gone.waiting)
    }

    @Test
    fun onlyAFootMeantToConnectIsWaiting() {
        assertTrue(status(Foot.RIGHT, LinkState.Connecting).isWaitingToJoin())
        assertTrue(status(Foot.RIGHT, LinkState.Configuring).isWaitingToJoin())
        assertTrue(status(Foot.RIGHT, LinkState.Reconnecting(1, 1, null)).isWaitingToJoin())
        val btOff = LinkState.Unavailable("Bluetooth is off", UnavailableReason.BluetoothOff)
        assertTrue(status(Foot.RIGHT, btOff, wanted = true).isWaitingToJoin(), "connects once Bluetooth is on")
        assertFalse(status(Foot.RIGHT, btOff).isWaitingToJoin())
        assertFalse(status(Foot.RIGHT, LinkState.Idle).isWaitingToJoin(), "disconnected by the user")
        assertFalse(status(Foot.RIGHT, LinkState.Disconnected("Link lost")).isWaitingToJoin(), "reconnect off")
        assertFalse(status(Foot.RIGHT, LinkState.Streaming).isWaitingToJoin(), "streaming: it joins")
        assertFalse(status(Foot.RIGHT, LinkState.NoDevice, DeviceChoice.None, wanted = true).isWaitingToJoin())
    }

    @Test
    fun theRecordBarShowsTheWaitingFoot() {
        val s = RecordingState.Recording("id", setOf(Foot.LEFT), 0, bytes = 840_000, waiting = setOf(Foot.RIGHT))
        assertEquals("Left foot · Right waiting to connect · 820 KB", recordingDetails(s))
        assertEquals(
            "Both feet · 820 KB · 2 marks",
            recordingDetails(s.copy(feet = Foot.entries.toSet(), waiting = emptySet(), markers = 2)),
        )
        assertEquals(
            "Right foot · Left waiting to connect · 820 KB",
            recordingDetails(s.copy(feet = setOf(Foot.RIGHT), waiting = setOf(Foot.LEFT))),
        )
    }

    @Test
    fun theResultSaysWhenAFootJoinedOrThatItNeverDid() {
        val joined = RecordingResult(
            "2026-11-14_10-00-00",
            StopReason.User,
            durationMillis = 62_000.0,
            feet = Foot.entries.toSet(),
            joinedMillis = mapOf(Foot.RIGHT to 5_002.5),
        )
        assertEquals("Saved 2026-11-14 10:00:00 · 1:02 · Right foot joined at 0:05", resultText(joined))
        val never = joined.copy(feet = setOf(Foot.LEFT), joinedMillis = emptyMap(), neverJoined = setOf(Foot.RIGHT))
        assertEquals("Saved 2026-11-14 10:00:00 · 1:02 · Right foot never connected", resultText(never))
    }

    @Test
    fun theSummaryKeepsTheJoinTimesAndOldSummariesStillDecode() {
        val summary = SessionSummary(
            62_000.0,
            Foot.entries.toSet(),
            rows = 11_400,
            markers = 1,
            joinedMillis = mapOf(Foot.RIGHT to 5_002.5),
        )
        val text = summary.encode()
        assertTrue(text.contains("joined_ms=RIGHT:5002.5\n"), text)
        assertEquals(summary, SessionSummary.decode(text))
        // Without a late foot the summary is as before; one written before joins existed decodes without any.
        assertFalse(summary.copy(joinedMillis = emptyMap()).encode().contains("joined"))
        val old = "smartsocks-summary=1\nduration_ms=1000.0\nfeet=LEFT+RIGHT\nrows=200\nmarkers=0\n"
        assertEquals(emptyMap(), SessionSummary.decode(old)?.joinedMillis)
        // A damaged value is left out, the summary still counts.
        assertEquals(emptyMap(), SessionSummary.decode(old + "joined_ms=RIGHT:x+MIDDLE:5\n")?.joinedMillis)
    }

    @Test
    fun theSessionsListShowsBothFeetAndWhenOneJoined() {
        val joined = mapOf(Foot.RIGHT to 5_002.5)
        val session = StoredSession(
            id = "a",
            sizeBytes = 3_250_000,
            modifiedMillis = 0,
            header = SessionHeader(rateHz = 100, feet = setOf(Foot.LEFT)),
            summary = SessionSummary(62_000.0, Foot.entries.toSet(), 11_400, 0, joinedMillis = joined),
            hasRaw = false,
        )
        assertEquals("1:02 · Both feet · Right from 0:05 · 100 Hz · 3.1 MB", sessionDetails(session))
        // Cut short, until the sessions list has summarised it in the background: the header's feet, those recorded
        // from the start (see SessionsControllerTest.recordingsWithoutASummaryAreSummarisedOnceInTheBackground).
        assertEquals("Duration unknown · Left foot · 100 Hz · 3.1 MB", sessionDetails(session.copy(summary = null)))
    }
}

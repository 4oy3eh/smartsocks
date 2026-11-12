package com.smartsocks.core.playback

import com.smartsocks.core.model.Foot
import com.smartsocks.core.recording.CsvMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionPlayerTest {
    /** Both feet every 10 ms over 0..9990 ms; left steps every 1000 ms (in contact for 600 ms of each). */
    private val timeline = SessionTimeline(
        recorded(
            rows(Foot.LEFT, 0, 10_000) { t -> t % 1000 in 100 until 700 } +
                rows(Foot.RIGHT, 5, 10_000) { true },
            markers = listOf(CsvMarker(2500.0, "one"), CsvMarker(7000.0, "two")),
        ),
    )

    private fun TestScope.player(tickMillis: Long = 10) = SessionPlayer(
        timeline,
        backgroundScope,
        clockNanos = { testScheduler.currentTime * 1_000_000 },
        tickMillis = tickMillis,
    )

    private val SessionPlayer.position get() = state.value.positionMillis

    @Test
    fun `starts paused at the start`() = runTest {
        val p = player()
        assertEquals(PlaybackState(positionMillis = 0.0), p.state.value)
        advanceTimeBy(1000)
        assertEquals(0.0, p.position)
    }

    @Test
    fun `plays at the chosen speed`() = runTest {
        val p = player()
        p.setSpeed(0.5)
        p.play()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(500.0, p.position, 1e-9)
        assertTrue(p.state.value.playing)

        p.setSpeed(2.0)
        advanceTimeBy(250)
        p.pause()
        assertEquals(1000.0, p.position, 1e-9)
    }

    @Test
    fun `position follows the clock between ticks only at tick rate`() = runTest {
        val p = player(tickMillis = 16)
        val positions = mutableListOf<Double>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            p.state.collect {
                positions +=
                    it.positionMillis
            }
        }
        p.play()
        advanceTimeBy(8)
        assertEquals(0.0, p.position, "no tick yet")
        advanceTimeBy(992)
        // ~62 updates for 1 s, although 200 samples were passed.
        assertTrue(positions.size in 60..64, "${positions.size} updates")
        assertEquals(992.0, p.position, 1e-9)
    }

    @Test
    fun `pause stops advancing and play resumes`() = runTest {
        val p = player()
        p.play()
        advanceTimeBy(205)
        p.pause()
        assertEquals(205.0, p.position, 1e-9, "pause takes the time since the last tick into account")
        assertFalse(p.state.value.playing)
        advanceTimeBy(1000)
        assertEquals(205.0, p.position)

        p.toggle()
        assertTrue(p.state.value.playing)
        advanceTimeBy(100)
        p.toggle()
        assertEquals(305.0, p.position, 1e-9)
    }

    @Test
    fun `speed change does not make the position jump`() = runTest {
        val p = player()
        p.play()
        advanceTimeBy(305)
        p.setSpeed(4.0)
        assertEquals(305.0, p.position, 1e-9)
        advanceTimeBy(100)
        p.setSpeed(0.1)
        assertEquals(705.0, p.position, 1e-9)
        advanceTimeBy(1000)
        p.pause()
        assertEquals(805.0, p.position, 1e-9)
    }

    @Test
    fun `speed must be within range`() = runTest {
        val p = player()
        assertFailsWith<IllegalArgumentException> { p.setSpeed(0.05) }
        assertFailsWith<IllegalArgumentException> { p.setSpeed(4.5) }
        p.setSpeed(0.1)
        p.setSpeed(4.0)
        assertEquals(4.0, p.state.value.speed)
    }

    @Test
    fun `seek clamps to the timeline and keeps playing`() = runTest {
        val p = player()
        p.seek(-500.0)
        assertEquals(0.0, p.position)
        p.seek(4000.0)
        p.play()
        advanceTimeBy(100)
        p.seek(6000.0)
        assertTrue(p.state.value.playing)
        advanceTimeBy(100)
        p.pause()
        assertEquals(6100.0, p.position, 1e-9)

        p.seek(1e12)
        assertEquals(timeline.endMillis, p.position)
        assertTrue(p.state.value.atEnd)
        assertFailsWith<IllegalArgumentException> { p.seek(Double.NaN) }
    }

    @Test
    fun `stops at the end and play restarts from the start`() = runTest {
        val p = player()
        p.seek(9_900.0)
        p.play()
        advanceTimeBy(500)
        assertEquals(PlaybackState(positionMillis = 9_995.0, playing = false, atEnd = true), p.state.value)
        advanceTimeBy(500)
        assertEquals(9_995.0, p.position, "the ticker has stopped")

        p.play()
        assertEquals(0.0, p.position)
        assertFalse(p.state.value.atEnd)
        advanceTimeBy(100)
        p.pause()
        assertEquals(100.0, p.position, 1e-9)
    }

    @Test
    fun `seeking to the end while playing stops`() = runTest {
        val p = player()
        p.play()
        p.seek(timeline.endMillis)
        assertFalse(p.state.value.playing)
        assertTrue(p.state.value.atEnd)
    }

    @Test
    fun `loop wraps to its start keeping the overshoot`() = runTest {
        val p = player()
        p.setLoop(1000.0, 1500.0)
        assertEquals(LoopRange(1000.0, 1500.0), p.state.value.loop)
        assertEquals(1000.0, p.position, "moved into the loop")
        p.play()
        advanceTimeBy(605)
        p.pause()
        assertEquals(1105.0, p.position, 1e-9)

        p.play()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(1105.0, p.position, 1e-9, "four whole loops later")
        assertTrue(p.state.value.playing)
        assertFalse(p.state.value.atEnd)
    }

    @Test
    fun `loop up to the end of the timeline wraps instead of stopping`() = runTest {
        val p = player()
        p.setLoop(9000.0, 1e9)
        assertEquals(LoopRange(9000.0, timeline.endMillis), p.state.value.loop)
        p.play()
        advanceTimeBy(1500)
        assertTrue(p.state.value.playing)
        assertTrue(p.position in 9000.0..9600.0, "position ${p.position}")
    }

    @Test
    fun `seeking past the loop end wraps to the loop start`() = runTest {
        val p = player()
        p.setLoop(1000.0, 1500.0)
        p.play()
        p.seek(3000.0)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(1000.0, p.position)
        advanceTimeBy(100)
        p.pause()
        assertEquals(1100.0, p.position, 1e-9)
    }

    @Test
    fun `seeking before the loop plays into it`() = runTest {
        val p = player()
        p.setLoop(1000.0, 1500.0)
        p.seek(800.0)
        p.play()
        advanceTimeBy(800)
        p.pause()
        assertEquals(1100.0, p.position, 1e-9)
    }

    @Test
    fun `clearing the loop lets playback run on`() = runTest {
        val p = player()
        p.setLoop(1000.0, 1500.0)
        p.play()
        advanceTimeBy(400)
        p.clearLoop()
        assertNull(p.state.value.loop)
        advanceTimeBy(400)
        p.pause()
        assertEquals(1800.0, p.position, 1e-9)
    }

    @Test
    fun `invalid loops are rejected`() = runTest {
        val p = player()
        assertFailsWith<IllegalArgumentException> { p.setLoop(500.0, 500.0) }
        assertFailsWith<IllegalArgumentException> { p.setLoop(600.0, 500.0) }
        assertFailsWith<IllegalArgumentException> { p.setLoop(20_000.0, 30_000.0) }
        assertNull(p.state.value.loop)
    }

    @Test
    fun `step frame moves between sample times of both feet and pauses`() = runTest {
        val p = player()
        p.stepFrame()
        assertEquals(5.0, p.position, "right foot sample")
        p.stepFrame(+1)
        assertEquals(10.0, p.position)
        p.stepFrame(-1)
        assertEquals(5.0, p.position)
        p.stepFrame(3)
        assertEquals(20.0, p.position)

        p.seek(500.0)
        p.play()
        advanceTimeBy(103)
        p.stepFrame()
        assertFalse(p.state.value.playing)
        assertEquals(605.0, p.position)

        p.seek(0.0)
        p.stepFrame(-1)
        assertEquals(0.0, p.position, "nothing before the first sample")
        p.seek(timeline.endMillis)
        p.stepFrame()
        assertEquals(timeline.endMillis, p.position)
        assertFailsWith<IllegalArgumentException> { p.stepFrame(0) }
    }

    @Test
    fun `jumps to gait events and markers`() = runTest {
        val p = player()
        assertTrue(p.nextEvent())
        assertEquals(100.0, p.position, "first left heel strike")
        assertTrue(p.nextEvent(type = GaitEventType.TOE_OFF))
        assertEquals(700.0, p.position)
        assertTrue(p.nextEvent(Foot.LEFT, GaitEventType.HEEL_STRIKE))
        assertEquals(1100.0, p.position)
        assertTrue(p.previousEvent(type = GaitEventType.HEEL_STRIKE))
        assertEquals(100.0, p.position)
        assertFalse(p.nextEvent(Foot.RIGHT), "the right foot never leaves the ground")
        assertEquals(100.0, p.position)

        assertTrue(p.nextMarker())
        assertEquals(2500.0, p.position)
        assertTrue(p.nextMarker())
        assertFalse(p.nextMarker())
        assertTrue(p.previousMarker())
        assertEquals(2500.0, p.position)
    }

    @Test
    fun `jumping while playing keeps playing from the target`() = runTest {
        val p = player()
        p.play()
        advanceTimeBy(50)
        assertTrue(p.nextEvent())
        assertTrue(p.state.value.playing)
        advanceTimeBy(100)
        p.pause()
        assertEquals(200.0, p.position, 1e-9)
    }

    @Test
    fun `frame shows both feet and the balance at the position`() = runTest {
        val p = player()
        p.seek(300.0)
        val frame = p.frameAt()
        assertEquals(300.0, frame.left?.tMillis)
        assertEquals(295.0, frame.right?.tMillis)
        assertEquals(0.5, frame.balance!!.left, 1e-9)

        p.seek(800.0)
        assertEquals(1.0, p.frameAt().balance!!.right, 1e-9, "left foot in swing")
    }

    @Test
    fun `frames follow the position only`() = runTest {
        val p = player()
        val frames = mutableListOf<PlaybackFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.frames.collect { frames += it } }
        p.seek(300.0)
        p.setSpeed(2.0) // no new position, no new frame
        assertEquals(listOf(0.0, 300.0), frames.map { it.positionMillis })
        assertEquals(300.0, frames.last().left?.tMillis)
    }

    @Test
    fun `an empty timeline cannot play`() = runTest {
        val p = SessionPlayer(SessionTimeline(recorded(emptyList())), backgroundScope, { 0L })
        assertTrue(p.state.value.atEnd)
        p.play()
        assertFalse(p.state.value.playing)
        assertNull(p.frameAt().balance)
    }

    @Test
    fun `previous during playback does not stick to the event just jumped to`() = runTest {
        val p = player()
        p.seek(3600.0) // left heel strikes at 100, 1100, 2100, 3100, ...
        p.play()
        assertTrue(p.previousEvent(Foot.LEFT, GaitEventType.HEEL_STRIKE))
        assertEquals(3100.0, p.position)
        advanceTimeBy(50)
        assertTrue(p.previousEvent(Foot.LEFT, GaitEventType.HEEL_STRIKE))
        assertEquals(2100.0, p.position, "second press goes one further back")
        advanceTimeBy(600)
        assertTrue(p.previousEvent(Foot.LEFT, GaitEventType.HEEL_STRIKE))
        assertEquals(2100.0, p.position, "long after the jump: back to the start of the current step")

        p.seek(150.0)
        assertTrue(p.previousEvent(Foot.LEFT, GaitEventType.HEEL_STRIKE))
        assertEquals(100.0, p.position, "nothing before the first one: stays on it")
    }

    @Test
    fun `previous marker during playback steps back past the one just jumped to`() = runTest {
        val p = player()
        p.seek(7500.0)
        p.play()
        assertTrue(p.previousMarker())
        assertEquals(7000.0, p.position)
        advanceTimeBy(100)
        assertTrue(p.previousMarker())
        assertEquals(2500.0, p.position)
    }

    @Test
    fun `while paused previous takes the nearest one before the position`() = runTest {
        val p = player()
        p.seek(3150.0)
        assertTrue(p.previousEvent(Foot.LEFT, GaitEventType.HEEL_STRIKE))
        assertEquals(3100.0, p.position)
        p.stepFrame()
        assertTrue(p.previousEvent(Foot.LEFT, GaitEventType.HEEL_STRIKE))
        assertEquals(3100.0, p.position)
    }

    @Test
    fun `play at the end with a loop restarts from the loop start`() = runTest {
        val p = player()
        p.setLoop(1000.0, timeline.endMillis)
        p.seek(timeline.endMillis)
        assertTrue(p.state.value.atEnd)
        p.play()
        assertEquals(1000.0, p.position)
        advanceTimeBy(100)
        p.pause()
        assertEquals(1100.0, p.position, 1e-9)
    }

    @Test
    fun `cancelling the scope stops playback`() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val p = SessionPlayer(timeline, scope, { testScheduler.currentTime * 1_000_000 }, tickMillis = 10)
        p.play()
        advanceTimeBy(100)
        scope.cancel()
        runCurrent()
        assertFalse(p.state.value.playing)
        p.play() // a dead scope: the ticker cannot run, so it must not claim to be playing
        runCurrent()
        assertFalse(p.state.value.playing)
    }

    @Test
    fun `stance half width is a player setting`() = runTest {
        // Unequal loads, otherwise the combined COP does not depend on the stance width.
        val uneven =
            SessionTimeline(recorded(listOf(row(0.0, Foot.LEFT, load = 30.0), row(0.0, Foot.RIGHT, load = 10.0))))
        val p = SessionPlayer(uneven, backgroundScope, { 0L }, stanceHalfWidth = 2.0)
        val combined = p.frameAt().balance!!.combinedCop!!
        assertEquals(uneven.frameAt(0.0, 2.0).balance!!.combinedCop, combined)
        assertEquals(0.1 - 2.0 / 2, combined.x, 1e-9)
    }

    @Test
    fun `frame steps skip the other foot's sample a fraction of a millisecond later`() = runTest {
        val merged = SessionTimeline(
            recorded((0 until 100).flatMap { listOf(row(it * 10.0, Foot.LEFT), row(it * 10.0 + 0.3, Foot.RIGHT)) }),
        )
        val p = SessionPlayer(merged, backgroundScope, { 0L })
        val positions = (0 until 5).map {
            p.stepFrame()
            p.state.value.positionMillis
        }
        assertEquals(listOf(0.3, 10.3, 20.3, 30.3, 40.3), positions)
        p.stepFrame(-2)
        assertEquals(20.3, p.state.value.positionMillis)
        p.stepFrame(1000)
        assertEquals(990.3, p.state.value.positionMillis, "stops at the last frame")
    }
}

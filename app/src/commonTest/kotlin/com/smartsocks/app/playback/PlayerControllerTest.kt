package com.smartsocks.app.playback

import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.LoopRange
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerControllerTest {
    private fun TestScope.player(loaded: LoadedSession = loadedSession()) =
        PlayerController(loaded, backgroundScope, { testScheduler.currentTime * 1_000_000 })

    @Test
    fun aTapOnTheChartSeeksToTheMomentUnderIt() = runTest {
        val p = player()
        p.seek(5_000.0)
        // A 10 s window ends a quarter span after the position: 7.5 s at the right edge of a 40..1040 px plot.
        assertEquals(7_500.0, p.chartWindowEnd(5_000.0, 10_000.0))
        p.seekFromChart(540f, 40f, 1040f, 10_000.0)
        assertEquals(2_500.0, p.position, 1e-6)
        // Beyond the plot: clamped to its edges.
        p.seekFromChart(5000f, 40f, 1040f, 10_000.0)
        assertEquals(2_500.0 + 2_500.0, p.position, 1e-6)
    }

    @Test
    fun draggingTheChartMovesThePictureWithTheFinger() = runTest {
        val p = player()
        p.seek(5_000.0)
        p.dragChart(100f, 40f, 1040f, 10_000.0) // right by a tenth of the plot: back a tenth of the span
        assertEquals(4_000.0, p.position, 1e-3)
        p.dragChart(-300f, 40f, 1040f, 10_000.0)
        assertEquals(7_000.0, p.position, 1e-3)
        p.dragChart(-100_000f, 40f, 1040f, 10_000.0)
        assertEquals(p.timeline.endMillis, p.position, "clamped to the end")
    }

    @Test
    fun chartTimeIsClampedToThePlot() {
        assertEquals(1_000.0, PlayerController.chartTimeAt(110f, 10f, 110f, 1_000.0, 500.0))
        assertEquals(500.0, PlayerController.chartTimeAt(-50f, 10f, 110f, 1_000.0, 500.0))
        assertEquals(750.0, PlayerController.chartTimeAt(60f, 10f, 110f, 1_000.0, 500.0))
        assertEquals(1_000.0, PlayerController.chartTimeAt(60f, 10f, 10f, 1_000.0, 500.0), "empty plot")
    }

    @Test
    fun speedStepsThroughTheVideoSpeeds() = runTest {
        val p = player()
        assertEquals(1.0, p.state.value.speed)
        p.stepSpeed(faster = true)
        assertEquals(2.0, p.state.value.speed)
        p.setSpeed(4.0)
        p.stepSpeed(faster = true)
        assertEquals(4.0, p.state.value.speed, "stays at the top")
        p.setSpeed(1.0)
        repeat(5) { p.stepSpeed(faster = false) }
        assertEquals(0.1, p.state.value.speed, "stays at the bottom")
        p.setSpeed(9.0)
        assertEquals(4.0, p.state.value.speed, "clamped")
    }

    @Test
    fun slowMotionPlaysAtTheChosenSpeed() = runTest {
        val p = player()
        p.setSpeed(0.25)
        p.toggle()
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(p.state.value.playing)
        assertEquals(500.0, p.position, 20.0)
        p.toggle()
        val paused = p.position
        advanceTimeBy(1_000)
        assertEquals(paused, p.position)
    }

    @Test
    fun aLoopIsSetWithAThenB() = runTest {
        val p = player()
        p.seek(2_000.0)
        p.setLoopStart()
        assertEquals(2_000.0, p.pendingLoopStart.value)
        assertNull(p.state.value.loop)
        p.seek(4_000.0)
        assertTrue(p.setLoopEnd())
        assertEquals(LoopRange(2_000.0, 4_000.0), p.state.value.loop)
        assertNull(p.pendingLoopStart.value)

        // A again inside the loop moves its start; B alone moves its end.
        p.seek(3_000.0)
        p.setLoopStart()
        assertEquals(LoopRange(3_000.0, 4_000.0), p.state.value.loop)
        p.seek(3_500.0)
        p.setLoopEnd()
        assertEquals(LoopRange(3_000.0, 3_500.0), p.state.value.loop)

        // Played through, it wraps.
        p.seek(3_400.0)
        p.toggle()
        advanceTimeBy(300)
        runCurrent()
        assertTrue(p.position in 3_000.0..3_500.0, "${p.position}")
        p.toggle()

        p.clearLoop()
        assertNull(p.state.value.loop)
    }

    @Test
    fun bBeforeASwapsAndTooShortIsIgnored() = runTest {
        val p = player()
        p.seek(6_000.0)
        p.setLoopStart()
        p.seek(1_000.0)
        assertTrue(p.setLoopEnd())
        assertEquals(LoopRange(1_000.0, 6_000.0), p.state.value.loop)
        p.clearLoop()

        p.seek(1_000.0)
        p.setLoopStart()
        p.seek(1_005.0)
        assertFalse(p.setLoopEnd())
        assertNull(p.state.value.loop)
        assertEquals(1_000.0, p.pendingLoopStart.value, "A still waits for a usable B")
        p.clearLoop()
        assertNull(p.pendingLoopStart.value)

        // B without A: from the start.
        p.seek(3_000.0)
        p.setLoopEnd()
        assertEquals(LoopRange(0.0, 3_000.0), p.state.value.loop)
    }

    @Test
    fun stepsAndMarkersJumpToTheirMoments() = runTest {
        val p = player()
        // The left foot starts in contact (no event); the right one strikes at 0.5 s, the left at 1 s.
        assertTrue(p.nextStep())
        assertEquals(500.0, p.position)
        assertTrue(p.nextStep())
        assertEquals(1_000.0, p.position)
        assertTrue(p.previousStep())
        assertEquals(500.0, p.position)
        assertTrue(p.steps.size in 17..19, "${p.steps.size}")
        assertTrue(p.nextMarker())
        assertEquals(2_500.0, p.position)
        assertTrue(p.nextMarker())
        assertEquals(7_000.0, p.position)
        assertFalse(p.nextMarker())
        p.stepFrame(1)
        assertEquals(7_010.0, p.position, 1e-6)
        p.stepFrame(-2)
        assertEquals(6_990.0, p.position, 1e-6)
    }

    @Test
    fun theSnapshotShowsTheRecordedMomentWithItsTrail() = runTest {
        val p = player(loadedSession(syntheticTimeline(feet = setOf(Foot.LEFT))))
        val s = p.snapshot(1_300.0) // 300 ms into the left foot's stance
        val left = assertNotNull(s.left.state)
        assertTrue(left.inContact)
        assertEquals(325.0, left.totalLoad, 1.0)
        assertEquals(1.0, left.shares.sum(), 1e-6)
        assertNull(s.left.caption)
        // The last second of COP: the end of the previous stance (0.3..0.59 s) and this one so far, apart.
        val trail = s.left.trail
        assertEquals(30 + 31, trail.size)
        assertEquals(listOf(0, 30), trail.indices.filter { trail[it].startsSegment })
        assertEquals(0f, trail.last().age)
        assertEquals(1f, trail.first().age)
        assertNull(s.right.state)
        assertEquals("Not recorded", s.right.caption)
        assertEquals(1.0, assertNotNull(s.balance).left)

        // In the air: no COP, the trail of the last stance remains while it fades.
        val air = p.snapshot(1_700.0)
        assertFalse(assertNotNull(air.left.state).inContact)
        assertTrue(air.left.trail.isNotEmpty())
        assertTrue(air.left.trail.all { it.age > 0.09f })
    }

    @Test
    fun aFootThatJoinedLateSaysWhenBeforeItsFirstSample() = runTest {
        val p = player(loadedSession(syntheticTimeline(rightJoinsAt = 5_000.0)))
        val before = p.snapshot(3_000.0)
        assertNotNull(before.left.state)
        assertNull(before.right.state, "no stale sample before it joined")
        assertEquals("Joins at 0:05", before.right.caption)
        assertTrue(before.right.trail.isEmpty())
        assertNull(before.balance, "shown as –, not 100 % left")
        val after = p.snapshot(5_200.0)
        assertNotNull(after.right.state)
        assertNull(after.right.caption)
        assertNotNull(after.balance)
        assertEquals(setOf(Foot.LEFT, Foot.RIGHT), p.timeline.feet)
    }

    @Test
    fun theOverviewHoldsEachSlicesPeakLoad() {
        val timeline = syntheticTimeline()
        val o = PlaybackOverview.of(timeline, buckets = 100)
        assertEquals(100, o.buckets)
        assertEquals(325.0f, o.maxTotal, 1f)
        // 0.3 s: mid-stance of the left foot; 0.7 s: the left foot in the air, the right one in contact.
        assertEquals(325f, o.left[3], 1f)
        assertEquals(0f, o.left[7])
        assertTrue(o.right[7] > 100f)
        assertEquals(timeline.startMillis, o.timeOf(0))
        val single = PlaybackOverview.of(syntheticTimeline(feet = setOf(Foot.RIGHT)), 10)
        assertTrue(single.left.all { it.isNaN() }, "no samples: NaN")
    }

    @Test
    fun timesAndSpeedsAsTheTransportBarShowsThem() {
        assertEquals("0:00.0", PlayerController.timeText(0.0))
        assertEquals("1:05.3", PlayerController.timeText(65_340.0))
        assertEquals("1:02:05", PlayerController.timeText(3_725_000.0, tenths = false))
        assertEquals("−0:01.5", PlayerController.timeText(-1_500.0))
        assertEquals(
            listOf("0.1×", "0.25×", "0.5×", "1×", "2×", "4×"),
            PlayerController.SPEEDS.map(PlayerController::speedText),
        )
    }
}

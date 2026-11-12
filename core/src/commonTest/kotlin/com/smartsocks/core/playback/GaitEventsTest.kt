package com.smartsocks.core.playback

import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.GaitEventType.HEEL_STRIKE
import com.smartsocks.core.playback.GaitEventType.PEAK_LOAD
import com.smartsocks.core.playback.GaitEventType.TOE_OFF
import com.smartsocks.core.recording.CsvRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GaitEventsTest {
    private fun events(vararg feet: List<CsvRow>, minPhase: Double = 40.0) =
        GaitEvents.detect(SessionTimeline(recorded(feet.flatMap { it })), minPhase)

    @Test
    fun `contact transitions become heel strikes, toe offs and the stance peak`() {
        // In contact 100..690 with a load peak at 300, and again from 1000.
        val left = rows(Foot.LEFT, 0, 1500, loadAt = { t -> if (t == 300) 200.0 else 50.0 }) { t ->
            t in 100 until 700 || t >= 1000
        }
        val e = events(left).events
        assertEquals(
            listOf(
                GaitEvent(Foot.LEFT, HEEL_STRIKE, 100.0),
                GaitEvent(Foot.LEFT, PEAK_LOAD, 300.0),
                GaitEvent(Foot.LEFT, TOE_OFF, 700.0),
                GaitEvent(Foot.LEFT, HEEL_STRIKE, 1000.0),
            ),
            e,
        )
    }

    @Test
    fun `flickers shorter than the minimum phase are ignored`() {
        val left = rows(Foot.LEFT, 0, 1500) { t ->
            when {
                t in 300 until 320 -> false

                // 20 ms dropout in a stance
                t in 900 until 930 -> true

                // 30 ms blip in the swing
                else -> t in 100 until 700
            }
        }
        val e = events(left).events
        assertEquals(
            listOf(HEEL_STRIKE to 100.0, PEAK_LOAD to 100.0, TOE_OFF to 700.0),
            e.map { it.type to it.tMillis },
        )
        // With a shorter minimum both flickers count.
        val strict = events(left, minPhase = 10.0).events.filter { it.type != PEAK_LOAD }
        assertEquals(
            listOf(100.0, 300.0, 320.0, 700.0, 900.0, 930.0),
            strict.map { it.tMillis },
        )
    }

    @Test
    fun `a phase of exactly the minimum counts`() {
        val left = rows(Foot.LEFT, 0, 1000) { t -> t in 100 until 600 && t !in 300 until 340 }
        val toeOffs = events(left).events.filter { it.type == TOE_OFF }.map { it.tMillis }
        assertEquals(listOf(300.0, 600.0), toeOffs)
    }

    @Test
    fun `chatter at contact onset is stamped at the first lasting stretch`() {
        val left = rows(Foot.LEFT, 0, 1000) { t -> t in 100 until 120 || t in 130 until 700 }
        val strikes = events(left).events.filter { it.type == HEEL_STRIKE }.map { it.tMillis }
        assertEquals(listOf(130.0), strikes)
    }

    @Test
    fun `a recording that starts in contact has no first heel strike and no partial peaks`() {
        val left = rows(Foot.LEFT, 0, 2000, loadAt = { t -> 1000.0 - t }) { t -> t < 400 || t in 800 until 1200 }
        val e = events(left).events
        assertEquals(
            listOf(TOE_OFF to 400.0, HEEL_STRIKE to 800.0, PEAK_LOAD to 800.0, TOE_OFF to 1200.0),
            e.map { it.type to it.tMillis },
        )
    }

    @Test
    fun `a short last stretch is not an event`() {
        val left = rows(Foot.LEFT, 0, 1020) { t -> t in 100 until 700 || t >= 1000 }
        assertEquals(TOE_OFF, events(left).events.last().type)
    }

    @Test
    fun `navigation across both feet with filters`() {
        // Left stances 0+..: HS 100, TO 700; right HS 400, TO 1000; left HS 1300.
        val left = rows(Foot.LEFT, 0, 1600) { t -> t in 100 until 700 || t >= 1300 }
        val right = rows(Foot.RIGHT, 0, 1600) { t -> t in 400 until 1000 }
        val g = events(left, right)

        val strikes = g.events.filter { it.type == HEEL_STRIKE }.map { it.foot to it.tMillis }
        assertEquals(listOf(Foot.LEFT to 100.0, Foot.RIGHT to 400.0, Foot.LEFT to 1300.0), strikes)

        assertEquals(GaitEvent(Foot.LEFT, HEEL_STRIKE, 100.0), g.nextEvent(0.0))
        assertEquals(GaitEvent(Foot.RIGHT, HEEL_STRIKE, 400.0), g.nextEvent(100.0, type = HEEL_STRIKE))
        assertEquals(GaitEvent(Foot.LEFT, HEEL_STRIKE, 1300.0), g.nextEvent(100.0, Foot.LEFT, HEEL_STRIKE))
        assertEquals(GaitEvent(Foot.RIGHT, TOE_OFF, 1000.0), g.nextEvent(400.0, Foot.RIGHT, TOE_OFF))
        assertNull(g.nextEvent(1300.0))

        assertEquals(GaitEvent(Foot.RIGHT, TOE_OFF, 1000.0), g.previousEvent(1300.0))
        assertEquals(GaitEvent(Foot.LEFT, TOE_OFF, 700.0), g.previousEvent(1300.0, Foot.LEFT))
        assertEquals(GaitEvent(Foot.LEFT, HEEL_STRIKE, 100.0), g.previousEvent(400.0, type = HEEL_STRIKE))
        assertNull(g.previousEvent(100.0))
        assertTrue(g.forFoot(Foot.RIGHT).all { it.foot == Foot.RIGHT })
    }

    @Test
    fun `empty timeline and bad minimum`() {
        assertTrue(GaitEvents.detect(SessionTimeline(recorded(emptyList()))).events.isEmpty())
        assertFailsWith<IllegalArgumentException> { GaitEvents.detect(SessionTimeline(recorded(emptyList())), -1.0) }
    }

    @Test
    fun `a flicker at the very start sets no initial state`() {
        // 10 ms of contact noise, then a long swing, then a full stance.
        val noisyOn = rows(Foot.LEFT, 0, 2000) { t -> t < 10 || t in 500 until 1100 }
        assertEquals(
            listOf(HEEL_STRIKE to 500.0, PEAK_LOAD to 500.0, TOE_OFF to 1100.0),
            events(noisyOn).events.map { it.type to it.tMillis },
        )
        // 10 ms without contact, then a stance cut by the recording start: no strike, no peak for it.
        val noisyOff = rows(Foot.LEFT, 0, 2000) { t -> t in 10 until 600 || t >= 1200 }
        assertEquals(
            listOf(TOE_OFF to 600.0, HEEL_STRIKE to 1200.0),
            events(noisyOff).events.map { it.type to it.tMillis },
        )
    }

    @Test
    fun `nothing lasting means no events`() {
        val chatter = rows(Foot.LEFT, 0, 400) { t -> (t / 20) % 2 == 0 }
        assertTrue(events(chatter).events.isEmpty())
    }
}

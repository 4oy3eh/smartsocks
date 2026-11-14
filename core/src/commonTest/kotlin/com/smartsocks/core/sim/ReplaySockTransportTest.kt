package com.smartsocks.core.sim

import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketEncoder
import com.smartsocks.core.protocol.PacketFormat
import com.smartsocks.core.protocol.RawPacket
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.recording.CsvRow
import com.smartsocks.core.recording.CsvSessionReader
import com.smartsocks.core.recording.InMemoryRecordingSink
import com.smartsocks.core.recording.LineSource
import com.smartsocks.core.recording.RawDumpReader
import com.smartsocks.core.recording.RawPacketRecorder
import com.smartsocks.core.session.ConnectionState
import com.smartsocks.core.session.SockSession
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.session.TimedPacket
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A packet whose every byte says which one it is, unknown fields included; count 0 is [i]. */
private fun packet(i: Int): ByteArray = PacketEncoder.encode(intArrayOf(i, i + 1, i + 2, i + 3, i + 4, i + 5)).also {
    for (k in 1 until PacketFormat.SENSOR_OFFSET) it[k] = (i * 7 + k).toByte()
    it[PacketFormat.SIZE - 2] = (i * 13).toByte()
}

private fun indexOf(bytes: ByteArray): Int = RawPacket(bytes).count(0)

/** A raw dump as [RawPacketRecorder] writes it: (t ms, foot, packet). */
private fun dumpOf(rows: List<Triple<Double, Foot, ByteArray>>): List<String> {
    val sink = InMemoryRecordingSink()
    val recorder = RawPacketRecorder(sink)
    // The recorder makes times relative to the first packet: anchor at 0 with a first packet that is dropped below.
    recorder.record(TimedPacket(Foot.LEFT, 0, RawPacket(packet(0)), 0))
    for ((t, foot, bytes) in rows) {
        val nanos = (t * 1_000_000).toLong()
        recorder.record(TimedPacket(foot, nanos, RawPacket(bytes), nanos + 3_000_000))
    }
    recorder.close()
    return sink.lines.filterIndexed { i, _ -> i != 2 }
}

/** [count] packets of [foot] every [periodMillis], packet i is [packet] (i + 1). */
private fun steady(count: Int, periodMillis: Double = 10.0, foot: Foot = Foot.LEFT) =
    dumpOf(List(count) { Triple(it * periodMillis, foot, packet(it + 1)) })

/** Both feet every 10 ms from one clock: left packet k is [packet] (k + 1), right packet k is [packet] (20000 + k). */
private fun twoFeet(left: Iterable<Int>, right: Iterable<Int>): List<String> = dumpOf(
    buildList {
        for (k in left) add(Triple(k * 10.0, Foot.LEFT, packet(k + 1)))
        for (k in right) add(Triple(k * 10.0 + 0.5, Foot.RIGHT, packet(RIGHT_BASE + k)))
    }.sortedBy { it.first },
)

private const val RIGHT_BASE = 20_000

private val recorded100 = StreamSettings(SampleRate.HZ_100, MeasurementRange.K64)

private class Opened {
    var count = 0
    var closed = 0
}

private fun TestScope.testClock(speed: Double = 1.0, maxGapMillis: Double? = 1_000.0) =
    ReplayClock(speed, maxGapMillis) { testScheduler.currentTime * 1_000_000 }

private fun TestScope.replay(
    lines: List<String>,
    options: ReplayOptions = ReplayOptions(connectDelayMillis = 0),
    foot: Foot = Foot.LEFT,
    clock: ReplayClock = testClock(),
    opened: Opened = Opened(),
) = ReplaySockTransport(foot, recorded100, {
    opened.count++
    LineSource(lines.iterator()) { opened.closed++ }
}, clock, options)

/** A completed packet: its [index] (count 0) and when its last byte was read, virtual ms. */
private class Emission(val atMillis: Long, val index: Int)

/** Reads until the link closes; [stall]: virtual ms during which it does not read. */
private class Reader(private val transport: ReplaySockTransport, private val now: () -> Long) {
    val bytes = mutableListOf<Byte>()
    val emissions = mutableListOf<Emission>()
    var ended = false
    var stall: LongRange? = null
    val packets: Int get() = bytes.size / PacketFormat.SIZE

    suspend fun run() {
        val buffer = ByteArray(100) // smaller than a burst: reads split packets
        while (true) {
            stall?.let { if (now() in it) delay(it.last - now()) }
            val n = transport.read(buffer)
            if (n < 0) {
                ended = true
                return
            }
            for (i in 0 until n) {
                bytes += buffer[i]
                if (bytes.size % PacketFormat.SIZE == 0) emissions += Emission(now(), indexOf(packet(packets - 1)))
            }
        }
    }

    fun packet(index: Int): ByteArray =
        bytes.subList(index * PacketFormat.SIZE, (index + 1) * PacketFormat.SIZE).toByteArray()
}

private fun TestScope.startReading(transport: ReplaySockTransport): Reader {
    val reader = Reader(transport) { testScheduler.currentTime }
    launch { reader.run() }
    return reader
}

private suspend fun ReplaySockTransport.send(command: SockCommand) = write(command.encode())

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaySockTransportTest {
    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `plays the recorded packets byte for byte, unknown fields included`() = runTest {
        val transport = replay(steady(30))
        transport.open()
        val reader = startReading(transport)
        transport.send(SockCommand.Start)
        advanceUntilIdle()
        assertTrue(reader.ended, "the link closes at the end")
        assertEquals(30, reader.packets)
        for (i in 0 until 30) assertContentEquals(packet(i + 1), reader.packet(i), "packet $i")
        assertEquals(30, transport.packetsSent)
    }

    @Test
    fun `is paced by the recorded time`() = runTest {
        val transport = replay(steady(300))
        transport.open()
        val reader = startReading(transport)
        transport.send(SockCommand.Start)
        advance(1_000)
        // Bursts every 20 ms; by t = 1000 ms every packet due at 0..1000 ms: 101.
        assertEquals(101, reader.packets)
        advance(500)
        assertEquals(151, reader.packets)
        transport.close()
    }

    @Test
    fun `a speed factor scales the pace`() = runTest {
        val fast = replay(steady(300), clock = testClock(speed = 2.0))
        fast.open()
        val reader = startReading(fast)
        fast.send(SockCommand.Start)
        advance(500)
        assertEquals(101, reader.packets)
        fast.close()

        val slow = replay(steady(300), clock = testClock(speed = 0.5))
        slow.open()
        val slowReader = startReading(slow)
        slow.send(SockCommand.Start)
        advance(1_000)
        assertEquals(51, slowReader.packets)
        slow.close()
    }

    @Test
    fun `the clock sets the pace, not delay`() = runTest {
        var nanos = 0L
        val clock = ReplayClock { nanos }
        val transport = replay(steady(300), clock = clock)
        transport.open()
        val reader = startReading(transport)
        transport.send(SockCommand.Start)
        // `delay` "overshoots": every 20 ms burst covers 30 ms of clock, as with Windows' coarse timers.
        repeat(10) {
            nanos += 30_000_000
            advance(20)
        }
        assertEquals(31, reader.packets)
        transport.close()
    }

    @Test
    fun `silent until START, STOP pauses and START resumes where it stopped`() = runTest {
        val clock = testClock()
        val transport = replay(steady(300), clock = clock)
        transport.open()
        val reader = startReading(transport)
        transport.send(SockCommand.SetRange(MeasurementRange.K64))
        transport.send(SockCommand.SetSampleRate(SampleRate.HZ_100))
        advance(1_000)
        assertEquals(0, reader.packets)
        assertEquals(0, clock.active)

        transport.send(SockCommand.Start)
        assertEquals(1, clock.active)
        advance(100)
        assertEquals(11, reader.packets)

        transport.send(SockCommand.Stop)
        assertEquals(0, clock.active)
        advance(2_000)
        assertEquals(11, reader.packets)
        assertEquals(100.0, clock.positionMillis(), "the clock stands still while nothing plays")

        transport.send(SockCommand.Start)
        advance(20)
        assertEquals(13, reader.packets)
        assertContentEquals(packet(12), reader.packet(11), "resumed with the packet after the last one")
        assertFalse(transport.settingsMismatch)
        transport.close()
        assertEquals(0, clock.active)
    }

    @Test
    fun `the end closes the link, and a new open starts from the beginning`() = runTest {
        val opened = Opened()
        val clock = testClock()
        val transport = replay(steady(10), opened = opened, clock = clock)
        transport.open()
        val reader = startReading(transport)
        transport.send(SockCommand.Start)
        advance(80)
        assertFalse(reader.ended)
        advanceUntilIdle()
        assertTrue(reader.ended)
        assertEquals(10, reader.packets)
        assertEquals(1, opened.closed, "the dump is closed at the end")
        assertEquals(0.0, clock.positionMillis(), "ended: the clock starts over")
        assertEquals(-1, transport.read(ByteArray(10)))
        assertFailsWith<IllegalStateException> { transport.send(SockCommand.Start) }

        transport.open()
        val again = startReading(transport)
        transport.send(SockCommand.Start)
        advanceUntilIdle()
        assertEquals(10, again.packets)
        assertContentEquals(packet(1), again.packet(0))
        assertEquals(2, opened.count)
    }

    @Test
    fun `loops at the end, one period after the last packet`() = runTest {
        val opened = Opened()
        val transport = replay(steady(10), ReplayOptions(connectDelayMillis = 0, loop = true), opened = opened)
        transport.open()
        val reader = startReading(transport)
        transport.send(SockCommand.Start)
        advance(1_000)
        // An even 100 Hz across the seams: 101 packets by 1000 ms.
        assertEquals(101, reader.packets)
        assertEquals(10, transport.loops)
        for (i in 0 until 101) assertContentEquals(packet(i % 10 + 1), reader.packet(i), "packet $i")
        // The loop can be switched off while playing: this pass is the last.
        transport.loop = false
        advanceUntilIdle()
        assertTrue(reader.ended)
        assertEquals(110, reader.packets)
        assertEquals(opened.count, opened.closed, "every pass's dump is closed")
    }

    @Test
    fun `long silences of the whole dump are cut to one period unless kept`() = runTest {
        val rows = listOf(0.0, 10.0, 20.0, 5_020.0, 5_030.0).mapIndexed { i, t -> Triple(t, Foot.LEFT, packet(i + 1)) }
        val cut = replay(dumpOf(rows))
        cut.open()
        val cutReader = startReading(cut)
        cut.send(SockCommand.Start)
        advance(40)
        assertEquals(5, cutReader.packets)

        val kept = replay(dumpOf(rows), clock = testClock(maxGapMillis = null))
        kept.open()
        val keptReader = startReading(kept)
        kept.send(SockCommand.Start)
        advance(1_000)
        assertEquals(3, keptReader.packets)
        advance(4_020)
        assertEquals(4, keptReader.packets)
        advance(20)
        assertEquals(5, keptReader.packets)
        advanceUntilIdle()
        assertTrue(cutReader.ended && keptReader.ended)
    }

    /**
     * Every packet of either foot was emitted in the first burst at or after it was due (`pass · 4000 + k · 10` ms)
     * and packets of equal recorded time on both feet came out together.
     */
    private fun assertInStep(left: Reader, right: Reader, passMillis: Long, from: Long = 0) {
        // The pass a packet belongs to: the last one that had it due by its emission (a foot may skip passes' parts).
        fun occurrences(r: Reader, base: Int) = buildMap<Pair<Int, Int>, Long> {
            for (e in r.emissions) {
                val k = e.index - base
                val pass = ((e.atMillis - k * 10.0) / passMillis).toInt()
                assertTrue(k to pass !in this, "packet $k twice in pass $pass")
                put(k to pass, e.atMillis)
            }
        }
        val l = occurrences(left, 1)
        val r = occurrences(right, RIGHT_BASE)
        for ((key, at) in r) {
            if (at < from) continue
            val (k, pass) = key
            val due = pass * passMillis + k * 10.0
            assertTrue(at - due in 0.0..20.0, "right $k of pass $pass at $at, due $due")
            val leftAt = l[key] ?: continue
            assertTrue(abs(leftAt - at) <= 20, "right $k of pass $pass at $at, left at $leftAt")
        }
        for ((key, at) in l) {
            if (at < from) continue
            val due = key.second * passMillis + key.first * 10.0
            assertTrue(at - due in 0.0..20.0, "left ${key.first} of pass ${key.second} at $at, due $due")
        }
    }

    private fun TestScope.pair(
        dump: List<String>,
        loop: Boolean = true,
    ): Triple<ReplayClock, ReplaySockTransport, ReplaySockTransport> {
        val clock = testClock()
        val options = ReplayOptions(connectDelayMillis = 0, loop = loop)
        return Triple(clock, replay(dump, options, Foot.LEFT, clock), replay(dump, options, Foot.RIGHT, clock))
    }

    @Test
    fun `both feet stay in step across a gap of one foot and loops with different ends`() = runTest {
        // The right foot drops out for a second (no cut: the left one goes on) and ends half a second early.
        val (_, left, right) = pair(twoFeet(0 until 400, (0 until 350).filter { it !in 100 until 200 }))
        left.open()
        right.open()
        val l = startReading(left)
        val r = startReading(right)
        left.send(SockCommand.Start)
        right.send(SockCommand.Start)
        advance(10_000)
        assertTrue(r.emissions.none { it.index - RIGHT_BASE in 100 until 200 })
        assertInStep(l, r, passMillis = 4_000)
        assertEquals(2, left.loops)
        assertEquals(2, right.loops)
        // The third pass of the right foot started with the left one's, at 8 s.
        assertTrue(r.emissions.any { it.index == RIGHT_BASE && it.atMillis in 8_000L..8_020L })
        left.close()
        right.close()
    }

    @Test
    fun `a foot that joined late keeps its lead-in while the other plays`() = runTest {
        val (_, left, right) = pair(twoFeet(0 until 400, 200 until 400))
        left.open()
        right.open()
        val l = startReading(left)
        val r = startReading(right)
        left.send(SockCommand.Start)
        right.send(SockCommand.Start)
        advance(1_990)
        assertEquals(0, r.packets, "silent until it joined")
        advance(6_000)
        assertInStep(l, r, passMillis = 4_000)
        left.close()
        right.close()
    }

    @Test
    fun `a foot that pauses or starts later resumes in step with the other`() = runTest {
        val (clock, left, right) = pair(twoFeet(0 until 400, 0 until 400))
        left.open()
        right.open()
        val l = startReading(left)
        val r = startReading(right)
        left.send(SockCommand.Start)
        advance(500)
        // The right foot connects half a second later: it starts where the left one is.
        right.send(SockCommand.Start)
        advance(500)
        right.send(SockCommand.Stop)
        advance(1_500)
        right.send(SockCommand.Start)
        advance(3_000)
        assertEquals(2, clock.active)
        assertTrue(r.emissions.first().index - RIGHT_BASE >= 50, "did not start from the beginning")
        // In the first pass (the second one plays them).
        val fromPause = r.emissions.filter { it.atMillis < 4_000 && (it.index - RIGHT_BASE) in 102 until 250 }
        assertTrue(
            fromPause.isEmpty(),
            "nothing from the pause: ${fromPause.map {
                it.atMillis to it.index - RIGHT_BASE
            }}",
        )
        assertTrue(right.packetsSkipped > 0)
        assertInStep(l, r, passMillis = 4_000)
        left.close()
        right.close()
    }

    @Test
    fun `a stalled reader catches up at once, at most the lag limit, and stays in step`() = runTest {
        val (_, left, right) = pair(twoFeet(0 until 400, 0 until 400))
        left.open()
        right.open()
        val l = startReading(left)
        val r = startReading(right)
        r.stall = 1_000L..1_500L
        left.send(SockCommand.Start)
        right.send(SockCommand.Start)
        advance(3_000)
        val catchUp = r.emissions.filter { it.atMillis in 1_500L..1_520L }
        // 200 ms of packets (and the one at the edge), the 300 ms before skipped.
        assertTrue(catchUp.size in 20..22, "${catchUp.size} packets in the catch-up burst")
        assertTrue(right.packetsSkipped in 28L..32L, "${right.packetsSkipped} skipped")
        assertInStep(l, r, passMillis = 4_000, from = 1_521)
        left.close()
        right.close()
    }

    @Test
    fun `a cut last line is left out, a bad line in the middle fails the link after what came before`() = runTest {
        val good = steady(5)
        val cut = replay(good + good.last().dropLast(20))
        cut.open()
        val reader = startReading(cut)
        cut.send(SockCommand.Start)
        advanceUntilIdle()
        assertTrue(reader.ended)
        assertEquals(5, reader.packets)

        // Header, column line, two packets, the bad line, three packets.
        val bad = replay(good.take(4) + "12.0,12.0,LEFT,zz" + good.drop(4))
        bad.open()
        bad.send(SockCommand.Start)
        val buffer = ByteArray(1024)
        var bytes = 0
        val error = assertFailsWith<IllegalArgumentException> {
            while (true) {
                val n = bad.read(buffer)
                if (n < 0) break
                bytes += n
            }
        }
        assertEquals(2 * PacketFormat.SIZE, bytes, "the packets before the bad line were delivered")
        assertTrue("Line 5" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `refuses to open what it cannot play`() = runTest {
        assertFailsWith<IllegalArgumentException> { replay(listOf("t_ms,foot,r1")).open() }
        assertFailsWith<IllegalStateException> { replay(steady(5), foot = Foot.RIGHT).open() }
        val opened = Opened()
        val empty = replay(steady(0), opened = opened)
        assertFailsWith<IllegalStateException> { empty.open() }
        assertEquals(1, opened.closed, "closed after a failed open")
        assertEquals(-1, empty.read(ByteArray(4)))
    }

    @Test
    fun `other settings than recorded show as a mismatch and change nothing`() = runTest {
        val transport = replay(steady(20))
        transport.open()
        for (c in SockCommand.startSequence(SampleRate.HZ_100, MeasurementRange.K128)) transport.send(c)
        assertTrue(transport.settingsMismatch)
        assertEquals(MeasurementRange.K128, transport.requestedRange)
        val reader = startReading(transport)
        advanceUntilIdle()
        assertContentEquals(packet(1), reader.packet(0))
        assertEquals(20, reader.packets)
        assertEquals(5, transport.receivedCommands.size)

        val matching = replay(steady(20))
        matching.open()
        for (c in SockCommand.startSequence(SampleRate.HZ_100, MeasurementRange.K64)) matching.send(c)
        assertFalse(matching.settingsMismatch)
        matching.close()
    }

    @Test
    fun `replaying both feet of a recorded session at once reproduces its samples and their timing`() = runTest {
        val csv = InMemoryRecordingSink()
        val raw = InMemoryRecordingSink()
        val settings = StreamSettings(SampleRate.HZ_100, MeasurementRange.K64)
        SimulatedRecording.write(csv, raw, seconds = 4.0, rate = settings.rate, range = settings.range)
        val rows = CsvSessionReader.read(csv.lines).rows
        val nanos = { testScheduler.currentTime * 1_000_000 }
        val clock = ReplayClock(clockNanos = nanos)
        val states = Foot.entries.associateWith { mutableListOf<FootState>() }
        val sessions = Foot.entries.map { foot ->
            val transport = ReplaySockTransport(foot, settings, { LineSource(raw.lines.iterator()) }, clock)
            SockSession(foot, transport, settings, clockNanos = nanos)
        }
        val collectors = sessions.map { s ->
            launch(start = CoroutineStart.UNDISPATCHED) { s.states.collect { states.getValue(s.foot) += it } }
        }
        val runs = sessions.map { s -> launch { s.run() } }
        runs.forEach { it.join() }
        collectors.forEach { it.cancel() }
        for (s in sessions) assertEquals(ConnectionState.Disconnected("Link closed"), s.connection.value)

        val t0 = states.getValue(Foot.LEFT).first().timestampNanos
        val r0 = rows.first { it.foot == Foot.LEFT }.tMillis
        for (foot in Foot.entries) {
            val expected = rows.filter { it.foot == foot }
            val got = states.getValue(foot)
            assertEquals(expected.size, got.size, "$foot samples")
            for ((i, pair) in expected.zip(got).withIndex()) {
                val (row, state) = pair
                assertSameSample(row, state, "$foot #$i")
                // On the shared time axis: within a burst of the recording (the sample clock smooths the rest).
                val at = (state.timestampNanos - t0) / 1e6
                assertClose(row.tMillis - r0, at, 25.0, "$foot #$i time")
            }
        }
    }

    private fun assertSameSample(row: CsvRow, state: FootState, what: String) {
        for (i in 0 until 6) {
            assertClose(row.resistanceOhms[i], state.resistanceOhms[i], 0.051, "$what r${i + 1}")
            assertClose(row.loads[i], state.loads[i], 0.0011, "$what load${i + 1}")
        }
        assertClose(row.totalLoad, state.totalLoad, 0.004, "$what total")
        assertEquals(row.inContact, state.inContact, "$what contact")
        // The raw COP; the smoothed one depends on the sample stamps, which the session steers towards arrival times.
        assertEquals(row.rawCop == null, state.rawCop == null, "$what cop")
        val a = row.rawCop
        val b = state.rawCop
        if (a != null && b != null) {
            assertClose(a.x, b.x, 1e-3, "$what cop x")
            assertClose(a.y, b.y, 1e-3, "$what cop y")
        }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double, what: String) {
        val same = expected == actual || (expected.isNaN() && actual.isNaN()) || abs(expected - actual) <= tolerance
        assertTrue(same, "$what: expected $expected, was $actual")
    }
}

class RawDumpReaderTest {
    @Test
    fun `reads both versions, picks a foot and falls back to the host time`() {
        val v2 = listOf(
            "# smartsocks-raw=2",
            "t_ms,host_ms,foot,hex",
            "0.000,0.000,LEFT,${hex(packet(1))}",
            "2.500,20.000,RIGHT,${hex(packet(2))}",
            ",40.000,LEFT,${hex(packet(3)).uppercase()}\r",
            "",
        )
        val reader = RawDumpReader(v2.iterator(), Foot.LEFT)
        assertTrue(reader.next())
        assertEquals(0.0, reader.tMillis)
        assertContentEquals(packet(1), reader.packet)
        assertTrue(reader.next())
        assertEquals(40.0, reader.tMillis, "no t_ms: the host time")
        assertContentEquals(packet(3), reader.packet)
        assertFalse(reader.next())
        assertFalse(reader.truncatedLastLine)
        assertEquals(2, reader.version)

        val v1 = listOf("# smartsocks-raw=1", "t_ms,foot,hex", "-7.125,RIGHT,${hex(packet(4))}")
        val old = RawDumpReader(v1.iterator())
        assertTrue(old.next())
        assertEquals(Foot.RIGHT, old.packetFoot)
        assertEquals(-7.125, old.tMillis)
        assertTrue(old.hostMillis.isNaN())
        assertFalse(old.next())
    }

    @Test
    fun `every foot's time, the bytes only of the feet asked for`() {
        val lines = listOf(
            "# smartsocks-raw=2",
            "t_ms,host_ms,foot,hex",
            "1.5,0,LEFT,${hex(packet(1))}",
            "2.25,0,RIGHT,not-even-hex",
            "1e1,0,LEFT,${hex(packet(3))}",
        )
        val reader = RawDumpReader(lines.iterator(), foot = null, bytesOf = setOf(Foot.LEFT))
        assertTrue(reader.next() && reader.hasBytes && reader.tMillis == 1.5)
        assertTrue(reader.next())
        assertEquals(Foot.RIGHT, reader.packetFoot)
        assertFalse(reader.hasBytes)
        assertEquals(2.25, reader.tMillis)
        assertTrue(reader.next())
        assertEquals(10.0, reader.tMillis)
        assertContentEquals(packet(3), reader.packet)
        assertFalse(reader.next())
    }

    @Test
    fun `malformed lines`() {
        val head = listOf("# smartsocks-raw=2", "t_ms,host_ms,foot,hex")
        val ok = "1.0,1.0,LEFT,${hex(packet(1))}"
        for (bad in listOf(
            "1.0,1.0,LEFT,abc",
            "1.0,1.0,MIDDLE,${hex(packet(1))}",
            "x,y,LEFT,${hex(packet(1))}",
            "1.0",
        )) {
            val last = RawDumpReader((head + ok + bad).iterator())
            assertTrue(last.next())
            assertFalse(last.next(), bad)
            assertTrue(last.truncatedLastLine)
            val middle = RawDumpReader((head + bad + ok).iterator())
            assertFailsWith<IllegalArgumentException>(bad) { middle.next() }
        }
        assertFailsWith<IllegalArgumentException> { RawDumpReader(listOf("t_ms,host_ms,foot,hex").iterator()).next() }
        assertFailsWith<IllegalArgumentException> { RawDumpReader(listOf("# smartsocks-raw=3").iterator()).next() }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

package com.smartsocks.core.sim

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketDecoder
import com.smartsocks.core.protocol.PacketFormat
import com.smartsocks.core.protocol.RawPacket
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GaitModelTest {
    private val weightN = 70 * 9.81

    @Test
    fun `walking rolls from heel to forefoot`() {
        val model = GaitModel(Foot.LEFT, cadenceStepsPerMin = 100.0)
        val stride = 1.2
        var heelPeakT = 0.0
        var forePeakT = 0.0
        var heelMax = 0.0
        var foreMax = 0.0
        for (i in 0 until 1200) {
            val t = i * stride / 1200
            val f = model.forces(t, SimMode.Walking)
            if (f[4] + f[5] > heelMax) {
                heelMax = f[4] + f[5]
                heelPeakT = t
            }
            if (f[0] + f[1] > foreMax) {
                foreMax = f[0] + f[1]
                forePeakT = t
            }
        }
        assertTrue(heelPeakT < forePeakT, "heel peak $heelPeakT should precede forefoot peak $forePeakT")
        assertTrue(model.forces(stride * 0.8, SimMode.Walking).all { it == 0.0 }, "swing phase is unloaded")
    }

    @Test
    fun `at least one foot is on the ground while walking`() {
        val left = GaitModel(Foot.LEFT)
        val right = GaitModel(Foot.RIGHT)
        for (i in 0 until 1000) {
            val t = i * 0.005
            val total = left.forces(t, SimMode.Walking).sum() + right.forces(t, SimMode.Walking).sum()
            assertTrue(total > 0.0, "both feet in the air at t=$t")
        }
    }

    @Test
    fun `standing carries body weight on both feet`() {
        val left = GaitModel(Foot.LEFT)
        val right = GaitModel(Foot.RIGHT)
        for (t in listOf(0.0, 1.3, 2.7, 4.1)) {
            val total = left.forces(t, SimMode.Standing).sum() + right.forces(t, SimMode.Standing).sum()
            assertEquals(weightN, total, 1e-6)
        }
    }

    @Test
    fun `lifted foot has no load`() {
        assertTrue(GaitModel(Foot.RIGHT).forces(0.3, SimMode.Lifted).all { it == 0.0 })
    }
}

class SimulatedSockTransportTest {
    private suspend fun SimulatedSockTransport.start(rate: SampleRate, range: MeasurementRange) {
        open()
        for (c in SockCommand.startSequence(rate, range)) write(c.encode())
    }

    /** Reads for [millis] of virtual time and returns the decoded packets. */
    private suspend fun SimulatedSockTransport.collect(millis: Long): List<RawPacket> {
        val packets = mutableListOf<RawPacket>()
        val decoder = PacketDecoder { packets += it }
        val buffer = ByteArray(256)
        withTimeoutOrNull(millis) {
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                decoder.feed(buffer, 0, n)
            }
        }
        return packets
    }

    @Test
    fun `silent until started`() = runTest {
        val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0))
        sim.open()
        assertNull(withTimeoutOrNull(1_000) { sim.read(ByteArray(64)) })
    }

    @Test
    fun `streams at the configured rate`() = runTest {
        for (rate in listOf(SampleRate.HZ_10, SampleRate.HZ_100, SampleRate.HZ_200)) {
            val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0))
            sim.start(rate, MeasurementRange.K64)
            val packets = sim.collect(2_000)
            // ±1 packet for the window edge.
            assertEquals(2.0 * rate.hz, packets.size.toDouble(), maxOf(1.0, rate.hz * 0.05), "rate $rate")
        }
    }

    @Test
    fun `applies range and records commands`() = runTest {
        val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted))
        sim.start(SampleRate.HZ_50, MeasurementRange.K2)
        assertEquals(MeasurementRange.K2, sim.range)
        assertEquals(SampleRate.HZ_50, sim.rate)
        assertEquals(SockCommand.startSequence(SampleRate.HZ_50, MeasurementRange.K2), sim.receivedCommands)
        // An unloaded FSR (~2 MOhm) is far beyond a 2 kOhm range: every channel saturates.
        val packet = sim.collect(200).first()
        for (ch in 0 until 6) assertEquals(PacketFormat.MAX_COUNT, packet.count(ch))
    }

    @Test
    fun `corrupted stream still yields most packets`() = runTest {
        val sim = SimulatedSockTransport(
            Foot.RIGHT,
            SimConfig(connectDelayMillis = 0, corruptionRate = 0.05, randomSplit = true),
        )
        sim.start(SampleRate.HZ_100, MeasurementRange.K64)
        val packets = sim.collect(5_000)
        assertTrue(packets.size in 400..500, "got ${packets.size} of 500")
    }

    @Test
    fun `read returns -1 after a link drop, after delivering everything before it`() = runTest {
        // 100 Hz in 20 ms bursts of 2: the drop happens in the middle of the sixth burst.
        val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0, disconnectAfterPackets = 11))
        sim.start(SampleRate.HZ_100, MeasurementRange.K64)
        val packets = sim.collect(5_000)
        assertEquals(11, packets.size)
        assertEquals(-1, sim.read(ByteArray(1024)))
    }

    @Test
    fun `close wakes a read that waits before START`() = runTest {
        val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0))
        sim.open()
        var result: Int? = null
        launch { result = sim.read(ByteArray(64)) }
        runCurrent()
        assertNull(result)
        sim.close()
        runCurrent()
        assertEquals(-1, result)
    }

    @Test
    fun `can be reopened after close with a fresh link`() = runTest {
        val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0, disconnectAfterPackets = 20))
        sim.start(SampleRate.HZ_100, MeasurementRange.K64)
        assertEquals(20, sim.collect(5_000).size)
        sim.close()
        assertEquals(-1, sim.read(ByteArray(64)))

        sim.open()
        // Idle again: silent until started, and the drop counter starts over.
        assertNull(withTimeoutOrNull(500) { sim.read(ByteArray(64)) })
        for (c in SockCommand.startSequence(SampleRate.HZ_100, MeasurementRange.K64)) sim.write(c.encode())
        assertEquals(20, sim.collect(5_000).size)
    }

    @Test
    fun `unloaded sensors sit in range at 128k and above but saturate below`() = runTest {
        val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted))
        sim.start(SampleRate.HZ_50, MeasurementRange.K128)
        val packet = sim.collect(200).first()
        for (ch in 0 until 6) {
            val ohms = MeasurementRange.K128.countsToOhms(packet.count(ch))
            assertTrue(ohms in 70_000.0..128_000.0, "channel $ch: $ohms")
        }
        val tight = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0, mode = SimMode.Lifted))
        tight.start(SampleRate.HZ_50, MeasurementRange.K64)
        assertEquals(PacketFormat.MAX_COUNT, tight.collect(200).first().count(0))
    }

    @Test
    fun `random split copes with tiny buffers and an empty one`() = runTest {
        val sim = SimulatedSockTransport(Foot.LEFT, SimConfig(connectDelayMillis = 0, randomSplit = true))
        sim.start(SampleRate.HZ_100, MeasurementRange.K64)
        assertEquals(0, sim.read(ByteArray(0)))
        val n = sim.read(ByteArray(1))
        assertEquals(1, n)
    }
}

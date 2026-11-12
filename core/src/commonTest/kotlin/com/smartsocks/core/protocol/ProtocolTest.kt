package com.smartsocks.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolTest {
    @Test
    fun `commands have legacy wire format`() {
        assertEquals("BT^START\r", SockCommand.Start.wire)
        assertEquals("BT^STOP\r", SockCommand.Stop.wire)
        assertEquals("BT^TRES\r", SockCommand.ResetTimestamp.wire)
        assertEquals("BTS1=4\r", SockCommand.SetSampleRate(SampleRate.HZ_100).wire)
        assertEquals("BTS2=9\r", SockCommand.SetRange(MeasurementRange.K1024).wire)
    }

    @Test
    fun `every command parses back to itself`() {
        val all = listOf(SockCommand.Start, SockCommand.Stop, SockCommand.ResetTimestamp) +
            SampleRate.entries.map(SockCommand::SetSampleRate) +
            MeasurementRange.entries.map(SockCommand::SetRange)
        for (command in all) assertEquals(command, SockCommand.parse(command.wire.removeSuffix("\r")))
    }

    @Test
    fun `unknown commands parse to null`() {
        assertNull(SockCommand.parse("BTS1=9"))
        assertNull(SockCommand.parse("BTS2=x"))
        assertNull(SockCommand.parse("HELLO"))
    }

    @Test
    fun `sample rate codes match legacy formula`() {
        assertEquals(10, SampleRate.HZ_10.hz)
        for (rate in SampleRate.entries.drop(1)) assertEquals(25 * rate.code, rate.hz)
    }

    @Test
    fun `range multiplier matches legacy table`() {
        val legacy = doubleArrayOf(0.03125, 0.0625, 0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0)
        MeasurementRange.entries.forEachIndexed { i, range -> assertEquals(legacy[i], range.ohmsPerCount) }
        assertEquals(64_000.0, MeasurementRange.K64.fullScaleOhms)
        assertEquals("1024 kΩ", MeasurementRange.K1024.label)
    }

    @Test
    fun `full scale count is an open circuit`() {
        assertEquals(Double.POSITIVE_INFINITY, MeasurementRange.K64.countsToOhms(PacketFormat.MAX_COUNT))
        assertEquals(65_534.0, MeasurementRange.K64.countsToOhms(PacketFormat.MAX_COUNT - 1))
    }

    @Test
    fun `ohms to counts saturates at 16 bits`() {
        assertEquals(PacketFormat.MAX_COUNT, MeasurementRange.K2.ohmsToCounts(1e9))
        assertEquals(0, MeasurementRange.K2.ohmsToCounts(-5.0))
        assertEquals(1000, MeasurementRange.K64.ohmsToCounts(1000.0))
    }

    @Test
    fun `start sequence configures before starting`() {
        val seq = SockCommand.startSequence(SampleRate.HZ_50, MeasurementRange.K16)
        assertEquals(SockCommand.Stop, seq.first())
        assertEquals(SockCommand.Start, seq.last())
        assertEquals(
            listOf(SockCommand.SetRange(MeasurementRange.K16), SockCommand.SetSampleRate(SampleRate.HZ_50)),
            seq.filter { it is SockCommand.SetRange || it is SockCommand.SetSampleRate },
        )
    }
}

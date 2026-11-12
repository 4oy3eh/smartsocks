package com.smartsocks.core.protocol

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PacketDecoderTest {
    private val decoded = mutableListOf<IntArray>()
    private val decoder = PacketDecoder { p -> decoded += IntArray(PacketFormat.CHANNELS) { p.count(it) } }

    private fun packet(vararg counts: Int) = PacketEncoder.encode(counts)

    @Test
    fun `decodes little endian counts at legacy offsets`() {
        val bytes = packet(0x1234, 1, 65535, 0, 300, 7)
        assertEquals(0x34.toByte(), bytes[29])
        assertEquals(0x12.toByte(), bytes[30])

        decoder.feed(bytes)

        assertEquals(1, decoded.size)
        assertContentEquals(intArrayOf(0x1234, 1, 65535, 0, 300, 7, 0, 0), decoded[0])
    }

    @Test
    fun `reassembles a packet split at every possible point`() {
        val bytes = packet(100, 200, 300, 400, 500, 600)
        for (split in 1 until bytes.size) {
            decoded.clear()
            decoder.reset()
            decoder.feed(bytes, 0, split)
            decoder.feed(bytes, split, bytes.size - split)
            assertEquals(1, decoded.size, "split at $split")
            assertEquals(600, decoded[0][5])
        }
    }

    @Test
    fun `decodes several packets from one chunk`() {
        val stream = packet(1) + packet(2) + packet(3)
        decoder.feed(stream)
        assertEquals(listOf(1, 2, 3), decoded.map { it[0] })
    }

    @Test
    fun `skips leading garbage`() {
        decoder.feed(byteArrayOf(1, 2, 3, 0x55, 9) + packet(42))
        assertEquals(listOf(42), decoded.map { it[0] })
        assertEquals(5, decoder.droppedBytes)
    }

    @Test
    fun `recovers after a truncated packet`() {
        val truncated = packet(1).copyOf(30)
        decoder.feed(truncated + packet(2) + packet(3))
        // The resync finds packet 2's start byte inside the bad frame, so nothing after the truncation is lost.
        assertEquals(listOf(2, 3), decoded.map { it[0] })
        assertEquals(30, decoder.droppedBytes)
    }

    @Test
    fun `payload bytes equal to start or end markers do not break framing`() {
        val tricky = 0xF055 // low byte 0x55 (END), high byte 0xF0 (START)
        val stream = packet(tricky, tricky, tricky, tricky, tricky, tricky) + packet(0x55F0)
        decoder.feed(stream)
        assertEquals(listOf(tricky, 0x55F0), decoded.map { it[0] })
    }

    @Test
    fun `random garbage between packets loses no clean packet`() {
        val random = Random(7)
        val expected = (1..200).toList()
        val stream = mutableListOf<Byte>()
        for (v in expected) {
            // Garbage without START bytes cannot fake a frame, so every packet must survive.
            repeat(random.nextInt(0, 5)) { stream += random.nextInt(0, 0xF0).toByte() }
            stream += packet(v).toList()
        }
        val bytes = stream.toByteArray()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(random.nextInt(1, 64), bytes.size - i)
            decoder.feed(bytes, i, n)
            i += n
        }
        assertEquals(expected, decoded.map { it[0] })
        assertEquals(expected.size.toLong(), decoder.packetCount)
    }
}

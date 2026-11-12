package com.smartsocks.core.protocol

/** One complete 47-byte packet. [bytes] is kept whole so unknown fields can be recorded and decoded later. */
class RawPacket(val bytes: ByteArray) {
    init {
        require(bytes.size == PacketFormat.SIZE) { "Packet must be ${PacketFormat.SIZE} bytes, was ${bytes.size}" }
    }

    /** Raw uint16 little-endian count of [channel] (0-based, 0..7). */
    fun count(channel: Int): Int {
        val i = PacketFormat.SENSOR_OFFSET + channel * 2
        return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
    }
}

/**
 * Streaming packet framer. Feed it arbitrary chunks as they come from the transport; it emits every complete
 * packet that starts with [PacketFormat.START] and ends with [PacketFormat.END] at the expected offset.
 *
 * On a bad end byte it drops the leading byte and resynchronises on the next start byte already buffered, so
 * garbage or a truncated packet costs at most one packet. Known limitation: if the bytes `55 F0` happen to appear
 * at a fixed offset in every packet, the decoder can lock onto that false boundary; only the (still unknown)
 * checksum in byte 45 could rule that out. Not thread-safe: use from a single reader coroutine.
 */
class PacketDecoder(private val onPacket: (RawPacket) -> Unit) {
    private val buffer = ByteArray(PacketFormat.SIZE)
    private var length = 0

    var packetCount: Long = 0
        private set

    /** Bytes discarded while searching for a packet boundary. */
    var droppedBytes: Long = 0
        private set

    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        for (i in offset until offset + count) push(data[i])
    }

    fun reset() {
        length = 0
    }

    private fun push(b: Byte) {
        if (length == 0) {
            if (b == PacketFormat.START) {
                buffer[0] = b
                length = 1
            } else {
                droppedBytes++
            }
            return
        }
        buffer[length++] = b
        if (length < PacketFormat.SIZE) return

        if (buffer[PacketFormat.SIZE - 1] == PacketFormat.END) {
            packetCount++
            length = 0
            onPacket(RawPacket(buffer.copyOf()))
        } else {
            resync()
        }
    }

    private fun resync() {
        var start = 1
        while (start < PacketFormat.SIZE && buffer[start] != PacketFormat.START) start++
        droppedBytes += start
        buffer.copyInto(buffer, destinationOffset = 0, startIndex = start, endIndex = PacketFormat.SIZE)
        length = PacketFormat.SIZE - start
    }
}

/** Builds packets in the device format. Used by the simulator and tests; unknown fields are zero. */
object PacketEncoder {
    fun encode(counts: IntArray): ByteArray {
        require(counts.size <= PacketFormat.CHANNELS) { "At most ${PacketFormat.CHANNELS} channels" }
        val bytes = ByteArray(PacketFormat.SIZE)
        bytes[0] = PacketFormat.START
        bytes[PacketFormat.SIZE - 1] = PacketFormat.END
        counts.forEachIndexed { channel, value ->
            require(value in 0..PacketFormat.MAX_COUNT) { "Count out of range: $value" }
            val i = PacketFormat.SENSOR_OFFSET + channel * 2
            bytes[i] = (value and 0xFF).toByte()
            bytes[i + 1] = (value ushr 8).toByte()
        }
        return bytes
    }
}

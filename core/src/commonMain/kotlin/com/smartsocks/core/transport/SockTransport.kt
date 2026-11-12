package com.smartsocks.core.transport

/**
 * A byte pipe to one sock. Real implementations wrap an Android RFCOMM socket or a desktop COM port; the simulator
 * implements it in memory. Everything above this interface (framing, analytics) is shared.
 */
interface SockTransport {
    val displayName: String

    /** Connects; throws on failure. */
    suspend fun open()

    /** Suspends until at least one byte is available. Returns the number of bytes read, or -1 once closed. */
    suspend fun read(buffer: ByteArray): Int

    suspend fun write(data: ByteArray)

    /** Idempotent. A pending [read] must return -1. */
    suspend fun close()
}

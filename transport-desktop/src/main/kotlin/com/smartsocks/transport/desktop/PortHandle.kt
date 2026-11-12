package com.smartsocks.transport.desktop

import java.io.IOException

/** The few serial-port operations the transport needs; the only place jSerialComm is touched is [JSerialPortHandle]. */
internal interface PortHandle {
    /** Throws [IOException] if the port cannot be opened. */
    fun open()

    /**
     * Blocks for a bounded time. Returns the number of bytes read (> 0), 0 if the wait timed out with no data,
     * or -1 if the port is closed or gone. May be called while [write] runs on another thread.
     */
    fun read(buffer: ByteArray): Int

    /** Writes all of [data]; throws [IOException] on failure. */
    fun write(data: ByteArray)

    fun bytesAvailable(): Int

    /** Idempotent; makes a pending [read] return -1 (or 0) promptly. */
    fun close()
}

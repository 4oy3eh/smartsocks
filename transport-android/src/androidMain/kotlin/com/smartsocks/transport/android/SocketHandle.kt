package com.smartsocks.transport.android

import java.io.IOException

/**
 * The few socket operations the transport needs; the only place `BluetoothSocket` is touched is
 * [BluetoothSocketHandle]. One handle is one connection attempt: it is never reconnected after [close].
 */
internal interface SocketHandle {
    /** Blocks until connected; throws [IOException] on failure. A concurrent [close] makes it fail promptly. */
    fun connect()

    /**
     * Blocks until data arrives. Returns the number of bytes read (> 0) or -1 at end of stream; throws
     * [IOException] when the link breaks or the handle is closed under it. May run while [write] runs on another
     * thread.
     */
    fun read(buffer: ByteArray): Int

    /** Writes all of [data]; throws [IOException] on failure. */
    fun write(data: ByteArray)

    /** Idempotent and callable from any thread; aborts a pending [connect] or [read]. */
    fun close()
}

/** One way of creating an RFCOMM socket to the device (secure SDP, insecure SDP, fixed channel...). */
internal class ConnectAttempt(val label: String, val create: () -> SocketHandle)

/** Resolves what to connect to; Android-specific in production, a fake in tests. */
internal fun interface RfcommEndpoint {
    /**
     * Called on the IO dispatcher at the start of every open: checks that Bluetooth is usable, cancels discovery
     * and returns the socket-creation strategies to try in order. Throws [IOException] or [SecurityException].
     */
    fun prepare(): List<ConnectAttempt>
}

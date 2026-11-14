package com.smartsocks.transport.desktop

import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [SockTransport] over a serial (COM) port. On Windows a paired Classic-BT SPP sock shows up as an outgoing COM
 * port. [read] uses semi-blocking reads with a short timeout and loops internally, so it returns only with data
 * or -1; the timeout also bounds how long a cancelled or closed read keeps its thread.
 *
 * [close] is idempotent. The transport can be opened again afterwards (that is how `SockSession` reconnects); every
 * open uses a new port handle from [newHandle].
 *
 * Opening a port may block for long (an SPP link to a sock out of range) and cannot be interrupted (jSerialComm's
 * open and close share a lock). So [open] runs it on its own and returns at once when cancelled, or when [close] is
 * called meanwhile; the port is then closed as soon as the blocked open returns.
 */
class SerialSockTransport internal constructor(
    private val portName: String,
    private val newHandle: () -> PortHandle,
    private val io: CoroutineDispatcher,
) : SockTransport {
    constructor(
        portName: String,
        baudRate: Int = DEFAULT_BAUD_RATE,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    ) : this(portName, { JSerialPortHandle(portName, baudRate, readTimeoutMillis) }, Dispatchers.IO)

    override val displayName: String get() = portName

    /**
     * One open/close cycle. [close] marks it closed and closes the handle, unless its open is still under way: then
     * [openReturned] closes it. (Both may close it in a race; [PortHandle.close] is idempotent.)
     */
    private class Connection(val handle: PortHandle) {
        val closed = AtomicBoolean(false)

        /** Completed by [close]. */
        val closedSignal = CompletableDeferred<Unit>()

        @Volatile
        private var opening = true

        fun close(): Boolean {
            if (!closed.compareAndSet(false, true)) return false
            closedSignal.complete(Unit)
            if (!opening) handle.close()
            return true
        }

        /** [PortHandle.open] has returned or thrown. */
        fun openReturned() {
            opening = false
            if (closed.get()) handle.close()
        }
    }

    private val connection = AtomicReference<Connection?>(null)
    private val writeLock = Mutex()

    /** @throws IllegalStateException if already open. */
    override suspend fun open() {
        val conn = Connection(newHandle())
        val previous = connection.get()
        check(previous == null || previous.closed.get()) { "Transport for '$portName' is already open" }
        check(connection.compareAndSet(previous, conn)) { "Transport for '$portName' is already open" }
        // Not a child of the caller: a cancelled caller must not wait for an open that cannot be interrupted.
        val opened = CompletableDeferred<Unit>()
        CoroutineScope(io).launch {
            try {
                conn.handle.open()
                opened.complete(Unit)
            } catch (e: Throwable) {
                opened.completeExceptionally(e)
            } finally {
                conn.openReturned()
            }
        }
        try {
            // Also given up on by close() while the open blocks.
            val done = select {
                opened.onAwait { true }
                conn.closedSignal.onAwait { false }
            }
            if (!done) throw IOException("Serial port '$portName' was closed while opening")
        } catch (e: Throwable) {
            withContext(NonCancellable + io) { conn.close() }
            if (e is IOException || e is CancellationException) throw e
            throw IOException("Cannot open serial port '$portName': ${e.message}", e)
        }
        if (conn.closed.get()) throw IOException("Serial port '$portName' was closed while opening")
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        val conn = connection.get() ?: return -1
        return withContext(io) { readBlocking(conn, buffer) }
    }

    private fun readBlocking(conn: Connection, buffer: ByteArray): Int {
        while (true) {
            if (conn.closed.get()) return -1
            val n = try {
                conn.handle.read(buffer)
            } catch (_: Exception) {
                // Closing under a pending read, or the port vanishing, surfaces as an error: end of stream.
                conn.close()
                return -1
            }
            if (n > 0) return n
            if (n < 0) {
                conn.close()
                return -1
            }
            // n == 0: read timed out. The handle blocks for its timeout, so this loop does not spin.
        }
    }

    override suspend fun write(data: ByteArray) {
        val conn = connection.get()
        if (conn == null || conn.closed.get()) throw IOException("Serial port '$portName' is closed")
        // Writers are serialised among themselves only; a pending read never holds this lock.
        writeLock.withLock { withContext(io) { conn.handle.write(data) } }
    }

    override suspend fun close() {
        val conn = connection.get() ?: return
        if (!conn.closed.get()) withContext(io) { conn.close() }
    }

    companion object {
        const val DEFAULT_BAUD_RATE = 115200
        const val DEFAULT_READ_TIMEOUT_MILLIS = 200
    }
}

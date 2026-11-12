package com.smartsocks.transport.android

import android.content.Context
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * [SockTransport] over a Classic Bluetooth SPP (RFCOMM) socket to a bonded sock.
 *
 * - [open] connects on the IO dispatcher, trying in order: secure SDP lookup of the SPP UUID, insecure SDP lookup,
 *   then insecure RFCOMM channel 1 (what the legacy app used; cheap SPP modules often have a broken SDP record).
 *   Discovery is cancelled first when the app may do so. Cancelling the calling coroutine, or [close] from another
 *   one, closes the socket and so aborts a hanging connect.
 * - [read] blocks on the IO dispatcher and returns -1 at end of stream or once closed. A link that breaks while
 *   open throws [IOException] once (with the cause); later reads return -1. A blocked read ignores cancellation:
 *   use [close] to wake it.
 * - [write] may run while a read is pending. Writers are serialised among themselves. Cancelling a write that is
 *   stuck (flow control) closes the connection.
 * - [close] is idempotent. The transport can be opened again afterwards; every open uses a new socket.
 *
 * Failures of [open]: [IOException] (no adapter, Bluetooth off, device unreachable, closed while connecting),
 * [SecurityException] (permission missing, see [BluetoothPermissions]), [IllegalStateException] (already open).
 */
class RfcommSockTransport internal constructor(
    override val displayName: String,
    private val endpoint: RfcommEndpoint,
    private val io: CoroutineDispatcher,
) : SockTransport {
    /** [address] is the device MAC (`00:11:22:AA:BB:CC`); nothing is checked until [open]. */
    constructor(
        context: Context,
        address: String,
        displayName: String = address,
    ) : this(displayName, AndroidRfcommEndpoint(context.applicationContext, address), Dispatchers.IO)

    constructor(context: Context, device: BluetoothDeviceInfo) :
        this(context, device.address, device.name ?: device.address)

    private val lock = Any()
    private var connection: Connection? = null // guarded by lock
    private val writeLock = Mutex()

    /**
     * Which connect attempt succeeded on the last [open] ("secure SPP", "insecure SPP", "insecure channel 1"), or null
     * before the first successful open. For diagnostics: which one the real sock needs is not known yet.
     */
    @Volatile
    var connectedVia: String? = null
        private set

    override suspend fun open() {
        val conn = Connection()
        synchronized(lock) {
            check(connection?.isClosed != false) { "Transport for '$displayName' is already open" }
            connection = conn
        }
        try {
            withContext(io) { connect(conn) }
        } catch (e: Throwable) {
            // Socket close may block briefly; keep it off the caller's (possibly main) thread, even when cancelled.
            withContext(NonCancellable + io) { conn.close() }
            throw e
        }
    }

    private suspend fun connect(conn: Connection) {
        val attempts = endpoint.prepare()
        if (attempts.isEmpty()) throw IOException("No way to connect to '$displayName'")
        val failures = ArrayList<Exception>(attempts.size)
        for (attempt in attempts) {
            currentCoroutineContext().ensureActive()
            if (conn.isClosed) throw closedWhileConnecting()
            val handle = try {
                attempt.create()
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                failures += IOException("${attempt.label}: ${e.message}", e)
                continue
            }
            if (!conn.attach(handle)) {
                handle.close()
                throw closedWhileConnecting()
            }
            val error = runBlockingIo(onCancel = handle::close) { handle.connect() }
            if (error == null) {
                if (!conn.markConnected()) throw closedWhileConnecting()
                connectedVia = attempt.label
                return
            }
            conn.detach(handle)
            handle.close()
            if (conn.isClosed) throw closedWhileConnecting()
            if (error is SecurityException) throw error
            failures += IOException("${attempt.label}: ${error.message}", error)
        }
        throw IOException(
            "Cannot connect to '$displayName' (paired, switched on and in range?): " +
                failures.joinToString("; ") { it.message.orEmpty() },
        ).apply { failures.forEach(::addSuppressed) }
    }

    /**
     * Runs a blocking socket call (connect or write, which ignore cancellation) on an IO thread while this coroutine
     * stays responsive: on cancellation or timeout [onCancel] closes the socket, which makes the call fail and
     * frees that thread. The call runs outside structured concurrency on purpose, so that the caller returns at once
     * even if the stack is slow to fail it. Returns the call's failure, or null on success.
     */
    private suspend fun runBlockingIo(onCancel: () -> Unit, block: () -> Unit): Exception? {
        val call = CoroutineScope(io).async {
            try {
                block()
                null
            } catch (e: Exception) {
                e
            }
        }
        return try {
            call.await()
        } catch (e: CancellationException) {
            onCancel()
            throw e
        }
    }

    private fun closedWhileConnecting() = IOException("Connection to '$displayName' was closed while connecting")

    private fun current(): Connection? = synchronized(lock) { connection }

    override suspend fun read(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        val conn = current() ?: return -1
        val handle = conn.connectedHandle() ?: return -1
        return withContext(io) { readBlocking(conn, handle, buffer) }
    }

    private fun readBlocking(conn: Connection, handle: SocketHandle, buffer: ByteArray): Int {
        while (true) {
            val n = try {
                handle.read(buffer)
            } catch (e: Exception) {
                // Closing under a pending read surfaces as an IOException: that is the normal end of stream.
                if (!conn.close()) return -1
                throw IOException("Bluetooth link to '$displayName' lost: ${e.message}", e)
            }
            if (n > 0) return n
            if (n < 0) {
                conn.close()
                return -1
            }
            // n == 0 does not happen with a Bluetooth stream and a non-empty buffer; just read again.
        }
    }

    override suspend fun write(data: ByteArray) {
        val conn = current()
        val handle = conn?.connectedHandle() ?: throw IOException("Bluetooth link to '$displayName' is not open")
        // A pending read never holds this lock, so STOP can still be sent while the reader blocks. A write stuck on
        // RFCOMM flow control is aborted by cancellation (e.g. the session's STOP timeout): a link that cannot take a
        // few bytes in that time is dead, so the whole connection is closed and a pending read returns -1.
        writeLock.withLock {
            val error = runBlockingIo(onCancel = { conn.close() }) { handle.write(data) } ?: return
            if (conn.isClosed) throw IOException("Bluetooth link to '$displayName' is closed", error)
            throw error
        }
    }

    override suspend fun close() {
        val conn = current() ?: return
        if (!conn.isClosed) withContext(io) { conn.close() }
    }

    /** One open/close cycle. Thread-safe; [close] closes the socket exactly once. */
    private class Connection {
        private var handle: SocketHandle? = null
        private var connected = false
        private var closed = false

        val isClosed: Boolean get() = synchronized(this) { closed }

        fun attach(h: SocketHandle): Boolean = synchronized(this) {
            if (closed) return false
            handle = h
            true
        }

        fun detach(h: SocketHandle) = synchronized(this) {
            if (handle === h) handle = null
        }

        fun markConnected(): Boolean = synchronized(this) {
            if (closed) return false
            connected = true
            true
        }

        fun connectedHandle(): SocketHandle? = synchronized(this) { if (connected && !closed) handle else null }

        /** Returns true if this call closed it. */
        fun close(): Boolean {
            val h = synchronized(this) {
                if (closed) return false
                closed = true
                handle
            }
            h?.close()
            return true
        }
    }

    companion object {
        /** Serial Port Profile service UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** RFCOMM channel of the last-resort attempt; the legacy app connected this way. */
        const val FALLBACK_RFCOMM_CHANNEL = 1
    }
}

package com.smartsocks.transport.desktop

import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [SockTransport] over a serial (COM) port. On Windows a paired Classic-BT SPP sock shows up as an outgoing COM
 * port. [read] uses semi-blocking reads with a short timeout and loops internally, so it returns only with data
 * or -1; the timeout also bounds how long a cancelled or closed read keeps its thread.
 */
class SerialSockTransport internal constructor(
    private val portName: String,
    private val handle: PortHandle,
    private val io: CoroutineDispatcher,
) : SockTransport {
    constructor(
        portName: String,
        baudRate: Int = DEFAULT_BAUD_RATE,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    ) : this(portName, JSerialPortHandle(portName, baudRate, readTimeoutMillis), Dispatchers.IO)

    override val displayName: String get() = portName

    private val closed = AtomicBoolean(false)
    private val writeLock = Mutex()

    override suspend fun open() {
        check(!closed.get()) { "Transport for '$portName' is closed" }
        withContext(io) {
            try {
                handle.open()
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Cannot open serial port '$portName': ${e.message}", e)
            }
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        return withContext(io) { readBlocking(buffer) }
    }

    private fun readBlocking(buffer: ByteArray): Int {
        while (true) {
            if (closed.get()) return -1
            val n = try {
                handle.read(buffer)
            } catch (_: Exception) {
                // Closing under a pending read, or the port vanishing, surfaces as an error: end of stream.
                closed.set(true)
                return -1
            }
            if (n > 0) return n
            if (n < 0) {
                closed.set(true)
                return -1
            }
            // n == 0: read timed out. The handle blocks for its timeout, so this loop does not spin.
        }
    }

    override suspend fun write(data: ByteArray) {
        if (closed.get()) throw IOException("Serial port '$portName' is closed")
        // Writers are serialised among themselves only; a pending read never holds this lock.
        writeLock.withLock { withContext(io) { handle.write(data) } }
    }

    override suspend fun close() {
        if (closed.compareAndSet(false, true)) {
            withContext(io) { handle.close() }
        }
    }

    companion object {
        const val DEFAULT_BAUD_RATE = 115200
        const val DEFAULT_READ_TIMEOUT_MILLIS = 200
    }
}

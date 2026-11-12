package com.smartsocks.transport.android

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.session.ConnectionState
import com.smartsocks.core.session.SockSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Behaves like a `BluetoothSocket`: close() aborts a pending connect or read with an IOException. */
private class FakeSocket(
    private val connectError: Exception? = null,
    private val blockConnect: Boolean = false,
    /** Simulates RFCOMM flow control: write blocks until the socket is closed. */
    private val blockWrite: Boolean = false,
) : SocketHandle {
    /** Byte, [EOF] or an IOException to throw from read. */
    val incoming = LinkedBlockingQueue<Any>()
    val written = LinkedBlockingQueue<ByteArray>()
    val connectStarted = CountDownLatch(1)
    val writeStarted = CountDownLatch(1)
    val closeCalls = AtomicInteger()
    private val closedLatch = CountDownLatch(1)
    val isClosed: Boolean get() = closedLatch.count == 0L

    fun feed(vararg bytes: Int) = bytes.forEach { incoming.add(it.toByte()) }

    override fun connect() {
        connectStarted.countDown()
        connectError?.let { throw it }
        if (blockConnect) {
            closedLatch.await()
            throw IOException("socket closed")
        }
        if (isClosed) throw IOException("socket closed")
    }

    override fun read(buffer: ByteArray): Int {
        while (true) {
            if (isClosed) throw IOException("socket closed")
            val item = incoming.poll(10, TimeUnit.MILLISECONDS) ?: continue
            when (item) {
                EOF -> return -1
                is IOException -> throw item
            }
            buffer[0] = item as Byte
            var n = 1
            while (n < buffer.size) {
                val next = incoming.peek() as? Byte ?: break
                incoming.poll()
                buffer[n++] = next
            }
            return n
        }
    }

    override fun write(data: ByteArray) {
        if (isClosed) throw IOException("socket closed")
        if (blockWrite) {
            writeStarted.countDown()
            closedLatch.await()
            throw IOException("socket closed")
        }
        written.add(data.copyOf())
    }

    override fun close() {
        closeCalls.incrementAndGet()
        closedLatch.countDown()
    }

    companion object {
        val EOF = Any()
    }
}

/** Hands out sockets from [plan] (one list per open) and records what was created. */
private class FakeEndpoint(private val plan: List<List<() -> SocketHandle>>) : RfcommEndpoint {
    val created = CopyOnWriteArrayList<SocketHandle>()
    val prepareCalls = AtomicInteger()
    var prepareError: Exception? = null

    /** When set, prepare() signals [prepareEntered] and waits for this gate. */
    var prepareGate: CountDownLatch? = null
    val prepareEntered = CountDownLatch(1)

    override fun prepare(): List<ConnectAttempt> {
        prepareEntered.countDown()
        prepareGate?.await(5, TimeUnit.SECONDS)
        prepareError?.let { throw it }
        val round = plan[minOf(prepareCalls.getAndIncrement(), plan.lastIndex)]
        return round.mapIndexed { i, make -> ConnectAttempt("attempt$i") { make().also { created += it } } }
    }

    companion object {
        fun of(vararg sockets: FakeSocket) = FakeEndpoint(sockets.map { s -> listOf({ s }) })
    }
}

class RfcommSockTransportTest {
    private fun transport(endpoint: RfcommEndpoint, io: CoroutineDispatcher = Dispatchers.IO) =
        RfcommSockTransport("Block 3", endpoint, io)

    private fun TestScope.testIo() = StandardTestDispatcher(testScheduler)

    @Test
    fun readAndWritePassThrough() = runTest {
        val s = FakeSocket()
        val t = transport(FakeEndpoint.of(s), testIo())
        t.open()
        s.feed(1, 2, 3)
        val buf = ByteArray(16)
        assertEquals(3, t.read(buf))
        assertContentEquals(byteArrayOf(1, 2, 3), buf.copyOf(3))
        t.write(byteArrayOf(9, 8))
        assertContentEquals(byteArrayOf(9, 8), s.written.poll())
        t.close()
        assertEquals(-1, t.read(buf))
    }

    @Test
    fun endOfStreamReturnsMinusOneAndClosesSocket() = runTest {
        val s = FakeSocket()
        val t = transport(FakeEndpoint.of(s), testIo())
        t.open()
        s.incoming.add(FakeSocket.EOF)
        assertEquals(-1, t.read(ByteArray(8)))
        assertTrue(s.isClosed)
        assertEquals(-1, t.read(ByteArray(8)))
    }

    @Test
    fun linkLossThrowsOnceThenMinusOne() = runTest {
        val s = FakeSocket()
        val t = transport(FakeEndpoint.of(s), testIo())
        t.open()
        s.incoming.add(IOException("bt socket closed, read return: -1"))
        val e = assertFailsWith<IOException> { t.read(ByteArray(8)) }
        assertTrue(e.message!!.contains("Block 3"))
        assertTrue(s.isClosed)
        assertEquals(-1, t.read(ByteArray(8)))
        assertFailsWith<IOException> { t.write(byteArrayOf(1)) }
    }

    @Test
    fun closeWakesBlockedRead() = runBlocking {
        val s = FakeSocket()
        val t = transport(FakeEndpoint.of(s))
        t.open()
        val pending = async(Dispatchers.Default) { t.read(ByteArray(8)) }
        Thread.sleep(100)
        assertTrue(pending.isActive)
        t.close()
        assertEquals(-1, withTimeout(2000) { pending.await() })
    }

    @Test
    fun closeIsIdempotent() = runTest {
        val s = FakeSocket()
        val t = transport(FakeEndpoint.of(s), testIo())
        t.close() // before open: no-op
        t.open()
        t.close()
        t.close()
        assertEquals(1, s.closeCalls.get())
    }

    @Test
    fun writeWorksWhileReadPending() = runBlocking {
        val s = FakeSocket()
        val t = transport(FakeEndpoint.of(s))
        t.open()
        val pending = async(Dispatchers.Default) { t.read(ByteArray(8)) }
        Thread.sleep(100)
        withTimeout(2000) { t.write(byteArrayOf(7, 6)) }
        assertContentEquals(byteArrayOf(7, 6), s.written.poll(2, TimeUnit.SECONDS))
        assertTrue(pending.isActive)
        t.close()
        assertEquals(-1, withTimeout(2000) { pending.await() })
    }

    @Test
    fun writeWhenNotOpenFails() = runTest {
        val t = transport(FakeEndpoint.of(FakeSocket()), testIo())
        assertFailsWith<IOException> { t.write(byteArrayOf(1)) }
        t.open()
        t.close()
        assertFailsWith<IOException> { t.write(byteArrayOf(1)) }
        assertEquals(-1, t.read(ByteArray(4)))
    }

    @Test
    fun reopenUsesNewSocket() = runTest {
        val first = FakeSocket()
        val second = FakeSocket()
        val endpoint = FakeEndpoint.of(first, second)
        val t = transport(endpoint, testIo())
        t.open()
        t.close()
        t.open()
        assertTrue(first.isClosed)
        assertFalse(second.isClosed)
        second.feed(42)
        val buf = ByteArray(4)
        assertEquals(1, t.read(buf))
        assertEquals(42, buf[0].toInt())
        assertEquals(2, endpoint.prepareCalls.get())
        t.close()
    }

    @Test
    fun openTwiceWithoutCloseFails() = runTest {
        val t = transport(FakeEndpoint.of(FakeSocket(), FakeSocket()), testIo())
        t.open()
        assertFailsWith<IllegalStateException> { t.open() }
    }

    @Test
    fun fallsBackToNextAttemptWhenConnectFails() = runTest {
        val secure = FakeSocket(connectError = IOException("read failed, socket might closed or timeout"))
        val insecure = FakeSocket()
        val endpoint = FakeEndpoint(listOf(listOf({ secure }, { insecure }, { error("must not be reached") })))
        val t = transport(endpoint, testIo())
        assertNull(t.connectedVia)
        t.open()
        assertEquals("attempt1", t.connectedVia)
        assertEquals(listOf<SocketHandle>(secure, insecure), endpoint.created.toList())
        assertTrue(secure.isClosed)
        insecure.feed(5)
        assertEquals(1, t.read(ByteArray(4)))
    }

    @Test
    fun fallsBackWhenSocketCannotBeCreated() = runTest {
        val socket = FakeSocket()
        val endpoint = FakeEndpoint(listOf(listOf({ throw IOException("no SDP") }, { socket })))
        val t = transport(endpoint, testIo())
        t.open()
        assertSame(socket, endpoint.created.single())
    }

    @Test
    fun openFailsWhenAllAttemptsFail() = runTest {
        val a = FakeSocket(connectError = IOException("timeout"))
        val b = FakeSocket(connectError = IOException("refused"))
        val good = FakeSocket()
        val endpoint = FakeEndpoint(listOf(listOf({ a }, { b }), listOf({ good })))
        val t = transport(endpoint, testIo())
        val e = assertFailsWith<IOException> { t.open() }
        val message = e.message!!
        assertTrue("Block 3" in message && "attempt0: timeout" in message && "attempt1: refused" in message, message)
        // Coroutine stack-trace recovery (debug mode in tests) may wrap the original; it is then the cause.
        assertEquals(2, generateSequence<Throwable>(e) { it.cause }.maxOf { it.suppressed.size })
        assertTrue(a.isClosed && b.isClosed)
        // The failed open left nothing behind: the next one connects normally.
        t.open()
        good.feed(1)
        assertEquals(1, t.read(ByteArray(4)))
    }

    @Test
    fun securityExceptionIsNotSwallowedByFallback() = runTest {
        val endpoint = FakeEndpoint(
            listOf(
                listOf({
                    FakeSocket(connectError = SecurityException("Need BLUETOOTH_CONNECT"))
                }, { FakeSocket() }),
            ),
        )
        assertFailsWith<SecurityException> { transport(endpoint, testIo()).open() }
        assertEquals(1, endpoint.created.size)

        val denied = FakeEndpoint.of(FakeSocket()).apply { prepareError = SecurityException("no permission") }
        assertFailsWith<SecurityException> { transport(denied, testIo()).open() }
    }

    @Test
    fun cancellingOpenClosesHangingSocket() = runBlocking {
        val hanging = FakeSocket(blockConnect = true)
        val next = FakeSocket()
        val t = transport(FakeEndpoint.of(hanging, next))
        val opening = async(Dispatchers.Default) { t.open() }
        assertTrue(hanging.connectStarted.await(2, TimeUnit.SECONDS))
        opening.cancel()
        withTimeout(2000) { opening.join() }
        assertTrue(opening.isCancelled)
        assertTrue(hanging.isClosed)
        // Not left half-open: a new open is allowed and uses a new socket.
        withTimeout(2000) { t.open() }
        next.feed(3)
        assertEquals(1, withTimeout(2000) { t.read(ByteArray(4)) })
        t.close()
    }

    @Test
    fun closeDuringConnectAbortsOpen() = runBlocking {
        val hanging = FakeSocket(blockConnect = true)
        val t = transport(FakeEndpoint(listOf(listOf({ hanging }, { error("no fallback after close") }))))
        val opening = async(Dispatchers.Default) { runCatching { t.open() } }
        assertTrue(hanging.connectStarted.await(2, TimeUnit.SECONDS))
        t.close()
        val result = withTimeout(2000) { opening.await() }
        val e = result.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("closed while connecting"), "got $e")
        assertTrue(hanging.isClosed)
        assertEquals(-1, t.read(ByteArray(4)))
    }

    @Test
    fun securityExceptionFromSocketCreationSkipsFallbacks() = runTest {
        // On Android 12+ without BLUETOOTH_CONNECT, createRfcommSocketToServiceRecord itself throws.
        val fallbackTried = AtomicBoolean(false)
        val endpoint = FakeEndpoint(
            listOf(
                listOf(
                    { throw SecurityException("Need android.permission.BLUETOOTH_CONNECT") },
                    { FakeSocket().also { fallbackTried.set(true) } },
                ),
            ),
        )
        val t = transport(endpoint, testIo())
        assertFailsWith<SecurityException> { t.open() }
        assertFalse(fallbackTried.get())
        assertTrue(endpoint.created.isEmpty())
        assertEquals(-1, t.read(ByteArray(4)))
    }

    @Test
    fun closeDuringPrepareAbortsOpen() = runBlocking {
        val socketCreated = AtomicBoolean(false)
        val gate = CountDownLatch(1)
        val endpoint = FakeEndpoint(listOf(listOf({ FakeSocket().also { socketCreated.set(true) } }))).apply {
            prepareGate = gate
        }
        val t = transport(endpoint)
        val opening = async(Dispatchers.Default) { runCatching { t.open() } }
        assertTrue(endpoint.prepareEntered.await(2, TimeUnit.SECONDS))
        withTimeout(2000) { t.close() }
        gate.countDown()
        val e = withTimeout(2000) { opening.await() }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("closed while connecting"), "got $e")
        assertFalse(socketCreated.get())
    }

    @Test
    fun cancelledStuckWriteClosesConnection() = runBlocking {
        val s = FakeSocket(blockWrite = true)
        val t = transport(FakeEndpoint.of(s))
        t.open()
        val pending = async(Dispatchers.Default) { t.read(ByteArray(8)) }
        val startNanos = System.nanoTime()
        val result = withTimeoutOrNull(200) { t.write("BT^STOP\r".encodeToByteArray()) }
        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
        assertNull(result)
        assertTrue(elapsedMillis < 1500, "write took $elapsedMillis ms after its timeout")
        assertTrue(s.writeStarted.count == 0L)
        assertTrue(s.isClosed)
        assertEquals(-1, withTimeout(2000) { pending.await() })
        assertEquals(-1, t.read(ByteArray(8)))
        assertFailsWith<IOException> { t.write(byteArrayOf(1)) }
        Unit
    }

    @Test
    fun sessionCancelSendsStopWhileReadBlocks() = runBlocking {
        val s = FakeSocket()
        val t = transport(FakeEndpoint.of(s))
        val session = SockSession(Foot.LEFT, t, commandGapMillis = 0)
        val running = launch(Dispatchers.Default) { session.run() }
        withTimeout(5000) { session.connection.first { it == ConnectionState.Streaming } }
        Thread.sleep(100) // the session is now blocked in read(): the fake sends nothing
        running.cancel()
        withTimeout(5000) { running.join() }

        val wire = s.written.map { it.decodeToString() }
        val stop = SockCommand.Stop.encode().decodeToString()
        val start = SockCommand.Start.encode().decodeToString()
        val lastStart = wire.lastIndexOf(start)
        assertTrue(lastStart >= 0 && stop in wire.drop(lastStart + 1), "no STOP after START in $wire")
        assertTrue(s.isClosed)
        assertEquals(-1, t.read(ByteArray(8)))
        assertTrue(session.connection.value is ConnectionState.Disconnected, "${session.connection.value}")
    }
}

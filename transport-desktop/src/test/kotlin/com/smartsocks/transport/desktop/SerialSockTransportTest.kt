package com.smartsocks.transport.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeHandle(private val openError: Exception? = null) : PortHandle {
    val incoming = LinkedBlockingQueue<Byte>()
    val written = LinkedBlockingQueue<ByteArray>()
    private val closed = AtomicBoolean(false)
    val closeCalls = AtomicInteger()

    override fun open() {
        openError?.let { throw it }
    }

    override fun read(buffer: ByteArray): Int {
        if (closed.get()) return -1
        val first = incoming.poll(20, TimeUnit.MILLISECONDS) ?: return if (closed.get()) -1 else 0
        buffer[0] = first
        var n = 1
        while (n < buffer.size) buffer[n++] = incoming.poll() ?: return n - 1
        return n
    }

    override fun write(data: ByteArray) {
        written.add(data)
    }

    override fun bytesAvailable(): Int = incoming.size

    override fun close() {
        closeCalls.incrementAndGet()
        closed.set(true)
    }
}

class SerialSockTransportTest {
    private fun transport(handle: FakeHandle) = SerialSockTransport("COM9", handle, Dispatchers.IO)

    @Test
    fun readReturnsDataThenMinusOneAfterClose() = runBlocking {
        val h = FakeHandle()
        val t = transport(h)
        t.open()
        h.incoming.addAll(listOf<Byte>(1, 2, 3))
        val buf = ByteArray(16)
        assertEquals(3, withTimeout(2000) { t.read(buf) })
        t.close()
        assertEquals(-1, t.read(buf))
    }

    @Test
    fun closeUnblocksPendingRead() = runBlocking {
        val t = transport(FakeHandle())
        t.open()
        val pending = async(Dispatchers.Default) { t.read(ByteArray(8)) }
        Thread.sleep(100)
        assertTrue(pending.isActive)
        t.close()
        assertEquals(-1, withTimeout(2000) { pending.await() })
    }

    @Test
    fun closeIsIdempotent() = runBlocking {
        val h = FakeHandle()
        val t = transport(h)
        t.open()
        t.close()
        t.close()
        assertEquals(1, h.closeCalls.get())
    }

    @Test
    fun writeWorksWhileReadPending() = runBlocking {
        val h = FakeHandle()
        val t = transport(h)
        t.open()
        val pending = async(Dispatchers.Default) { t.read(ByteArray(8)) }
        Thread.sleep(100)
        withTimeout(2000) { t.write(byteArrayOf(9, 8)) }
        assertContentEquals(byteArrayOf(9, 8), h.written.poll())
        assertTrue(pending.isActive)
        t.close()
        assertEquals(-1, withTimeout(2000) { pending.await() })
    }

    @Test
    fun writeAfterCloseFails() = runBlocking {
        val t = transport(FakeHandle())
        t.open()
        t.close()
        assertFailsWith<IOException> { t.write(byteArrayOf(1)) }
        Unit
    }

    @Test
    fun openFailureHasClearMessage() = runBlocking {
        val e = assertFailsWith<IOException> {
            transport(FakeHandle(IOException("Cannot open serial port 'COM9' (busy)"))).open()
        }
        assertTrue(e.message!!.contains("COM9"))
        val e2 = assertFailsWith<IOException> { transport(FakeHandle(IllegalStateException("boom"))).open() }
        assertTrue(e2.message!!.contains("COM9") && e2.message!!.contains("boom"))
    }

    @Test
    fun listDoesNotCrash() {
        SerialPorts.list()
    }

    @Test
    fun bluetoothDetection() {
        assertTrue(SerialPorts.looksLikeBluetooth("Standard Serial over Bluetooth link (COM5)"))
        assertFalse(SerialPorts.looksLikeBluetooth("USB Serial Port", null))
    }
}

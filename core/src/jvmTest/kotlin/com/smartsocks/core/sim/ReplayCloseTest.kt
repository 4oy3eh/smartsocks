package com.smartsocks.core.sim

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketEncoder
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.recording.LineSource
import com.smartsocks.core.session.StreamSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real threads: a close while a burst is stuck reading the dump must still release the dump. */
class ReplayCloseTest {
    @Test
    fun `a close during a burst blocked on the dump releases it once the burst is done`() = runBlocking {
        val hex = PacketEncoder.encode(IntArray(6) { 100 }).joinToString("") { "%02x".format(it) }
        val head = listOf("# smartsocks-raw=2", "t_ms,host_ms,foot,hex")
        val opens = AtomicInteger()
        val closes = AtomicInteger()
        val stuck = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dump = {
            val index = opens.incrementAndGet()
            var n = 0
            val lines = generateSequence {
                n++
                // The second packet line blocks, as a slow disk would, once streaming (not while opening).
                if (n == 4 && index == 1) {
                    stuck.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                when {
                    n <= head.size -> head[n - 1]
                    n < 1_000 -> "${(n - 3) * 10}.000,0.000,LEFT,$hex"
                    else -> null
                }
            }
            LineSource(lines.iterator()) { closes.incrementAndGet() }
        }
        val transport = ReplaySockTransport(
            Foot.LEFT,
            StreamSettings(SampleRate.HZ_100, MeasurementRange.K64),
            dump,
            ReplayClock(),
            ReplayOptions(connectDelayMillis = 0),
        )
        transport.open()
        transport.write(SockCommand.Start.encode())
        val reader = async(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            var result: Int
            do {
                result = transport.read(buffer)
            } while (result >= 0)
            result
        }
        assertTrue(stuck.await(5, TimeUnit.SECONDS), "the burst reads the dump")
        // A session closes with a timeout: the close must return while the burst still holds the dump.
        assertNotNull(withTimeoutOrNull(500) { transport.close() }, "close does not wait for the burst")
        assertEquals(0, closes.get())
        release.countDown()
        assertEquals(-1, withTimeout(5_000) { reader.await() })
        withTimeout(5_000) { while (closes.get() < 1) delay(10) }
        assertEquals(opens.get(), closes.get(), "every opened dump closed")
    }
}

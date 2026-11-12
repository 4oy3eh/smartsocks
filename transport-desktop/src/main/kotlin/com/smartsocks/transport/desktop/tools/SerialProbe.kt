package com.smartsocks.transport.desktop.tools

import com.smartsocks.core.model.Foot
import com.smartsocks.core.session.SockSession
import com.smartsocks.transport.desktop.SerialPorts
import com.smartsocks.transport.desktop.SerialSockTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val SHOWN_PACKETS = 5
private const val DEFAULT_SECONDS = 10

/** Manual hardware tool. No args: list ports. `<port> [seconds]`: stream from a sock and print rate and samples. */
fun main(args: Array<String>) {
    if (args.isEmpty()) {
        val ports = SerialPorts.list()
        if (ports.isEmpty()) println("No serial ports found.")
        ports.forEach { println("${it.systemName}\t${if (it.looksLikeBluetooth) "[BT] " else ""}${it.description}") }
        println("Usage: probe --args=\"<port> [seconds]\"")
        return
    }
    val seconds = args.getOrNull(1)?.toIntOrNull() ?: DEFAULT_SECONDS
    runBlocking {
        val session = SockSession(Foot.LEFT, SerialSockTransport(args[0]))
        val printer = launch(start = CoroutineStart.UNDISPATCHED) {
            var shown = 0
            session.rawPackets.collect {
                if (shown++ < SHOWN_PACKETS) {
                    println("packet " + (0 until 8).joinToString(" ") { ch -> it.packet.count(ch).toString() })
                }
            }
        }
        val reporter = launch {
            while (true) {
                delay(1000)
                val s = session.stats.value
                println("${session.connection.value} packets=${s.packets} pps=${"%.1f".format(s.packetsPerSecond)}")
            }
        }
        withTimeoutOrNull(seconds * 1000L) { session.run() }
        printer.cancel()
        reporter.cancel()
        println("Done: ${session.connection.value}, packets=${session.stats.value.packets}")
    }
}

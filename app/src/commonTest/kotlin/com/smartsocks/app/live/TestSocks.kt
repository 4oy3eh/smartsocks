package com.smartsocks.app.live

import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.unsupportedOnThisPlatform
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedLinkException
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.transport.SockTransport
import kotlin.concurrent.Volatile

/** The reconnect waits without jitter: 1 s, 2 s, 4 s … 30 s. */
internal val noJitter: (Int) -> Long = { ReconnectBackoff.delayMillis(it, 0.0) }

/**
 * Simulated socks (for the simulator and Bluetooth choices; serial ports are unsupported) whose links can be made to
 * drop ([Sock.drop]) or to fail to open ([failOpen]); every open is logged with the time from [now]. A Bluetooth
 * device is subject to [problem], like on Android with Bluetooth off. [dropAfterPackets]: every link drops after
 * that many packets, see [SimConfig.disconnectAfterPackets].
 */
internal class TestSocks(
    private val now: () -> Long,
    private val dropAfterPackets: Long? = null,
    private val config: SimConfig = SimConfig(),
) : DeviceFactory {
    /** Every open from now on fails (after the simulator's connect delay), like a sock out of range. */
    @Volatile
    var failOpen = false

    @Volatile
    var problem: DeviceResult.Unavailable? = null

    val socks = mutableListOf<Sock>()

    fun latest(foot: Foot): Sock = socks.last { it.foot == foot }

    override fun create(foot: Foot, choice: DeviceChoice): DeviceResult {
        val mode = when (choice) {
            is DeviceChoice.Simulator -> choice.mode
            is DeviceChoice.Bluetooth -> problem?.let { return it } ?: SimMode.Walking
            else -> return unsupportedOnThisPlatform(choice, "Test")
        }
        val sim = SimulatedSockTransport(foot, config.copy(mode = mode, disconnectAfterPackets = dropAfterPackets))
        val sock = Sock(foot, sim)
        socks += sock
        return DeviceResult.Ready(sock, simulator = sim)
    }

    override fun check(foot: Foot, choice: DeviceChoice): DeviceResult.Unavailable? =
        if (choice is DeviceChoice.Bluetooth) problem else null

    inner class Sock(val foot: Foot, private val sim: SimulatedSockTransport) : SockTransport by sim {
        /** When each [open] was called, on [now]. */
        val opens = mutableListOf<Long>()

        /** Opened and not closed since: what holds a real port. */
        @Volatile
        var isOpen = false
            private set

        @Volatile
        private var dropRequested = false

        /** The next read ends the stream, as if the link broke. */
        fun drop() {
            dropRequested = true
        }

        override suspend fun open() {
            opens += now()
            sim.open()
            if (failOpen) throw SimulatedLinkException("No answer")
            dropRequested = false
            isOpen = true
        }

        override suspend fun read(buffer: ByteArray): Int {
            if (dropRequested) {
                dropRequested = false
                return -1
            }
            return sim.read(buffer)
        }

        override suspend fun close() {
            isOpen = false
            sim.close()
        }
    }
}

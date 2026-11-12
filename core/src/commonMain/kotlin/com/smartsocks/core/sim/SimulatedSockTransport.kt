package com.smartsocks.core.sim

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketEncoder
import com.smartsocks.core.protocol.PacketFormat
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

data class SimConfig(
    val mode: SimMode = SimMode.Walking,
    val bodyWeightKg: Double = 70.0,
    val cadenceStepsPerMin: Double = 100.0,
    /** Relative Gaussian noise on resistance. */
    val noise: Double = 0.01,
    /** Per-sensor sensitivity spread, ±fraction. */
    val gainSpread: Double = 0.15,
    /** Probability per packet of injecting garbage bytes or truncating the packet. */
    val corruptionRate: Double = 0.0,
    /** Bluetooth delivers data in bursts; one burst every [chunkMillis]. */
    val chunkMillis: Long = 20,
    /** Return reads of random length to exercise reassembly. */
    val randomSplit: Boolean = false,
    val connectDelayMillis: Long = 300,
    val failToConnect: Boolean = false,
    /** Simulate the link dropping after this many packets. */
    val disconnectAfterPackets: Long? = null,
    val seed: Int = 42,
)

class SimulatedLinkException(message: String) : Exception(message)

/**
 * In-memory sock: understands the real command set and streams real 47-byte packets generated from [GaitModel].
 * Time advances only through `delay`, so tests under `runTest` are deterministic and instant.
 */
class SimulatedSockTransport(val foot: Foot, private val config: SimConfig = SimConfig()) : SockTransport {
    override val displayName = "Simulated ${foot.name.lowercase().replaceFirstChar { it.uppercase() }}"

    /** Can be changed live, e.g. switch to [SimMode.Lifted] to calibrate. */
    @Volatile
    var mode: SimMode = config.mode

    @Volatile
    var rate: SampleRate = SampleRate.HZ_100
        private set

    @Volatile
    var range: MeasurementRange = MeasurementRange.K64
        private set

    /** Every command received, for tests and diagnostics. */
    val receivedCommands: List<SockCommand> get() = commands.toList()

    private enum class Link { Idle, Streaming, Closed }

    private val link = MutableStateFlow(Link.Idle)
    private val commands = mutableListOf<SockCommand>()
    private val commandLine = StringBuilder()
    private val random = Random(config.seed + foot.ordinal)
    private val gains = DoubleArray(SENSOR_COUNT) { 1 + config.gainSpread * (random.nextDouble() * 2 - 1) }
    private val gait = GaitModel(foot, config.bodyWeightKg, config.cadenceStepsPerMin)
    private val forces = DoubleArray(SENSOR_COUNT)

    @Volatile
    private var resetRequested = false
    private var packetIndex = 0L
    private var totalPackets = 0L
    private var packetsDue = 0.0
    private var pending = ByteArray(0)
    private var pendingPos = 0

    override suspend fun open() {
        check(link.value != Link.Closed) { "Transport closed" }
        delay(config.connectDelayMillis)
        if (config.failToConnect) throw SimulatedLinkException("Simulated connection failure")
    }

    override suspend fun read(buffer: ByteArray): Int {
        while (true) {
            if (link.value == Link.Closed) return -1
            if (pendingPos < pending.size) {
                var n = minOf(buffer.size, pending.size - pendingPos)
                if (config.randomSplit) n = random.nextInt(1, n + 1)
                pending.copyInto(buffer, 0, pendingPos, pendingPos + n)
                pendingPos += n
                return n
            }
            if (link.value != Link.Streaming) {
                link.first { it != Link.Idle }
                continue
            }
            delay(config.chunkMillis)
            if (link.value == Link.Streaming) generateBurst()
        }
    }

    override suspend fun write(data: ByteArray) {
        check(link.value != Link.Closed) { "Transport closed" }
        for (b in data) {
            val c = b.toInt().toChar()
            if (c != '\r') {
                commandLine.append(c)
                continue
            }
            SockCommand.parse(commandLine.toString())?.let(::apply)
            commandLine.clear()
        }
    }

    override suspend fun close() {
        link.value = Link.Closed
    }

    private fun apply(command: SockCommand) {
        commands += command
        when (command) {
            SockCommand.Start -> link.compareAndSet(Link.Idle, Link.Streaming)
            SockCommand.Stop -> link.compareAndSet(Link.Streaming, Link.Idle)
            SockCommand.ResetTimestamp -> resetRequested = true
            is SockCommand.SetSampleRate -> rate = command.rate
            is SockCommand.SetRange -> range = command.range
        }
    }

    private fun generateBurst() {
        if (resetRequested) {
            resetRequested = false
            packetIndex = 0
        }
        val hz = rate.hz
        packetsDue += config.chunkMillis * hz / 1000.0
        val n = packetsDue.toInt()
        packetsDue -= n

        val out = ArrayList<Byte>(n * PacketFormat.SIZE + 16)
        repeat(n) {
            val disconnectAt = config.disconnectAfterPackets
            if (disconnectAt != null && totalPackets >= disconnectAt) {
                link.value = Link.Closed
                return@repeat
            }
            val packet = nextPacket(packetIndex.toDouble() / hz)
            packetIndex++
            totalPackets++
            if (config.corruptionRate > 0 && random.nextDouble() < config.corruptionRate) {
                if (random.nextBoolean()) {
                    repeat(random.nextInt(1, 12)) { out += random.nextInt(256).toByte() }
                    packet.forEach { out += it }
                } else {
                    // Truncated packet: the decoder must drop it and recover on the next one.
                    for (i in 0 until random.nextInt(1, PacketFormat.SIZE - 1)) out += packet[i]
                }
            } else {
                packet.forEach { out += it }
            }
        }
        pending = out.toByteArray()
        pendingPos = 0
    }

    private fun nextPacket(tSeconds: Double): ByteArray {
        gait.forces(tSeconds, mode, forces)
        val counts = IntArray(PacketFormat.CHANNELS)
        for (i in 0 until SENSOR_COUNT) {
            val ohms = FsrModel.ohms(forces[i], gains[i]) * (1 + config.noise * gaussian())
            counts[i] = range.ohmsToCounts(ohms)
        }
        return PacketEncoder.encode(counts)
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }
}

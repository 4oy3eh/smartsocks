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
import kotlinx.coroutines.flow.update
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
    /**
     * Unloaded conductance of an FSR, µS. The default (~0.7 µS ≈ 1.4 MΩ) is a clean FSR: beyond every measurement
     * range, so it reads as an open circuit with zero load. See [preloadedInsole] for a leaky one.
     */
    val leakMicroSiemens: Double = FsrModel.LEAK_MICROSIEMENS,
    /** Per-sensor spread of the leakage, ±fraction. Keep it small enough that the unloaded value fits the range. */
    val leakSpread: Double = 0.2,
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

/** An insole whose sensors conduct ~10 µS (100 kΩ) unloaded, in range from 128 kΩ up: baseline subtraction matters. */
fun SimConfig.preloadedInsole(): SimConfig = copy(leakMicroSiemens = FsrModel.PRELOADED_LEAK_MICROSIEMENS)

class SimulatedLinkException(message: String) : Exception(message)

/**
 * In-memory sock: understands the real command set and streams real 47-byte packets generated from [GaitModel].
 *
 * Without [clockNanos], time advances only through `delay` (every burst covers exactly [SimConfig.chunkMillis]), so
 * tests under `runTest` are deterministic and instant. A real app passes a monotonic clock: a burst then covers the
 * time actually elapsed since the previous one, so the rate stays right when `delay` overshoots (Windows timers tick
 * at ~15.6 ms, which turns a 20 ms delay into ~31 ms and 100 Hz into ~64 Hz).
 */
class SimulatedSockTransport(
    val foot: Foot,
    private val config: SimConfig = SimConfig(),
    private val clockNanos: (() -> Long)? = null,
) : SockTransport {
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
    val receivedCommands: List<SockCommand> get() = commands.value

    private enum class Link { Idle, Streaming, Closed }

    private val link = MutableStateFlow(Link.Idle)
    private val commands = MutableStateFlow<List<SockCommand>>(emptyList())
    private val commandLine = StringBuilder()
    private val random = Random(config.seed + foot.ordinal)
    private val gains = DoubleArray(SENSOR_COUNT) { 1 + config.gainSpread * (random.nextDouble() * 2 - 1) }
    private val leaks = DoubleArray(SENSOR_COUNT) {
        config.leakMicroSiemens * (1 + config.leakSpread * (random.nextDouble() * 2 - 1))
    }
    private val gait = GaitModel(foot, config.bodyWeightKg, config.cadenceStepsPerMin)
    private val forces = DoubleArray(SENSOR_COUNT)

    @Volatile
    private var resetRequested = false
    private var packetIndex = 0L
    private var totalPackets = 0L
    private var packetsDue = 0.0
    private var pending = ByteArray(0)
    private var pendingPos = 0
    private var disconnectPending = false

    /** Clock reading at the previous burst; [NO_BURST] at the start of a stream. Only used with [clockNanos]. */
    @Volatile
    private var lastBurstNanos = NO_BURST

    /** Starts a fresh link, also after [close]: idle state, nothing pending, packet counters from zero. */
    override suspend fun open() {
        delay(config.connectDelayMillis)
        if (config.failToConnect) throw SimulatedLinkException("Simulated connection failure")
        pending = ByteArray(0)
        pendingPos = 0
        disconnectPending = false
        commandLine.clear()
        packetIndex = 0
        totalPackets = 0
        packetsDue = 0.0
        resetRequested = false
        link.value = Link.Idle
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        while (true) {
            if (link.value == Link.Closed) return -1
            if (pendingPos < pending.size) {
                var n = minOf(buffer.size, pending.size - pendingPos)
                if (config.randomSplit) n = random.nextInt(1, n + 1)
                pending.copyInto(buffer, 0, pendingPos, pendingPos + n)
                pendingPos += n
                return n
            }
            if (disconnectPending) {
                // Everything generated before the drop has been delivered.
                link.value = Link.Closed
                return -1
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
        commands.update { it + command }
        when (command) {
            SockCommand.Start -> {
                lastBurstNanos = NO_BURST
                link.compareAndSet(Link.Idle, Link.Streaming)
            }

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
        packetsDue += burstMillis() * hz / 1000.0
        val n = packetsDue.toInt()
        packetsDue -= n

        val out = ArrayList<Byte>(n * PacketFormat.SIZE + 16)
        for (k in 0 until n) {
            val disconnectAt = config.disconnectAfterPackets
            if (disconnectAt != null && totalPackets >= disconnectAt) {
                disconnectPending = true
                break
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

    /** The stream time this burst covers. */
    private fun burstMillis(): Double {
        val clock = clockNanos ?: return config.chunkMillis.toDouble()
        val now = clock()
        val last = lastBurstNanos
        lastBurstNanos = now
        if (last == NO_BURST) return config.chunkMillis.toDouble()
        // A stalled reader must not get a flood of packets afterwards: at most a few chunks' worth.
        return ((now - last) / 1e6).coerceIn(0.0, MAX_BURST_CHUNKS * config.chunkMillis.toDouble())
    }

    /**
     * The packet this simulator streams at [tSeconds] in the current [mode] at [range], without a link and without
     * waiting: for generating recordings offline ([SimulatedRecording]). Advances the noise generator like streaming.
     */
    fun packetAt(tSeconds: Double, range: MeasurementRange = this.range): ByteArray = nextPacket(tSeconds, range)

    private fun nextPacket(tSeconds: Double, range: MeasurementRange = this.range): ByteArray {
        gait.forces(tSeconds, mode, forces)
        val counts = IntArray(PacketFormat.CHANNELS)
        for (i in 0 until SENSOR_COUNT) {
            val ohms = FsrModel.ohms(forces[i], gains[i], leaks[i]) * (1 + config.noise * gaussian())
            counts[i] = range.ohmsToCounts(ohms)
        }
        return PacketEncoder.encode(counts)
    }

    private fun gaussian(): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }

    private companion object {
        const val NO_BURST = Long.MIN_VALUE
        const val MAX_BURST_CHUNKS = 10
    }
}

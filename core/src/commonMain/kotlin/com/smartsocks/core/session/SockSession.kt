package com.smartsocks.core.session

import com.smartsocks.core.analytics.Balance
import com.smartsocks.core.analytics.BalanceCalculator
import com.smartsocks.core.analytics.FootProcessor
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.SensorFrame
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketDecoder
import com.smartsocks.core.protocol.RawPacket
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

data class StreamSettings(val rate: SampleRate = SampleRate.HZ_100, val range: MeasurementRange = MeasurementRange.K64)

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data object Connecting : ConnectionState

    data object Configuring : ConnectionState

    data object Streaming : ConnectionState

    /** Link closed by the device, by an error, or by [SockSession.run] being cancelled. */
    data class Disconnected(val reason: String?) : ConnectionState
}

data class StreamStats(
    val packets: Long = 0,
    val droppedBytes: Long = 0,
    /** Measured over the last second. */
    val packetsPerSecond: Double = 0.0,
)

/** A packet as received, stamped with the same host clock as [FootState.timestampNanos]. */
class TimedPacket(val foot: Foot, val timestampNanos: Long, val packet: RawPacket)

/**
 * One sock: connects, configures, decodes and processes its stream.
 *
 * [latest] is conflated — the UI reads it at display rate however fast packets arrive, which is what kills the
 * legacy lag. [states] delivers every sample (with a bounded buffer) for recording and charts.
 */
class SockSession(
    val foot: Foot,
    private val transport: SockTransport,
    val settings: StreamSettings = StreamSettings(),
    layout: SensorLayout = SensorLayout.Legacy,
    processorSettings: ProcessorSettings = ProcessorSettings(),
    private val clockNanos: () -> Long = monotonicClock(),
    private val commandGapMillis: Long = 20,
) {
    val displayName: String get() = transport.displayName

    private val processor = FootProcessor(foot, layout, processorSettings)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _latest = MutableStateFlow<FootState?>(null)
    val latest: StateFlow<FootState?> = _latest.asStateFlow()

    private val _states = MutableSharedFlow<FootState>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val states: SharedFlow<FootState> = _states.asSharedFlow()

    private val _rawPackets = MutableSharedFlow<TimedPacket>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every undecoded packet, for raw recording and reverse-engineering the unknown bytes. */
    val rawPackets: SharedFlow<TimedPacket> = _rawPackets.asSharedFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val _calibrationProgress = MutableStateFlow<Double?>(null)

    /** 0..1 while a calibration is running, null otherwise. */
    val calibrationProgress: StateFlow<Double?> = _calibrationProgress.asStateFlow()

    private val calibrationRequest = MutableStateFlow<Int?>(null)

    private val decoder = PacketDecoder(::onPacket)
    private var windowStartNanos = 0L
    private var windowPackets = 0L
    private var packetsPerSecond = 0.0

    /**
     * Connects and streams until the link drops or the calling coroutine is cancelled. Never throws except
     * [CancellationException]; failures end up in [connection].
     */
    suspend fun run() {
        var reason: String? = null
        try {
            _connection.value = ConnectionState.Connecting
            transport.open()

            _connection.value = ConnectionState.Configuring
            for (command in SockCommand.startSequence(settings.rate, settings.range)) {
                transport.write(command.encode())
                delay(commandGapMillis)
            }

            _connection.value = ConnectionState.Streaming
            windowStartNanos = clockNanos()
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val n = transport.read(buffer)
                if (n < 0) {
                    reason = "Link closed"
                    break
                }
                decoder.feed(buffer, 0, n)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reason = e.message ?: e::class.simpleName
        } finally {
            withContext(NonCancellable) {
                runCatching { transport.write(SockCommand.Stop.encode()) }
                runCatching { transport.close() }
            }
            _connection.value = ConnectionState.Disconnected(reason)
        }
    }

    /** Lift the foot, then call this: the next [samples] frames become the unloaded baseline. */
    fun calibrate(samples: Int = settings.rate.hz) {
        require(samples > 0)
        calibrationRequest.value = samples
        _calibrationProgress.value = 0.0
    }

    private fun onPacket(packet: RawPacket) {
        calibrationRequest.value?.let {
            processor.startCalibration(it)
            calibrationRequest.value = null
        }

        val now = clockNanos()
        val ohms = DoubleArray(SENSOR_COUNT) { settings.range.countsToOhms(packet.count(it)) }
        val state = processor.process(SensorFrame(foot, now, ohms))
        _calibrationProgress.value = processor.calibrationProgress

        _latest.value = state
        _states.tryEmit(state)
        _rawPackets.tryEmit(TimedPacket(foot, now, packet))
        updateStats(now)
    }

    private fun updateStats(now: Long) {
        windowPackets++
        val elapsed = now - windowStartNanos
        if (elapsed >= 1_000_000_000L) {
            packetsPerSecond = windowPackets * 1e9 / elapsed
            windowPackets = 0
            windowStartNanos = now
        }
        _stats.value = StreamStats(decoder.packetCount, decoder.droppedBytes, packetsPerSecond)
    }

    private companion object {
        const val READ_BUFFER_SIZE = 1024
    }
}

/** Left/right load split and combined COP, recomputed whenever either foot updates. */
fun balanceOf(left: SockSession?, right: SockSession?, stanceHalfWidth: Double = 1.0): Flow<Balance?> {
    val l = left?.latest ?: flowOf(null)
    val r = right?.latest ?: flowOf(null)
    return combine(l, r) { a, b -> BalanceCalculator.compute(a, b, stanceHalfWidth) }
}

/** Latest state of each connected foot, keyed by side. */
fun latestOf(sessions: Collection<SockSession>): Flow<Map<Foot, FootState>> {
    if (sessions.isEmpty()) return flowOf(emptyMap())
    return combine(sessions.map { s -> s.latest.map { s.foot to it } }) { pairs ->
        pairs.mapNotNull { (foot, state) -> state?.let { foot to it } }.toMap()
    }
}

private fun monotonicClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeNanoseconds }
}

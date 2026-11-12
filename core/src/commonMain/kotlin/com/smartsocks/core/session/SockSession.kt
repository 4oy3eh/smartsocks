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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/** A packet as received, stamped with the same clock as [FootState.timestampNanos]. */
class TimedPacket(val foot: Foot, val timestampNanos: Long, val packet: RawPacket)

/**
 * One sock: connects, configures, decodes and processes its stream.
 *
 * [latest] is conflated — the UI reads it at display rate however fast packets arrive, which is what kills the
 * legacy lag. [states] delivers every sample (with a bounded buffer) for recording and charts.
 *
 * Samples are stamped by index at the nominal sample rate ([SampleClock]), slowly steered towards [clockNanos].
 * Pass the **same** [clockNanos] to the sessions of both feet, otherwise their timestamps (and so the interleaved
 * recording) do not line up. [run] may be called again after it returned (reconnect); the baseline survives.
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

    /** Newest state; null before the first sample and again once [run] has ended. */
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

    /** Published every 10 packets or 100 ms, whichever comes first. */
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val _calibrationProgress = MutableStateFlow<Double?>(null)

    /** 0..1 while a calibration is running, null otherwise. */
    val calibrationProgress: StateFlow<Double?> = _calibrationProgress.asStateFlow()

    private val calibrationRequest = MutableStateFlow<Int?>(null)
    private val running = MutableStateFlow(false)

    private var decoder = PacketDecoder(::onPacket)
    private val sampleClock = SampleClock(settings.rate.periodNanos)
    private var windowStartNanos = 0L
    private var windowPackets = 0L
    private var packetsPerSecond = 0.0
    private var packetsSincePublish = 0
    private var lastPublishNanos = 0L

    /**
     * Connects and streams until the link drops or the calling coroutine is cancelled. Never throws except
     * [CancellationException] (and [IllegalStateException] if a run is already active); failures end up in
     * [connection]. On cancellation the transport is closed by a watcher even if its `read` ignores cancellation
     * (the read itself must still be suspending, not block the calling thread).
     */
    suspend fun run() {
        check(running.compareAndSet(false, true)) { "run() is already active on this session" }
        try {
            link()
        } finally {
            running.value = false
        }
    }

    private suspend fun link() {
        decoder = PacketDecoder(::onPacket)
        sampleClock.reset()
        calibrationRequest.value = null
        _calibrationProgress.value = null
        processor.cancelCalibration()
        windowPackets = 0
        packetsPerSecond = 0.0
        packetsSincePublish = 0
        _stats.value = StreamStats()

        var reason: String? = null
        coroutineScope {
            val cleanedUp = CompletableDeferred<Unit>()
            // A blocking read never returns on cancellation, so something else has to close the transport. The
            // watcher gives the normal cleanup (which sends STOP first) a moment, then closes regardless.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        withTimeoutOrNull(WATCHER_GRACE_MILLIS) { cleanedUp.await() }
                        ignoringFailures { withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { transport.close() } }
                    }
                }
            }
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
                lastPublishNanos = windowStartNanos
                val buffer = ByteArray(READ_BUFFER_SIZE)
                while (true) {
                    val n = transport.read(buffer)
                    ensureActive() // a read that ignores cancellation may return -1 after one
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
                    ignoringFailures {
                        withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { transport.write(SockCommand.Stop.encode()) }
                    }
                    ignoringFailures { withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { transport.close() } }
                }
                // Disconnected first, so calibrate() is refused from here on; then drop everything tied to the link.
                _connection.value = ConnectionState.Disconnected(reason)
                calibrationRequest.value = null
                processor.cancelCalibration()
                _calibrationProgress.value = null
                _latest.value = null
                publishStats()
                cleanedUp.complete(Unit)
                watcher.cancel()
            }
        }
    }

    /**
     * Lift the foot, then call this: the next [samples] frames become the unloaded baseline. Only accepted while
     * [connection] is [ConnectionState.Streaming]; returns false (and does nothing) otherwise. A calibration cut
     * short by a link drop is discarded; the previous baseline, if any, is kept.
     */
    fun calibrate(samples: Int = settings.rate.hz): Boolean {
        require(samples > 0)
        if (_connection.value != ConnectionState.Streaming) return false
        calibrationRequest.value = samples
        _calibrationProgress.value = 0.0
        return true
    }

    private fun onPacket(packet: RawPacket) {
        if (calibrationRequest.value != null) {
            calibrationRequest.getAndUpdate { null }?.let(processor::startCalibration)
        }

        val host = clockNanos()
        val now = sampleClock.stamp(host)
        val ohms = DoubleArray(SENSOR_COUNT) { settings.range.countsToOhms(packet.count(it)) }
        val state = processor.process(SensorFrame(foot, now, ohms))
        _calibrationProgress.value = processor.calibrationProgress

        _latest.value = state
        _states.tryEmit(state)
        _rawPackets.tryEmit(TimedPacket(foot, now, packet))
        updateStats(host)
    }

    private fun updateStats(host: Long) {
        windowPackets++
        val elapsed = host - windowStartNanos
        if (elapsed >= 1_000_000_000L) {
            packetsPerSecond = windowPackets * 1e9 / elapsed
            windowPackets = 0
            windowStartNanos = host
        }
        packetsSincePublish++
        if (packetsSincePublish >= STATS_EVERY_PACKETS || host - lastPublishNanos >= STATS_EVERY_NANOS) {
            lastPublishNanos = host
            publishStats()
        }
    }

    private fun publishStats() {
        packetsSincePublish = 0
        _stats.value = StreamStats(decoder.packetCount, decoder.droppedBytes, packetsPerSecond)
    }

    private suspend inline fun ignoringFailures(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private companion object {
        const val READ_BUFFER_SIZE = 1024
        const val CLEANUP_TIMEOUT_MILLIS = 500L
        const val WATCHER_GRACE_MILLIS = 250L
        const val STATS_EVERY_PACKETS = 10
        const val STATS_EVERY_NANOS = 100_000_000L
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

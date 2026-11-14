package com.smartsocks.core.sim

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.PacketFormat
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.protocol.SockCommand
import com.smartsocks.core.recording.LineSource
import com.smartsocks.core.recording.RawDumpReader
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.floor
import kotlin.math.max

/** How a [ReplaySockTransport] plays its dump; what both feet must share is in [ReplayClock]. */
data class ReplayOptions(
    /** Start over at the end; otherwise the link closes there (`read` returns -1) like a sock that went away. */
    val loop: Boolean = false,
    /** Packets are delivered in bursts, one every [chunkMillis], like Bluetooth does. */
    val chunkMillis: Long = 20,
    val connectDelayMillis: Long = 100,
    /**
     * Catch-up limit: a reader that fell behind the clock (a stall) gets the packets of at most this much recording
     * time in one burst; older ones are skipped. Either way the timeline stays where the clock says.
     */
    val maxLagMillis: Double = 200.0,
) {
    init {
        require(chunkMillis > 0) { "chunkMillis must be positive" }
        require(maxLagMillis >= 0) { "maxLagMillis must not be negative" }
    }
}

/**
 * A recorded sock: plays [sourceFoot]'s packets of a raw dump ([com.smartsocks.core.recording.RawPacketRecorder])
 * byte for byte, unknown fields included, so that everything above the transport (decoder, session, recording)
 * runs on recorded data as on a live sock.
 *
 * **Time map.** Every packet of the dump (of both feet, in file order) moves the map on; only [sourceFoot]'s are
 * played. A packet with recorded time `t` is due at `passBase + (t − origin − cut)`, never before the previous one of
 * its foot: `origin` is the first packet's time, `cut` the sum of the silences of the *whole dump* (no foot sent
 * anything) longer than [ReplayClock.maxGapMillis], each shortened to one sample period. A pass lasts
 * `maxT − origin − cut + period`. All of it depends on the dump alone, so two transports playing the two feet of one
 * dump compute the same map: a foot that dropped out or joined late while recording stays silent meanwhile, and
 * both feet start every loop together.
 *
 * **Position.** What is due is decided by the shared [clock], not by this link: each burst plays every packet due by
 * [ReplayClock.positionMillis] (a reader that lags gets the late ones at once, those more than
 * [ReplayOptions.maxLagMillis] late are skipped), and START (also after a STOP, or a reconnect) seeks to the clock's
 * position. So both feet stay in step, whatever happens to either link.
 *
 * Otherwise it behaves like the device: silent until START, STOP pauses; at the end of a pass it loops or closes the
 * link ([loop]). BTS1/BTS2 cannot change recorded data: the packets stay as recorded, and their counts only convert
 * to Ohms at the [recorded] range. The session reading this transport must be built with [recorded] (the app does
 * that); a start sequence that asked for something else shows in [settingsMismatch].
 *
 * [openDump] opens the dump from its start (on [open], and for every pass); reading it happens inside [read] (and
 * [open]), blocking briefly, in small bursts.
 */
class ReplaySockTransport(
    /** The dump's foot whose packets are played. */
    val sourceFoot: Foot,
    /** What the dump was recorded with (its recording's header). */
    val recorded: StreamSettings,
    private val openDump: () -> LineSource,
    val clock: ReplayClock,
    private val options: ReplayOptions = ReplayOptions(),
    override val displayName: String = "Replay ${sourceFoot.name.lowercase()}",
) : SockTransport {
    /** Start over at the end of the dump; may be changed while streaming. */
    @Volatile
    var loop: Boolean = options.loop

    /** Every command received, for tests and diagnostics. */
    val receivedCommands: List<SockCommand> get() = commands.value

    /** The rate the last BTS1 asked for; null before any. */
    @Volatile
    var requestedRate: SampleRate? = null
        private set

    /** The range the last BTS2 asked for; null before any. */
    @Volatile
    var requestedRange: MeasurementRange? = null
        private set

    /** A rate or range other than [recorded] was asked for: the data is still the recorded one. */
    val settingsMismatch: Boolean
        get() = (requestedRate ?: recorded.rate) != recorded.rate ||
            (requestedRange ?: recorded.range) != recorded.range

    /** Packets played since [open] (handed out by [read] or about to be). */
    @Volatile
    var packetsSent: Long = 0
        private set

    /** Packets passed over since [open]: before the position a START sought to, or too late to catch up with. */
    @Volatile
    var packetsSkipped: Long = 0
        private set

    /** Passes started after the first since [open] ([loop], or a seek into a later pass). */
    @Volatile
    var loops: Int = 0
        private set

    private enum class Link { Opening, Idle, Streaming, Closed }

    private val link = MutableStateFlow(Link.Closed)
    private val commands = MutableStateFlow<List<SockCommand>>(emptyList())
    private val commandLine = StringBuilder()
    private val periodMillis = 1000.0 / recorded.rate.hz

    /** The clock's position at the last START, taken by the next burst: play from there. NaN: no seek. */
    @Volatile
    private var seekTo = Double.NaN

    // Everything below is guarded by [dumpLock]: [read] and [open] use it, [close] may come from another thread.
    private val dumpLock = Mutex()
    private var dump: LineSource? = null
    private var reader: RawDumpReader? = null

    /** The time map of the current pass, see the class comment. */
    private var passBase = 0.0
    private var haveOrigin = false
    private var origin = 0.0
    private var maxT = 0.0
    private var cut = 0.0
    private var prevRel = Double.NEGATIVE_INFINITY

    /** Length of a pass; NaN until the dump was read to its end once. */
    private var passLength = Double.NaN

    /** The current pass was read to its end. */
    private var eof = false

    /** The packet read ahead, due at [nextDue] on the clock's axis (ms). */
    private val nextPacket = ByteArray(PacketFormat.SIZE)
    private var haveNext = false
    private var nextDue = 0.0

    /** Due time of the last packet played or skipped. */
    private var lastDue = Double.NEGATIVE_INFINITY

    /** The dump ended and does not loop: close once [pending] is delivered. */
    private var ended = false

    /** Reading the dump failed: thrown by [read] once what came before it is delivered. */
    private var failure: Exception? = null

    // Only touched by the reader coroutine.
    private var pending = ByteArray(PacketFormat.SIZE * 16)
    private var pendingLength = 0
    private var pendingPos = 0

    /**
     * Opens the dump from its start (also after [close]): idle, nothing pending. Throws if the dump cannot be
     * opened, is not a raw dump or has no packet of [sourceFoot].
     */
    override suspend fun open() {
        if (link.getAndUpdate { Link.Opening } == Link.Streaming) clock.release(atEnd = false)
        delay(options.connectDelayMillis)
        try {
            dumpLock.withLock {
                closeDumpLocked()
                passLength = Double.NaN
                ended = false
                failure = null
                loops = 0
                packetsSent = 0
                packetsSkipped = 0
                lastDue = Double.NEGATIVE_INFINITY
                startPassLocked(base = 0.0)
                check(haveNext) { "The recording has no ${sourceFoot.name.lowercase()} foot packets" }
            }
        } catch (e: Throwable) {
            dumpLock.withLock { closeDumpLocked() }
            link.compareAndSet(Link.Opening, Link.Closed)
            throw e
        }
        pendingLength = 0
        pendingPos = 0
        commandLine.clear()
        // A close() meanwhile wins: the dump must not stay open behind a closed link.
        if (!link.compareAndSet(Link.Opening, Link.Idle)) {
            closeDumpIfClosed()
            throw IllegalStateException("Transport closed while opening")
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        if (buffer.isEmpty()) return 0
        while (true) {
            val state = link.value
            if (state == Link.Closed) {
                closeDumpIfClosed()
                return -1
            }
            if (pendingPos < pendingLength) {
                val n = minOf(buffer.size, pendingLength - pendingPos)
                pending.copyInto(buffer, 0, pendingPos, pendingPos + n)
                pendingPos += n
                return n
            }
            failure?.let {
                failure = null
                throw it
            }
            if (ended) {
                // Everything up to the end of the dump has been delivered: the "sock" goes away.
                closeLink(atEnd = true)
                return -1
            }
            if (state != Link.Streaming) {
                link.first { it == Link.Streaming || it == Link.Closed }
                continue
            }
            delay(options.chunkMillis)
            dumpLock.withLock { if (link.value == Link.Streaming) burstLocked() }
            closeDumpIfClosed()
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

    /** Does not wait for a burst that is reading the dump: that one closes it when it is done. */
    override suspend fun close() = closeLink(atEnd = false)

    private fun closeLink(atEnd: Boolean) {
        if (link.getAndUpdate { Link.Closed } == Link.Streaming) clock.release(atEnd)
        closeDumpIfClosed()
    }

    /**
     * Closes the dump if the link is closed and nobody holds the lock; called after every use of the lock, so the
     * last holder closes it when [close] could not.
     */
    private fun closeDumpIfClosed() {
        if (link.value != Link.Closed || !dumpLock.tryLock()) return
        try {
            closeDumpLocked()
        } finally {
            dumpLock.unlock()
        }
    }

    private fun apply(command: SockCommand) {
        commands.update { it + command }
        when (command) {
            SockCommand.Start -> if (link.compareAndSet(Link.Idle, Link.Streaming)) {
                clock.acquire()
                seekTo = clock.positionMillis()
            }

            SockCommand.Stop -> if (link.compareAndSet(Link.Streaming, Link.Idle)) clock.release(atEnd = false)

            // The recorded data is what it is.
            SockCommand.ResetTimestamp -> Unit

            is SockCommand.SetSampleRate -> requestedRate = command.rate

            is SockCommand.SetRange -> requestedRange = command.range
        }
    }

    /** Plays one burst: every packet due by the clock's position. */
    private fun burstLocked() {
        pendingLength = 0
        pendingPos = 0
        try {
            val position = clock.positionMillis()
            val target = seekTo
            val seeking = !target.isNaN()
            if (seeking) {
                seekTo = Double.NaN
                seekLocked(target)
            }
            val oldest = if (seeking) target else position - options.maxLagMillis
            playLocked(position, oldest, jumpAllowed = seeking)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What was assembled before still goes out; then the link fails.
            failure = e
            haveNext = false
            eof = false
        }
    }

    /** Before playing from [position]: back to the pass that holds it if the clock went back (it starts over). */
    private fun seekLocked(position: Double) {
        if (position >= passBase && position + options.chunkMillis >= lastDue) return
        val length = passLength
        val base = if (length.isNaN()) 0.0 else floor(position / length) * length
        startPassLocked(base)
        lastDue = Double.NEGATIVE_INFINITY
    }

    /**
     * Plays the packets due by [position]; those due before [oldest] are skipped. At the end of a pass it starts the
     * next one ([loop], or [jumpAllowed]: a seek into a later pass) once the clock gets there, else ends.
     */
    private fun playLocked(position: Double, oldest: Double, jumpAllowed: Boolean) {
        while (true) {
            if (!haveNext) {
                if (!eof || passLength.isNaN()) return
                val end = passBase + passLength
                if (position < end) return // the silent tail of the pass
                if (!loop && !jumpAllowed) {
                    ended = true
                    return
                }
                val passes = max(1.0, floor((position - passBase) / passLength))
                startPassLocked(passBase + passes * passLength)
                if (!haveNext) {
                    ended = true // the dump changed under us and has none of our packets now
                    return
                }
                loops += passes.toInt()
                continue
            }
            if (nextDue > position) return
            if (nextDue >= oldest) {
                append(nextPacket)
                packetsSent++
            } else {
                packetsSkipped++
            }
            lastDue = nextDue
            readNextLocked()
        }
    }

    /** (Re)opens the dump for a pass starting at [base] and reads up to its first packet of [sourceFoot]. */
    private fun startPassLocked(base: Double) {
        closeDumpLocked()
        val source = openDump()
        dump = source
        reader = RawDumpReader(source, foot = null, bytesOf = setOf(sourceFoot))
        passBase = base
        haveOrigin = false
        cut = 0.0
        prevRel = Double.NEGATIVE_INFINITY
        eof = false
        readNextLocked()
    }

    /** Moves the time map over the dump's packets up to the next one of [sourceFoot], see the class comment. */
    private fun readNextLocked() {
        haveNext = false
        val r = reader ?: return
        val maxGap = clock.maxGapMillis
        while (r.next()) {
            val t = r.tMillis
            if (!haveOrigin) {
                haveOrigin = true
                origin = t
                maxT = t
            } else {
                val gap = t - maxT
                if (maxGap != null && gap > maxGap) cut += gap - periodMillis
                if (t > maxT) maxT = t
            }
            if (r.packetFoot != sourceFoot) continue
            val rel = max(t - origin - cut, prevRel)
            prevRel = rel
            nextDue = passBase + rel
            r.packet.copyInto(nextPacket)
            haveNext = true
            return
        }
        eof = true
        if (haveOrigin) passLength = maxT - origin - cut + periodMillis
    }

    private fun closeDumpLocked() {
        val source = dump
        dump = null
        reader = null
        haveNext = false
        source?.close()
    }

    private fun append(packet: ByteArray) {
        if (pendingLength + packet.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, 64))
        packet.copyInto(pending, pendingLength)
        pendingLength += packet.size
    }
}

package com.smartsocks.app.recording

import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.LiveGeometry
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.recording.CsvRecorder
import com.smartsocks.core.recording.RawPacketRecorder
import com.smartsocks.core.recording.RecordingMetadata
import com.smartsocks.core.recording.RecordingSink
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.session.TimedPacket
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** What the record button shows. */
sealed interface RecordingState {
    data object Idle : RecordingState

    /** Creating the files. */
    data object Starting : RecordingState

    data class Recording(
        val id: String,
        /** The recorded feet: those streaming at the start and those that joined since (never shrinks). */
        val feet: Set<Foot>,
        /** On the live controller's clock ([LiveController.nowNanos]): the UI derives the elapsed time from it. */
        val startedNanos: Long,
        /** Written so far (both files), bytes. */
        val bytes: Long = 0,
        val rows: Long = 0,
        val markers: Int = 0,
        /** Samples the storage could not keep up with, see [RecordingController]. */
        val dropped: Long = 0,
        /** Raw packets dropped for the same reason. */
        val droppedRaw: Long = 0,
        /**
         * Feet not recorded yet that are on their way to streaming ([isWaitingToJoin]): each joins the recording when
         * it streams. Never overlaps [feet].
         */
        val waiting: Set<Foot> = emptySet(),
        /**
         * Feet not recorded that stream at another rate or range than the recording (a replay of another
         * recording): they cannot go into this file. Never overlaps [feet] or [waiting].
         */
        val incompatible: Set<Foot> = emptySet(),
    ) : RecordingState {
        /**
         * After a change of the feet's links: a streaming foot is recorded from now on if it [fits] the recording
         * (streams at its rate and range), else it is [incompatible]; [waiting] is recomputed.
         */
        fun following(statuses: Collection<FootStatus>, fits: (Foot) -> Boolean = { true }): Recording {
            val streaming = statuses.filter { it.state == LinkState.Streaming && it.foot !in feet }.map { it.foot }
            val recorded = feet + streaming.filter(fits)
            val incompatible = streaming.filterNot(fits).toSet()
            val waiting = statuses
                .filter { it.foot !in recorded && it.foot !in incompatible && it.isWaitingToJoin() }
                .map { it.foot }
                .toSet()
            if (recorded == feet && waiting == this.waiting && incompatible == this.incompatible) return this
            return copy(feet = recorded, waiting = waiting, incompatible = incompatible)
        }
    }

    /** Writing what is still queued and closing the files. */
    data class Stopping(val id: String) : RecordingState
}

enum class StopReason {
    User,

    /**
     * The app went to the background (Android) and the recording could not go on there (no foreground service
     * allowed), see [BackgroundRecording].
     */
    Background,

    /**
     * In the background no recorded foot was connected (the links went down there, or already before the app left
     * the foreground) and none could come back by itself: reconnecting was off or impossible without the user
     * (Bluetooth off, permission), or the feet kept failing to reconnect for
     * [BackgroundRecording.RECONNECT_GIVE_UP_MILLIS].
     */
    LinksLost,

    AppClosed,

    /** Writing failed; see [RecordingResult.error]. */
    Error,
}

/** How the last recording ended; shown until dismissed or the next recording starts. */
data class RecordingResult(
    /** Null if no recording was kept: the files could not be created, or nothing arrived before the stop. */
    val id: String?,
    val reason: StopReason,
    val durationMillis: Double = 0.0,
    val rows: Long = 0,
    val markers: Int = 0,
    val dropped: Long = 0,
    val droppedRaw: Long = 0,
    val error: String? = null,
    /** The feet with samples in the recording. */
    val feet: Set<Foot> = emptySet(),
    /** Feet that joined after the start, with the time of their first sample (ms on the recording's time axis). */
    val joinedMillis: Map<Foot, Double> = emptyMap(),
    /** Feet that were waiting to connect during the recording but never streamed into it. */
    val neverJoined: Set<Foot> = emptySet(),
    /** Feet that streamed at another rate or range than the recording, so were left out of it. */
    val incompatible: Set<Foot> = emptySet(),
)

/**
 * The foot is configured and meant to be connected ([FootStatus.wanted]), but does not stream (yet): connecting,
 * waiting to reconnect, held up by a fixable device problem (Bluetooth off, permission), or for a moment between two
 * of those. While recording, such a foot that is not recorded yet is expected: it joins the recording once it
 * streams. Based on the request rather than the momentary link state, so that a passing "idle" (a session rebuilt)
 * does not drop it (in the background it would be paused until the foreground). Its request ends when it cannot
 * come back by itself (disconnected by the user, reconnect off, an unusable device).
 */
fun FootStatus.isWaitingToJoin(): Boolean = device != DeviceChoice.None && wanted && state != LinkState.Streaming

/**
 * Records the feet that stream when [start] is called into a new [SessionStore] recording: the processed samples as
 * CSV and, if [com.smartsocks.core.settings.RecordingSettings.saveRaw], every packet into a second file.
 *
 * A configured foot that only streams later (it was still connecting or reconnecting at the start, or the user
 * connects it meanwhile) joins the recording from that moment: its samples and packets go into the same files, on
 * the same time axis, so playback keeps the feet aligned; the CSV marks the join (see
 * [com.smartsocks.core.recording.CsvRecorder]). Until then a foot on its way shows as
 * [RecordingState.Recording.waiting].
 *
 * The sample path is never blocked: each foot's collector (in [scope]) only hands the sample to a bounded queue
 * ([queueCapacity]); formatting and writing happen in one writer coroutine on [io], which flushes the files about
 * once a second. If the storage cannot keep up and the queue is full, samples and raw packets are dropped and
 * counted ([RecordingState.Recording.dropped], [RecordingState.Recording.droppedRaw]). Not counted: what a
 * session's own stream buffer (1024 samples, oldest dropped) loses if these collectors stall, which only happens if
 * the whole process does.
 *
 * The file's header names one rate and range: the ones the recorded feet's sessions read at (a replay's are the
 * recorded ones, see [com.smartsocks.app.platform.DeviceResult.Ready.stream]). Feet streaming at different ones
 * cannot be recorded together: [start] refuses, and a foot that would join at other ones is left out
 * ([RecordingState.Recording.incompatible]).
 *
 * A foot that disconnects keeps its place: when it streams again (reconnect) its samples go on into the same file,
 * on the same time axis (the controller's shared clock). While a recording runs, the stream settings (rate, range)
 * and the feet's devices cannot change (see [restrict], installed as the settings' guard): the file's header
 * describes them, and raw packets are only decodable with the range they were recorded at.
 *
 * [start], [mark] and [stop] are meant for the UI thread; state flows may be read from anywhere.
 */
@OptIn(ExperimentalAtomicApi::class)
class RecordingController(
    private val live: LiveController,
    private val store: SessionStore,
    private val settings: SettingsRepository,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val publishMillis: Long = PUBLISH_MILLIS,
    private val flushMillis: Long = FLUSH_MILLIS,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = scope + job

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _lastResult = MutableStateFlow<RecordingResult?>(null)
    val lastResult: StateFlow<RecordingResult?> = _lastResult.asStateFlow()

    private val _finished = MutableStateFlow(0)

    /** Bumped whenever a recording has been finalized: the sessions list reloads on it. */
    val finished: StateFlow<Int> = _finished.asStateFlow()

    private val _activeRecording = MutableStateFlow<String?>(null)

    /**
     * The id of the recording whose files are open (from their creation until they are closed and summarised):
     * the sessions list must not open, rename or delete it meanwhile.
     */
    val activeRecording: StateFlow<String?> = _activeRecording.asStateFlow()

    @Volatile
    private var current: Run? = null

    init {
        settings.guard = ::restrict
    }

    /** True from [start] until the recording's files are closed. */
    val isRecording: Boolean get() = _state.value != RecordingState.Idle

    /**
     * What of a settings change may take effect: while recording, the stream settings and the devices stay (a
     * simulator's walking/standing mode and a replay's loop setting may still change: they do not rebuild the
     * session).
     */
    fun restrict(old: AppSettings, new: AppSettings): AppSettings {
        if (!isRecording) return new
        fun keep(foot: Foot): DeviceChoice {
            val before = old.device(foot)
            val after = new.device(foot)
            val modeOnly = before is DeviceChoice.Simulator && after is DeviceChoice.Simulator &&
                before.preloaded == after.preloaded
            val loopOnly = before is DeviceChoice.Replay && after is DeviceChoice.Replay &&
                before.copy(loop = after.loop) == after
            return if (modeOnly || loopOnly) after else before
        }
        return new.copy(stream = old.stream, left = keep(Foot.LEFT), right = keep(Foot.RIGHT))
    }

    /** The feet that stream now: what [start] would record. */
    fun streamingFeet(): Set<Foot> =
        Foot.entries.filter { live.link(it).status.value.state == LinkState.Streaming }.toSet()

    /**
     * Starts recording the streaming feet (a configured foot that streams later joins); false if nothing streams, a
     * recording is running, or the streaming feet read at different rates or ranges (then [lastResult] says so).
     */
    fun start(): Boolean {
        val feet = streamingFeet()
        if (feet.isEmpty() || _state.value != RecordingState.Idle) return false
        val streams = feet.mapNotNull { live.link(it).session.value?.settings }.distinct()
        val stream = streams.singleOrNull()
        if (stream == null) {
            if (streams.size > 1) {
                _lastResult.value = RecordingResult(null, StopReason.Error, error = DIFFERENT_STREAMS)
            }
            return false
        }
        if (!_state.compareAndSet(RecordingState.Idle, RecordingState.Starting)) return false
        _lastResult.value = null
        val s = settings.settings.value
        val run = Run(feet, stream, s.recording.saveRaw)
        current = run
        run.job = scope.launch(io) { run.execute() }
        return true
    }

    /** Adds a marker "Mark n" at the newest recorded sample; returns its label, null when not recording. */
    fun mark(): String? = current?.mark()

    /** Stops the recording (if any) and finalizes its files in the background. */
    fun stop(reason: StopReason = StopReason.User) {
        current?.stop(reason)
    }

    /** [stop] and wait until the files are closed. */
    suspend fun stopAndJoin(reason: StopReason = StopReason.User) {
        val run = current ?: return
        run.stop(reason)
        run.job?.join()
    }

    fun dismissResult() {
        _lastResult.value = null
    }

    private inner class Run(val feet: Set<Foot>, val stream: StreamSettings, val saveRaw: Boolean) {
        var job: Job? = null

        /** Every foot that streamed at other settings at some point while not recorded. */
        @Volatile
        var incompatible: Set<Foot> = emptySet()

        /** [foot]'s current session reads at this recording's rate and range. */
        fun fits(foot: Foot): Boolean = live.link(foot).session.value?.settings == stream

        /** FootState, TimedPacket, Marker or Flush: no wrapper per sample. */
        val queue = Channel<Any>(queueCapacity)

        /** Every foot that was [RecordingState.Recording.waiting] at some point: reported if it never joined. */
        @Volatile
        var expected: Set<Foot> = emptySet()

        val droppedRows = AtomicLong(0)
        val droppedRaw = AtomicLong(0)

        @Volatile
        var feeders: Job? = null

        @Volatile
        var reason = StopReason.User

        @Volatile
        var markers = 0

        fun mark(): String? {
            if (queue.isClosedForSend) return null
            val label = "Mark ${markers + 1}"
            if (!queue.trySend(Marker(label)).isSuccess) return null
            markers++
            return label
        }

        fun stop(reason: StopReason) {
            if (queue.isClosedForSend) return
            this.reason = reason
            // Before closing: once the queue is closed the writer may finish (and publish Idle) at any moment, and
            // that must stay the last word.
            _state.update { if (it is RecordingState.Recording) RecordingState.Stopping(it.id) else it }
            queue.close()
            feeders?.cancel()
        }

        fun offer(item: Any) {
            if (queue.trySend(item).isSuccess || queue.isClosedForSend) return
            if (item is FootState) droppedRows.addAndFetch(1L) else droppedRaw.addAndFetch(1L)
        }

        suspend fun execute() {
            // Stopped before the files existed: nothing to create.
            if (queue.isClosedForSend) return finish(null, null, reason, null)
            var created: NewRecording? = null
            val writer = try {
                val files = store.create(saveRaw)
                created = files
                _activeRecording.value = files.id
                // The header names the feet of [start] only: any other foot (even one that began to stream while the
                // files were created) is announced by a join line with its first sample, so the file never names a
                // foot it may then have no data of.
                val metadata =
                    RecordingMetadata(files.startTimeIso, stream.rate, stream.range, feet, layout = LiveGeometry.layout)
                Writer(files, metadata)
            } catch (e: CancellationException) {
                created?.let { discard(it) }
                throw e
            } catch (e: Exception) {
                created?.let { discard(it) }
                finish(null, null, StopReason.Error, e.message ?: "Cannot create the recording")
                return
            }
            val files = created!!
            // Stopped while the files were being created: it never shows as running (that would, e.g., start a
            // foreground service from the background); it only finishes.
            _state.update {
                if (it == RecordingState.Starting && !queue.isClosedForSend) {
                    RecordingState.Recording(files.id, feet, live.nowNanos()).following(statuses(), ::fits)
                        .also { r ->
                            expected = r.waiting
                            incompatible = r.incompatible
                        }
                } else {
                    it
                }
            }
            var error: String? = null
            try {
                // The feeders run in the controller's scope, not on [io]: a slow disk only fills the queue.
                feeders = scope.launch(start = CoroutineStart.UNDISPATCHED) { feed(writer) }
                // stop() may have come before the feeders existed.
                if (queue.isClosedForSend) feeders?.cancel()
                for (item in queue) writer.write(item)
            } catch (e: CancellationException) {
                reason = StopReason.AppClosed
                throw e
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName ?: "Write error"
                reason = StopReason.Error
            } finally {
                withContext(NonCancellable) {
                    queue.close()
                    feeders?.cancel()
                    // Samples still queued when the scope was cancelled: write them too.
                    if (error == null) {
                        try {
                            while (true) writer.write(queue.tryReceive().getOrNull() ?: break)
                        } catch (e: Exception) {
                            error = e.message ?: "Write error"
                        }
                    }
                    val closeError = writer.close()
                    if (error == null && closeError != null) error = closeError
                    var kept: String? = files.id
                    if (writer.rows == 0L && writer.markers == 0) {
                        // Nothing arrived: no header-only file in the list.
                        kept = null
                        runCatching { store.delete(files.id) }
                    } else {
                        val summary = writer.summary(droppedRows.load(), droppedRaw.load())
                        runCatching { store.writeSummary(files.id, summary) }
                    }
                    finish(kept, writer, if (error != null) StopReason.Error else reason, error)
                }
            }
        }

        private fun discard(files: NewRecording) {
            runCatching { files.csv.close() }
            runCatching { files.raw?.close() }
            runCatching { store.delete(files.id) }
        }

        private fun statuses() = Foot.entries.map { live.link(it).status.value }

        private suspend fun feed(writer: Writer) = coroutineScope {
            // Every foot, not only those recorded at the start: one that streams later joins (a foot without a
            // device has no session), unless it reads at other settings. The devices cannot change meanwhile, see
            // [restrict].
            for (foot in Foot.entries) {
                val link = live.link(foot)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    link.session.collectLatest { session ->
                        if (session?.settings == stream) session.states.collect { offer(it) }
                    }
                }
                if (saveRaw) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        link.session.collectLatest { session ->
                            if (session?.settings == stream) session.rawPackets.collect { offer(it) }
                        }
                    }
                }
            }
            launch {
                combine(live.left.status, live.right.status) { l, r -> listOf(l, r) }.collect { statuses ->
                    _state.update { if (it is RecordingState.Recording) it.following(statuses, ::fits) else it }
                    (_state.value as? RecordingState.Recording)?.let {
                        expected = expected + it.waiting
                        incompatible = incompatible + it.incompatible
                    }
                }
            }
            // A flush a second also when no sample comes (both feet gone): what was written reaches the disk.
            launch {
                while (true) {
                    delay(flushMillis)
                    queue.trySend(Flush)
                }
            }
            launch {
                while (true) {
                    delay(publishMillis)
                    _state.update {
                        if (it is RecordingState.Recording) {
                            it.copy(
                                bytes = writer.bytes,
                                rows = writer.rows,
                                markers = markers,
                                dropped = droppedRows.load(),
                                droppedRaw = droppedRaw.load(),
                            )
                        } else {
                            it
                        }
                    }
                }
            }
        }

        /** The only place that ends a recording's state: everything before it leaves Idle alone. */
        private fun finish(id: String?, writer: Writer?, reason: StopReason, error: String?) {
            _lastResult.value = RecordingResult(
                id = id,
                reason = reason,
                durationMillis = writer?.durationMillis ?: 0.0,
                rows = writer?.rows ?: 0,
                markers = writer?.markers ?: 0,
                dropped = droppedRows.load(),
                droppedRaw = droppedRaw.load(),
                error = error,
                feet = writer?.feetSeen.orEmpty(),
                joinedMillis = writer?.joinedMillis.orEmpty(),
                neverJoined = expected - incompatible - writer?.feetSeen.orEmpty(),
                incompatible = incompatible - writer?.feetSeen.orEmpty(),
            )
            if (current === this) current = null
            _activeRecording.value = null
            _state.value = RecordingState.Idle
            _finished.update { it + 1 }
        }
    }

    private class Marker(val label: String)

    /** Asks the writer to push what it has to the disk. */
    private object Flush

    /** Owned by the writer coroutine: formats and writes, flushes on [Flush] or every few thousand items. */
    private inner class Writer(files: NewRecording, metadata: RecordingMetadata) {
        private val csvSink = CountingSink(files.csv)
        private val rawSink = files.raw?.let(::CountingSink)
        private val csv = CsvRecorder(csvSink, metadata)
        private val raw = rawSink?.let { RawPacketRecorder(it) }
        private val seen = mutableSetOf<Foot>()

        /** The feet with a written sample; read after the writer finished. */
        val feetSeen: Set<Foot> get() = seen.toSet()

        /** Feet that joined after the start and when; read after the writer finished. */
        val joinedMillis: Map<Foot, Double> get() = csv.joins.associate { it.foot to it.tMillis }

        private var firstNanos = Long.MAX_VALUE
        private var lastNanos = Long.MIN_VALUE
        private var sinceFlush = 0

        @Volatile
        var rows = 0L
            private set

        @Volatile
        var markers = 0
            private set

        val bytes: Long get() = csvSink.chars + (rawSink?.chars ?: 0)

        val durationMillis: Double get() = if (rows == 0L) 0.0 else (lastNanos - firstNanos) / 1e6

        fun write(item: Any) {
            when (item) {
                is FootState -> {
                    csv.record(item)
                    rows++
                    seen += item.foot
                    if (item.timestampNanos < firstNanos) firstNanos = item.timestampNanos
                    if (item.timestampNanos > lastNanos) lastNanos = item.timestampNanos
                }

                is TimedPacket -> raw?.record(item)

                is Marker -> {
                    csv.marker(item.label)
                    markers++
                }

                Flush -> {
                    if (sinceFlush > 0) flush()
                    return
                }
            }
            if (++sinceFlush >= FLUSH_EVERY_ITEMS) flush()
        }

        private fun flush() {
            csvSink.flush()
            rawSink?.flush()
            sinceFlush = 0
        }

        /** Closes both files; the first error, if any. */
        fun close(): String? {
            var error: String? = null
            for (sink in listOfNotNull(csvSink, rawSink)) {
                try {
                    sink.close()
                } catch (e: Exception) {
                    if (error == null) error = e.message ?: "Cannot close the recording"
                }
            }
            return error
        }

        fun summary(dropped: Long, droppedRaw: Long) =
            SessionSummary(durationMillis, feetSeen, rows, markers, dropped, droppedRaw, joinedMillis)

        /** Counts what goes through, for the size shown while recording. */
        private inner class CountingSink(private val inner: RecordingSink) : RecordingSink {
            @Volatile
            var chars = 0L
                private set

            override fun writeLine(line: String) {
                inner.writeLine(line)
                chars += line.length + 1
            }

            override fun flush() = inner.flush()

            override fun close() = inner.close()
        }
    }

    companion object {
        /** Ten seconds of both feet at 200 Hz, with the raw packets. */
        const val DEFAULT_QUEUE_CAPACITY = 8192
        const val PUBLISH_MILLIS = 250L
        const val FLUSH_MILLIS = 1_000L
        private const val FLUSH_EVERY_ITEMS = 4096

        const val DIFFERENT_STREAMS =
            "Left and right use different sample rates or ranges, so they can't be recorded into one file"
    }
}

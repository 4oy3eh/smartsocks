package com.smartsocks.app.recording

import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.recording.CsvRecorder
import com.smartsocks.core.recording.RawPacketRecorder
import com.smartsocks.core.recording.RecordingMetadata
import com.smartsocks.core.recording.RecordingSink
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
    ) : RecordingState

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
)

/**
 * Records the feet that stream when [start] is called into a new [SessionStore] recording: the processed samples as
 * CSV and, if [com.smartsocks.core.settings.RecordingSettings.saveRaw], every packet into a second file.
 *
 * The sample path is never blocked: each foot's collector (in [scope]) only hands the sample to a bounded queue
 * ([queueCapacity]); formatting and writing happen in one writer coroutine on [io], which flushes the files about
 * once a second. If the storage cannot keep up and the queue is full, samples and raw packets are dropped and
 * counted ([RecordingState.Recording.dropped], [RecordingState.Recording.droppedRaw]). Not counted: what a
 * session's own stream buffer (1024 samples, oldest dropped) loses if these collectors stall, which only happens if
 * the whole process does.
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
     * simulator's walking/standing mode may still change: that does not rebuild the session).
     */
    fun restrict(old: AppSettings, new: AppSettings): AppSettings {
        if (!isRecording) return new
        fun keep(foot: Foot): DeviceChoice {
            val before = old.device(foot)
            val after = new.device(foot)
            val modeOnly = before is DeviceChoice.Simulator && after is DeviceChoice.Simulator &&
                before.preloaded == after.preloaded
            return if (modeOnly) after else before
        }
        return new.copy(stream = old.stream, left = keep(Foot.LEFT), right = keep(Foot.RIGHT))
    }

    /** The feet that stream now: what [start] would record. */
    fun streamingFeet(): Set<Foot> =
        Foot.entries.filter { live.link(it).status.value.state == LinkState.Streaming }.toSet()

    /** Starts recording the streaming feet; false if nothing streams or a recording is running. */
    fun start(): Boolean {
        val feet = streamingFeet()
        if (feet.isEmpty()) return false
        if (!_state.compareAndSet(RecordingState.Idle, RecordingState.Starting)) return false
        _lastResult.value = null
        val s = settings.settings.value
        val run = Run(feet, s.stream.rate, s.stream.range, s.recording.saveRaw)
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

    private inner class Run(
        val feet: Set<Foot>,
        val rate: SampleRate,
        val range: MeasurementRange,
        val saveRaw: Boolean,
    ) {
        var job: Job? = null

        /** FootState, TimedPacket, Marker or Flush: no wrapper per sample. */
        val queue = Channel<Any>(queueCapacity)
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
                Writer(files, RecordingMetadata(files.startTimeIso, rate, range, feet))
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
                    RecordingState.Recording(files.id, feet, live.nowNanos())
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

        private suspend fun feed(writer: Writer) = coroutineScope {
            for (foot in feet) {
                val link = live.link(foot)
                launch(start = CoroutineStart.UNDISPATCHED) {
                    link.session.collectLatest { session -> session?.states?.collect { offer(it) } }
                }
                if (saveRaw) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        link.session.collectLatest { session -> session?.rawPackets?.collect { offer(it) } }
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
        private val feetSeen = mutableSetOf<Foot>()
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
                    feetSeen += item.foot
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
            SessionSummary(durationMillis, feetSeen.toSet(), rows, markers, dropped, droppedRaw)

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
    }
}

package com.smartsocks.app.playback

import com.smartsocks.app.live.LiveGeometry
import com.smartsocks.app.recording.CountingLines
import com.smartsocks.app.recording.SessionStore
import com.smartsocks.app.recording.SessionSummary
import com.smartsocks.app.recording.StoredSession
import com.smartsocks.core.model.Foot
import com.smartsocks.core.playback.GaitEvents
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.recording.CsvSessionReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/** The sessions list. */
sealed interface SessionList {
    data object Loading : SessionList

    data class Loaded(val sessions: List<StoredSession>) : SessionList

    data class Failed(val message: String) : SessionList
}

/** Opening a recording for the player: reading it, or why that failed. */
sealed interface OpenState {
    /** [progress] 0..1 of the file read so far. */
    data class Reading(val session: StoredSession, val progress: Float) : OpenState

    /** The reader's message (with the line number for a malformed recording). */
    data class Failed(val session: StoredSession, val message: String) : OpenState
}

/**
 * The sessions list and the open player. Every file access runs on [io]; [scope] is the UI scope (the player is
 * driven from it, see [PlayerController]). Owned by the screen (Android: its ViewModel), so an open player survives a
 * rotation; the live controller and the recorder outlive it (Android: the Application object).
 *
 * [activeRecording] is the recording whose files are being written (see
 * [com.smartsocks.app.recording.RecordingController.activeRecording]): it cannot be opened, renamed or deleted
 * meanwhile, and once it is finished a player still showing an older state of it is closed.
 *
 * A recording without a summary (cut short by a crash, or killed in the background) is summarised once in the
 * background after the list is loaded ([summarize]; unless [summarizeMissing] is off), so that the list soon shows
 * its duration and all its feet (its header only names the feet recorded from the start).
 */
class SessionsController(
    val store: SessionStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clockNanos: (() -> Long)? = null,
    val activeRecording: StateFlow<String?> = MutableStateFlow(null),
    private val summarizeMissing: Boolean = true,
) {
    private val _list = MutableStateFlow<SessionList>(SessionList.Loading)
    val list: StateFlow<SessionList> = _list.asStateFlow()

    private val _opening = MutableStateFlow<OpenState?>(null)
    val opening: StateFlow<OpenState?> = _opening.asStateFlow()

    private val _player = MutableStateFlow<PlayerController?>(null)

    /** The open session's player, null when none is open. */
    val player: StateFlow<PlayerController?> = _player.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)

    /** Why the last rename/delete/open was refused or failed; cleared by [dismissMessage]. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private var openJob: Job? = null
    private var refreshJob: Job? = null

    /** Recordings without a summary already summarised (or tried): each only once. UI thread only. */
    private val summarised = mutableSetOf<String>()

    /** Bumped by every [open] and [cancelOpen]: an older open never touches [opening] again. */
    @Volatile
    private var openGeneration = 0

    init {
        scope.launch {
            var previous: String? = null
            activeRecording.collect { id ->
                val finished = previous
                previous = id
                if (finished != null && finished != id) {
                    // Its files are complete now: what a player read before is outdated.
                    if (_player.value?.session?.id == finished) closePlayer()
                    runCatching { refreshNow() }
                }
            }
        }
    }

    /** The recording [session] is still being written. */
    fun isRecording(session: StoredSession): Boolean = session.id == activeRecording.value

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val list = try {
                SessionList.Loaded(withContext(io) { store.list() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SessionList.Failed(e.message ?: "Cannot list the recordings")
            }
            _list.value = list
            if (list is SessionList.Loaded && summarizeMissing) summarizeMissing(list.sessions)
        }
    }

    /** Summarises, on [io], the recordings of [sessions] that have no summary yet (not the one being written). */
    private suspend fun summarizeMissing(sessions: List<StoredSession>) {
        val missing = sessions.filter { it.summary == null && !isRecording(it) && it.id !in summarised }
        if (missing.isEmpty()) return
        var written = false
        for (session in missing) {
            if (!summarised.add(session.id)) continue
            try {
                withContext(io) {
                    val summary = summarize(store, session.id) ?: return@withContext
                    // Never over one the recorder (or a read) wrote meanwhile.
                    store.writeSummary(session.id, summary, replace = false)
                    written = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Unreadable: the list shows it without a summary, opening it says why.
            }
        }
        if (written) runCatching { refreshNow() }
    }

    fun delete(session: StoredSession) {
        if (refuseWhileRecording(session)) return
        if (_player.value?.session?.id == session.id) closePlayer()
        fileOperation("Cannot delete") { store.delete(session.id) }
    }

    /** Null when done, else why [newName] cannot be used (shown in the rename dialog). */
    suspend fun rename(session: StoredSession, newName: String): String? {
        if (isRecording(session)) return STILL_RECORDING
        return try {
            val renamed = withContext(io) { store.rename(session.id, newName) }
            refreshNow()
            // An open player keeps its data; only its name changes on the next open.
            if (renamed.id != session.id && _player.value?.session?.id == session.id) closePlayer()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "Cannot rename"
        }
    }

    private fun refuseWhileRecording(session: StoredSession): Boolean {
        if (!isRecording(session)) return false
        _message.value = STILL_RECORDING
        return true
    }

    private suspend fun refreshNow() {
        _list.value = SessionList.Loaded(withContext(io) { store.list() })
    }

    /** Runs [block] on [io], then reloads the list; a failure is shown in [message]. */
    private fun fileOperation(failure: String, block: () -> Unit) {
        scope.launch {
            try {
                withContext(io) { block() }
                refreshNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "$failure: ${e.message ?: e::class.simpleName}"
                runCatching { refreshNow() }
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    /**
     * Reads [session] off the main thread (progress in [opening], [cancelOpen] stops it) and opens it in a new
     * [player], then calls [onOpened]. Opening the session already open just keeps that player; the recording in
     * progress is refused.
     */
    fun open(session: StoredSession, onOpened: () -> Unit = {}) {
        if (refuseWhileRecording(session)) return
        if (_player.value?.session?.id == session.id) {
            onOpened()
            return
        }
        openJob?.cancel()
        val generation = ++openGeneration
        fun current() = openGeneration == generation
        _opening.value = OpenState.Reading(session, 0f)
        openJob = scope.launch {
            try {
                val loaded = withContext(io) {
                    val job = currentCoroutineContext().job
                    loadSession(
                        store,
                        session,
                        checkCancelled = { job.ensureActive() },
                        writeSummary = !isRecording(session),
                    ) { progress -> if (current()) _opening.value = OpenState.Reading(session, progress) }
                }
                if (!current()) return@launch
                _player.value?.player?.pause()
                _player.value = if (clockNanos != null) {
                    PlayerController(loaded, scope, clockNanos)
                } else {
                    PlayerController(loaded, scope)
                }
                _opening.value = null
                onOpened()
                if (session.summary == null) refreshNow()
            } catch (e: CancellationException) {
                if (current()) _opening.value = null
                throw e
            } catch (e: Exception) {
                if (current()) {
                    _opening.value = OpenState.Failed(session, e.message ?: e::class.simpleName ?: "Cannot read")
                }
            } catch (e: OutOfMemoryError) {
                if (current()) {
                    _opening.value = OpenState.Failed(session, "The recording is too large to open on this device")
                }
            }
        }
    }

    /** Opens the recording [id] (e.g. the one just recorded), looking it up in the store first. */
    fun open(id: String, onOpened: () -> Unit) {
        scope.launch {
            val session = try {
                withContext(io) { store.list() }.firstOrNull { it.id == id }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: return@launch
            open(session, onOpened)
        }
    }

    /** Shows an already loaded session in the player (previews, tools); replaces an open one. */
    fun show(loaded: LoadedSession): PlayerController {
        _player.value?.player?.pause()
        val player = clockNanos?.let { PlayerController(loaded, scope, it) } ?: PlayerController(loaded, scope)
        _player.value = player
        return player
    }

    fun cancelOpen() {
        openGeneration++
        openJob?.cancel()
        openJob = null
        _opening.value = null
    }

    fun dismissOpenError() {
        if (_opening.value is OpenState.Failed) _opening.value = null
    }

    fun closePlayer() {
        _player.value?.player?.pause()
        _player.value = null
    }

    companion object {
        const val STILL_RECORDING = "This recording is still being written: stop it first"
    }
}

/**
 * What a full read of recording [id] gives for its summary (duration, feet with rows, rows, markers, late joins),
 * streamed without building a timeline; null if it has no rows. Blocking: call it off the main thread.
 * @throws IllegalArgumentException for a malformed recording.
 */
fun summarize(store: SessionStore, id: String): SessionSummary? = store.read(id) { lines, _ ->
    val rows = LongArray(Foot.entries.size)
    var first = Double.POSITIVE_INFINITY
    var last = Double.NEGATIVE_INFINITY
    val info = CsvSessionReader.stream(lines) { row ->
        rows[row.foot.ordinal]++
        if (row.tMillis < first) first = row.tMillis
        if (row.tMillis > last) last = row.tMillis
    }
    if (rows.sum() == 0L) return@read null
    SessionSummary(
        durationMillis = last - first,
        feet = Foot.entries.filter { rows[it.ordinal] > 0 }.toSet(),
        rows = rows.sum(),
        markers = info.markers.size,
        joinedMillis = info.joins.associate { it.foot to it.tMillis },
    )
}

/**
 * Reads recording [session] of [store] whole into a [LoadedSession]: timeline, gait events, overview. Blocking: call
 * it off the main thread. [checkCancelled] is called every few thousand lines (throw to stop), [onProgress] gets
 * 0..1 of the file. A recording without a summary (cut short) gets one written if [writeSummary], so the list knows
 * its duration; an existing summary is never replaced. A last line cut short (crash) is left out.
 * @throws IllegalArgumentException for a malformed recording, with the line number.
 */
fun loadSession(
    store: SessionStore,
    session: StoredSession,
    checkCancelled: () -> Unit = {},
    writeSummary: Boolean = true,
    onProgress: (Float) -> Unit = {},
): LoadedSession {
    val timeline = store.read(session.id) { lines, size ->
        val total = size.coerceAtLeast(1)
        SessionTimeline.read(
            CountingLines(lines, checkCancelled = checkCancelled) { chars ->
                onProgress((chars.toDouble() / total).toFloat().coerceIn(0f, 1f))
            },
            // The COP in the app's layout, whatever the recording was made with.
            LiveGeometry.layout,
        )
    }
    checkCancelled()
    val events = GaitEvents.detect(timeline)
    val overview = PlaybackOverview.of(timeline)
    if (writeSummary && session.summary == null) {
        // Cut short (crash, killed in the background): remember what the list could not know. Never over a summary
        // the recorder wrote meanwhile (it knows the drops; this read may have missed the file's end).
        val summary = SessionSummary(
            durationMillis = timeline.durationMillis,
            feet = timeline.feet,
            rows = Foot.entries.sumOf { timeline.track(it).size.toLong() },
            markers = timeline.markers.size,
            joinedMillis = timeline.joins,
        )
        runCatching { store.writeSummary(session.id, summary, replace = false) }
    }
    return LoadedSession(session, timeline, events, overview)
}

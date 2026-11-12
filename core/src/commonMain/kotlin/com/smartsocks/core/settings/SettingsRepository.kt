package com.smartsocks.core.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's settings as a [StateFlow], loaded from [store] when created and saved back after changes.
 *
 * Saves are debounced: one save once no [update] has come for [debounceMillis], but at the latest
 * [maxWaitMillis] after the first unsaved change (a slider dragged continuously still gets saved). Call [flush]
 * on app exit, since cancelling [scope] drops a pending save. A failed debounced save is swallowed and retried with
 * the next change or [flush]; [flush] itself throws what the store throws.
 *
 * Until the load finishes [settings] shows the defaults. [update]s made before that are not lost: they are
 * replayed on top of the loaded settings, so transforms must be pure (`copy` of their argument); one that throws
 * when replayed is skipped. [update] may be called from any thread.
 *
 * If [SettingsStore.load] fails (an I/O error, not a damaged file: decoding never fails), the stored file is
 * presumably still there and must not be overwritten with defaults. The repository then keeps the changes as
 * transforms and retries the load before every save; a debounced save is skipped while the load keeps failing,
 * only an explicit [flush] then writes what is on screen.
 */
class SettingsRepository(
    private val store: SettingsStore,
    scope: CoroutineScope,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val maxWaitMillis: Long = DEFAULT_MAX_WAIT_MILLIS,
) {
    private enum class Phase { LOADING, LOADED, LOAD_FAILED }

    /**
     * Immutable; replaced atomically by every change. [pending] holds the transforms not yet applied to settings
     * read from the store (while [phase] is not [Phase.LOADED]).
     */
    private class Snapshot(
        val settings: AppSettings,
        val phase: Phase,
        val pending: List<(AppSettings) -> AppSettings>,
    )

    private val snapshot = MutableStateFlow(Snapshot(AppSettings(), Phase.LOADING, emptyList()))
    private val _settings = MutableStateFlow(snapshot.value.settings)
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** True once the first load attempt is over (successful or not). */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Completes with true after the first load attempt, with false if [scope] ended before it. */
    private val loadResult = CompletableDeferred<Boolean>()
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val saveLock = Mutex()

    /** What the store holds as far as we know (null: unknown); guarded by [saveLock]. */
    private var persisted: AppSettings? = null

    init {
        require(debounceMillis >= 0) { "debounceMillis must not be negative" }
        require(maxWaitMillis >= debounceMillis) { "maxWaitMillis must not be shorter than debounceMillis" }
        val job = scope.launch {
            load()
            saveLoop()
        }
        // Runs even if the scope is cancelled before the coroutine starts, so awaitLoaded()/flush() never hang.
        job.invokeOnCompletion { loadResult.complete(false) }
    }

    /** Applies [transform] to the current settings and schedules a save. */
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = snapshot.updateAndGet { s ->
            val pending = if (s.phase == Phase.LOADED) s.pending else s.pending + transform
            Snapshot(transform(s.settings), s.phase, pending)
        }
        publish()
        if (next.phase != Phase.LOADING) changes.trySend(Unit)
    }

    /** Returns once the first load attempt is over, or the repository's scope ended without one. */
    suspend fun awaitLoaded() {
        loadResult.await()
    }

    /**
     * Saves now if anything changed since the last save. Waits for the load first; does nothing if it never ran
     * (scope cancelled before), since then nothing is known about the stored file.
     */
    suspend fun flush() {
        if (!loadResult.await()) return
        save(explicit = true)
    }

    private suspend fun load() {
        val stored = tryLoad()
        saveLock.withLock { persisted = stored }
        val after = if (stored != null) rebase(stored) else snapshot.updateAndGet { it.withPhase(Phase.LOAD_FAILED) }
        publish()
        _loaded.value = true
        loadResult.complete(true)
        val dirty = if (stored != null) after.settings != stored else after.pending.isNotEmpty()
        if (dirty) changes.trySend(Unit)
    }

    private suspend fun saveLoop() {
        while (true) {
            changes.receive()
            // Debounce: wait for a quiet period, but not longer than the max wait in total.
            withTimeoutOrNull(maxWaitMillis) {
                while (withTimeoutOrNull(debounceMillis) { changes.receive() } != null) Unit
            }
            try {
                save(explicit = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Kept dirty: the next change or flush() retries.
            }
        }
    }

    private suspend fun save(explicit: Boolean) = saveLock.withLock {
        if (snapshot.value.phase == Phase.LOAD_FAILED) {
            val stored = tryLoad()
            if (stored != null) {
                persisted = stored
                rebase(stored)
                publish()
            } else if (!explicit) {
                return@withLock // the file may hold settings we never saw: do not overwrite it
            }
        }
        val current = snapshot.value.settings
        if (current != persisted) {
            store.save(current)
            persisted = current
        }
        // Whatever the store held before, it now holds what we show.
        snapshot.update { if (it.phase == Phase.LOAD_FAILED) it.withPhase(Phase.LOADED, emptyList()) else it }
    }

    /** Replays the pending transforms on [stored] and marks the settings as loaded. */
    private fun rebase(stored: AppSettings): Snapshot = snapshot.updateAndGet { s ->
        if (s.phase == Phase.LOADED) {
            s
        } else {
            val settings = s.pending.fold(stored) { acc, f -> runCatching { f(acc) }.getOrDefault(acc) }
            Snapshot(settings, Phase.LOADED, emptyList())
        }
    }

    private suspend fun tryLoad(): AppSettings? = try {
        store.load()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun Snapshot.withPhase(phase: Phase, pending: List<(AppSettings) -> AppSettings> = this.pending) =
        Snapshot(settings, phase, pending)

    /**
     * Copies the snapshot's settings into [settings]. Another thread may publish an older value between our read
     * and write, so repeat until what we wrote is still current: the last publisher then always leaves the newest
     * value.
     */
    private fun publish() {
        while (true) {
            val current = snapshot.value.settings
            _settings.value = current
            if (snapshot.value.settings == current) return
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 300L
        const val DEFAULT_MAX_WAIT_MILLIS = 2_000L
    }
}

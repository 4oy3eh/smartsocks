package com.smartsocks.core.settings

import com.smartsocks.core.analytics.ProcessorSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsRepositoryTest {
    private val stored = AppSettings(theme = AppTheme.DARK, autoConnect = false)

    private fun storeWith(settings: AppSettings) = InMemorySettingsStore(SettingsCodec.encode(settings))

    private fun InMemorySettingsStore.decoded() = SettingsCodec.decode(text!!)

    /** Wraps a store with a slow load and optionally failing loads and saves. */
    private class ScriptedStore(
        val inner: InMemorySettingsStore,
        val loadDelayMillis: Long = 0,
        var failSaves: Int = 0,
        var failLoads: Int = 0,
    ) : SettingsStore {
        var loadCount = 0

        override suspend fun load(): AppSettings {
            delay(loadDelayMillis)
            loadCount++
            if (failLoads > 0) {
                failLoads--
                throw IllegalStateException("disk on fire")
            }
            return inner.load()
        }

        override suspend fun save(settings: AppSettings) {
            if (failSaves > 0) {
                failSaves--
                throw IllegalStateException("disk full")
            }
            inner.save(settings)
        }
    }

    @Test
    fun `loads the stored settings`() = runTest {
        val repo = SettingsRepository(storeWith(stored), backgroundScope)
        assertFalse(repo.loaded.value)
        assertEquals(AppSettings(), repo.settings.value, "defaults until loaded")
        runCurrent()
        assertTrue(repo.loaded.value)
        assertEquals(stored, repo.settings.value)
    }

    @Test
    fun `an empty store gives the defaults and is not written`() = runTest {
        val store = InMemorySettingsStore()
        val repo = SettingsRepository(store, backgroundScope)
        repo.awaitLoaded()
        assertEquals(AppSettings(), repo.settings.value)
        advanceTimeBy(1000)
        repo.flush()
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `a failing load gives the defaults`() = runTest {
        val repo = SettingsRepository(ScriptedStore(storeWith(stored), failLoads = 1), backgroundScope)
        repo.awaitLoaded()
        assertTrue(repo.loaded.value)
        assertEquals(AppSettings(), repo.settings.value)
    }

    @Test
    fun `update shows at once and is saved after the debounce`() = runTest {
        val store = storeWith(stored)
        val repo = SettingsRepository(store, backgroundScope)
        runCurrent()
        repo.update { it.copy(liveView = LiveView.CHART) }
        assertEquals(LiveView.CHART, repo.settings.value.liveView)
        advanceTimeBy(299)
        assertEquals(0, store.saveCount)
        advanceTimeBy(2)
        assertEquals(1, store.saveCount)
        assertEquals(stored.copy(liveView = LiveView.CHART), store.decoded())
    }

    @Test
    fun `quick successive updates are saved once`() = runTest {
        val store = storeWith(stored)
        val repo = SettingsRepository(store, backgroundScope)
        runCurrent()
        repo.update { it.copy(theme = AppTheme.LIGHT) }
        advanceTimeBy(200)
        repo.update { it.copy(autoConnect = true) }
        advanceTimeBy(200)
        repo.update { it.copy(liveView = LiveView.NUMBERS) }
        advanceTimeBy(299)
        assertEquals(0, store.saveCount, "still within 300 ms of the last change")
        advanceTimeBy(2)
        assertEquals(1, store.saveCount)
        assertEquals(AppSettings(theme = AppTheme.LIGHT, liveView = LiveView.NUMBERS), store.decoded())
        advanceTimeBy(5000)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `an update back to the saved value is not written`() = runTest {
        val store = storeWith(stored)
        val repo = SettingsRepository(store, backgroundScope)
        runCurrent()
        repo.update { it.copy(theme = AppTheme.LIGHT) }
        repo.update { it.copy(theme = AppTheme.DARK) }
        advanceTimeBy(1000)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `flush saves at once and the pending debounce does not save again`() = runTest {
        val store = storeWith(stored)
        val repo = SettingsRepository(store, backgroundScope)
        runCurrent()
        repo.update { it.copy(theme = AppTheme.SYSTEM) }
        repo.flush()
        assertEquals(1, store.saveCount)
        assertEquals(AppTheme.SYSTEM, store.decoded().theme)
        advanceTimeBy(1000)
        assertEquals(1, store.saveCount)
        repo.flush()
        assertEquals(1, store.saveCount, "nothing new to flush")
    }

    @Test
    fun `updates before the load are replayed on top of it`() = runTest {
        val inner = storeWith(stored)
        val repo = SettingsRepository(ScriptedStore(inner, loadDelayMillis = 100), backgroundScope)
        runCurrent()
        repo.update { it.copy(liveView = LiveView.CHART) }
        assertEquals(AppSettings(liveView = LiveView.CHART), repo.settings.value, "applied to the defaults for now")

        advanceTimeBy(101)
        assertEquals(stored.copy(liveView = LiveView.CHART), repo.settings.value)
        assertEquals(0, inner.saveCount, "nothing saved before the load")
        advanceTimeBy(300)
        assertEquals(1, inner.saveCount)
        assertEquals(stored.copy(liveView = LiveView.CHART), inner.decoded())
    }

    @Test
    fun `flush before the load waits for it`() = runTest {
        val inner = storeWith(stored)
        val repo = SettingsRepository(ScriptedStore(inner, loadDelayMillis = 100), backgroundScope)
        repo.update { it.copy(autoConnect = true) }
        repo.flush()
        assertEquals(100, testScheduler.currentTime)
        assertEquals(stored.copy(autoConnect = true), inner.decoded())
    }

    @Test
    fun `a failed debounced save is retried by the next change and flush rethrows`() = runTest {
        val inner = storeWith(stored)
        val store = ScriptedStore(inner, failSaves = 1)
        val repo = SettingsRepository(store, backgroundScope)
        runCurrent()
        repo.update { it.copy(theme = AppTheme.LIGHT) }
        advanceTimeBy(400)
        assertEquals(0, inner.saveCount, "first save failed")

        repo.update { it.copy(liveView = LiveView.NUMBERS) }
        advanceTimeBy(400)
        assertEquals(1, inner.saveCount)
        assertEquals(stored.copy(theme = AppTheme.LIGHT, liveView = LiveView.NUMBERS), inner.decoded())

        store.failSaves = 1
        repo.update { it.copy(autoConnect = true) }
        assertFailsWith<IllegalStateException> { repo.flush() }
        repo.flush()
        assertEquals(2, inner.saveCount)
    }

    @Test
    fun `a scope cancelled before the repository starts does not hang awaitLoaded or flush`() = runTest {
        val dead = CoroutineScope(Job().apply { cancel() })
        val store = storeWith(stored)
        val repo = SettingsRepository(store, dead)
        withTimeout(1_000) {
            repo.awaitLoaded()
            repo.update { it.copy(theme = AppTheme.LIGHT) }
            repo.flush()
        }
        assertFalse(repo.loaded.value)
        assertEquals(0, store.saveCount, "nothing is known about the file: it is left alone")
    }

    @Test
    fun `a failed load is retried before the first save so the stored devices survive`() = runTest {
        val file = AppSettings(left = DeviceChoice.SerialPort("COM7"), right = DeviceChoice.Bluetooth("AA:BB", "R"))
        val inner = storeWith(file)
        val store = ScriptedStore(inner, failLoads = 1)
        val repo = SettingsRepository(store, backgroundScope)
        repo.awaitLoaded()
        assertEquals(AppSettings(), repo.settings.value, "defaults while the file cannot be read")

        repo.update { it.copy(theme = AppTheme.LIGHT) }
        advanceTimeBy(400)
        assertEquals(2, store.loadCount)
        assertEquals(1, inner.saveCount)
        assertEquals(file.copy(theme = AppTheme.LIGHT), inner.decoded())
        assertEquals(file.copy(theme = AppTheme.LIGHT), repo.settings.value, "the real settings show up")
    }

    @Test
    fun `while the load keeps failing only flush writes`() = runTest {
        val inner = storeWith(AppSettings(left = DeviceChoice.SerialPort("COM7")))
        val store = ScriptedStore(inner, failLoads = 100)
        val repo = SettingsRepository(store, backgroundScope)
        repo.awaitLoaded()
        repo.update { it.copy(liveView = LiveView.CHART) }
        advanceTimeBy(5_000)
        assertEquals(0, inner.saveCount, "the debounced save must not overwrite an unreadable file")

        repo.flush()
        assertEquals(1, inner.saveCount)
        assertEquals(AppSettings(liveView = LiveView.CHART), inner.decoded())
        // From now on the store holds our settings: normal debounced saves again.
        repo.update { it.copy(autoConnect = false) }
        advanceTimeBy(400)
        assertEquals(2, inner.saveCount)
    }

    @Test
    fun `a pending transform that throws on the loaded settings is skipped`() = runTest {
        val inner = storeWith(stored)
        val repo = SettingsRepository(ScriptedStore(inner, loadDelayMillis = 100), backgroundScope)
        runCurrent()
        repo.update { if (it.theme == AppTheme.DARK) error("not on dark") else it.copy(autoConnect = true) }
        repo.update { it.copy(liveView = LiveView.NUMBERS) }
        advanceTimeBy(101)
        assertEquals(stored.copy(liveView = LiveView.NUMBERS), repo.settings.value)

        repo.update { it.copy(theme = AppTheme.LIGHT) }
        advanceTimeBy(400)
        assertEquals(stored.copy(liveView = LiveView.NUMBERS, theme = AppTheme.LIGHT), inner.decoded())
    }

    @Test
    fun `continuous changes are saved at least every max wait`() = runTest {
        val store = InMemorySettingsStore()
        val repo = SettingsRepository(store, backgroundScope)
        runCurrent()
        var beta = 0.0
        repeat(50) {
            beta += 0.01
            val value = beta
            repo.update { it.copy(processor = it.processor.copy(copBeta = value)) }
            advanceTimeBy(100) // a slider drag: never 300 ms of quiet
        }
        assertEquals(2, store.saveCount, "saved after 2 s and 4 s of dragging")
        assertTrue(store.decoded().processor.copBeta > 0.35)
        advanceTimeBy(400)
        assertEquals(3, store.saveCount)
        assertEquals(beta, store.decoded().processor.copBeta, 1e-9)
    }

    @Test
    fun `updates from many threads leave the newest settings published`() = runTest {
        val repo = SettingsRepository(InMemorySettingsStore(), backgroundScope)
        runCurrent()
        val bump: (AppSettings) -> AppSettings = {
            it.copy(processor = it.processor.copy(copBeta = it.processor.copBeta + 1))
        }
        repeat(20) {
            withContext(Dispatchers.Default) {
                coroutineScope {
                    repeat(4) {
                        launch {
                            repeat(500) { repo.update(bump) }
                        }
                    }
                }
            }
        }
        assertEquals(ProcessorSettings().copBeta + 20 * 4 * 500, repo.settings.value.processor.copBeta, 1e-6)
    }
}

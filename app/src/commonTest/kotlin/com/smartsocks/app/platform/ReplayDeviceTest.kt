package com.smartsocks.app.platform

import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.ReplayFileUsers
import com.smartsocks.app.live.noJitter
import com.smartsocks.app.playback.SessionsController
import com.smartsocks.app.recording.MemorySessionStore
import com.smartsocks.app.recording.SessionSummary
import com.smartsocks.app.settings.DeviceOptions
import com.smartsocks.app.settings.deviceInUseByOtherFoot
import com.smartsocks.app.settings.identity
import com.smartsocks.app.settings.isSameDeviceAs
import com.smartsocks.app.settings.withDeviceIfFree
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.ReplaySockTransport
import com.smartsocks.core.sim.SimulatedRecording
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Replaying recordings as socks: the catalog, the device factory, conflicts, and the live controller on replays. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReplayDeviceTest {
    private val recorded = StreamSettings(SampleRate.HZ_50, MeasurementRange.K128)

    /** A simulated recording of [feet] in [this]; returns its id. */
    private fun MemorySessionStore.record(
        seconds: Double = 2.0,
        feet: Set<Foot> = Foot.entries.toSet(),
        raw: Boolean = true,
        joinAt: Map<Foot, Double> = emptyMap(),
    ): String {
        val rec = create(saveRaw = raw)
        SimulatedRecording.write(
            rec.csv,
            rec.raw,
            seconds,
            recorded.rate,
            recorded.range,
            feet = feet,
            joinAtSeconds = joinAt,
        )
        return rec.id
    }

    @Test
    fun `the catalog lists the recordings that have raw packets, with their feet`() {
        val store = MemorySessionStore()
        val both = store.record()
        store.record(raw = false)
        val left = store.record(feet = setOf(Foot.LEFT))
        val catalog = DeviceCatalog.SimulatorOnly.withReplay(store)
        assertEquals(listOf(DeviceKind.Simulator, DeviceKind.Replay), catalog.kinds)
        val entries = catalog.replaySessions()
        assertEquals(listOf(left, both), entries.map { it.id }, "newest first, without the one lacking raw packets")
        assertEquals(setOf(Foot.LEFT), entries[0].feet)
        assertEquals(Foot.entries.toSet(), entries[1].feet)
        assertEquals(recorded, entries[1].stream)
        assertEquals(DeviceCatalog.SimulatorOnly.replaySessions(), emptyList())
    }

    @Test
    fun `the factory builds a replay at the recorded settings, or says why not`() {
        val store = MemorySessionStore()
        val id = store.record(feet = setOf(Foot.LEFT))
        store.summaries[id] = SessionSummary(2_000.0, setOf(Foot.LEFT), 100, 0)
        val noRaw = store.record(raw = false)
        val devices = DeviceFactory.withSimulator(sessions = store) { _, c -> unsupportedOnThisPlatform(c, "Test") }

        // Either foot may play the recording's left foot.
        val ready = assertIs<DeviceResult.Ready>(devices.create(Foot.RIGHT, DeviceChoice.Replay(id, Foot.LEFT)))
        val transport = assertIs<ReplaySockTransport>(ready.transport)
        assertEquals(Foot.LEFT, transport.sourceFoot)
        assertEquals(recorded, ready.stream)
        assertFalse(ready.reconnects)
        assertNull(ready.simulator)
        assertNull(devices.check(Foot.RIGHT, DeviceChoice.Replay(id, Foot.LEFT)))

        fun problem(choice: DeviceChoice.Replay) = assertIs<DeviceResult.Unavailable>(devices.create(Foot.LEFT, choice))
        assertTrue("no right foot" in problem(DeviceChoice.Replay(id, Foot.RIGHT)).message)
        assertTrue("no raw packets" in problem(DeviceChoice.Replay(noRaw, Foot.LEFT)).message)
        assertTrue("is gone" in problem(DeviceChoice.Replay("nope", Foot.LEFT)).message)
        assertNotNull(devices.check(Foot.LEFT, DeviceChoice.Replay("nope", Foot.LEFT)))
        store.delete(id)
        assertNotNull(devices.check(Foot.RIGHT, DeviceChoice.Replay(id, Foot.LEFT)), "checked again before a connect")

        // A simulator-only factory has no recordings.
        val plain = DeviceFactory.withSimulator { _, c -> unsupportedOnThisPlatform(c, "Test") }
        assertIs<DeviceResult.Unavailable>(plain.create(Foot.LEFT, DeviceChoice.Replay(noRaw, Foot.LEFT)))
    }

    @Test
    fun `the same foot of the same recording is one device, the other foot is not`() {
        val l = DeviceChoice.Replay("rec", Foot.LEFT)
        val r = DeviceChoice.Replay("rec", Foot.RIGHT)
        assertFalse(l.isSameDeviceAs(r))
        assertTrue(l.isSameDeviceAs(l.copy(loop = false)))
        assertFalse(l.isSameDeviceAs(DeviceChoice.Replay("other", Foot.LEFT)))
        assertNotEquals(l.identity, r.identity)

        assertFalse(AppSettings(left = l, right = r).deviceInUseByOtherFoot(Foot.RIGHT))
        assertTrue(AppSettings(left = l, right = l).deviceInUseByOtherFoot(Foot.RIGHT))
        val settings = AppSettings(left = l)
        assertEquals(settings, settings.withDeviceIfFree(Foot.RIGHT, l.copy(loop = false)))
        assertEquals(r, settings.withDeviceIfFree(Foot.RIGHT, r).right)
    }

    @Test
    fun `replay options play the same foot where the recording has it`() {
        val entries = listOf(
            ReplaySessionEntry("both", "Both", Foot.entries.toSet(), 65_000.0),
            ReplaySessionEntry("left", "Left only", setOf(Foot.LEFT), 8_000.0),
        )
        val settings = AppSettings(left = DeviceChoice.Replay("left", Foot.LEFT, loop = false))
        val right = DeviceOptions.replays(entries, Foot.RIGHT, settings)
        assertEquals(listOf("both", "left"), right.map { (it.choice as DeviceChoice.Replay).sessionId })
        assertEquals(DeviceChoice.Replay("both", Foot.RIGHT), right[0].choice)
        assertEquals("Both feet · 1:05", right[0].detail)
        // The left-only recording plays its left foot, which the left foot already plays.
        assertEquals(DeviceChoice.Replay("left", Foot.LEFT), right[1].choice)
        assertTrue(right[1].usedByOtherFoot)
        assertEquals(right[0], DeviceOptions.suggestion(right))

        val left = DeviceOptions.replays(entries, Foot.LEFT, settings)
        assertTrue(left[1].selected && !left[1].usedByOtherFoot)
        assertEquals(DeviceChoice.Replay("both", Foot.LEFT, loop = false), left[0].choice, "keeps the loop setting")

        // A remembered recording that is gone is shown first, as missing.
        val gone = DeviceOptions.replays(entries, Foot.LEFT, AppSettings(left = DeviceChoice.Replay("old", Foot.LEFT)))
        assertEquals(3, gone.size)
        assertTrue(gone[0].selected && !gone[0].present)
    }

    private class Fixture(
        val store: MemorySessionStore,
        val settings: SettingsRepository,
        val controller: LiveController,
        val created: List<ReplaySockTransport>,
    )

    private fun TestScope.fixture(store: MemorySessionStore, initial: AppSettings): Fixture {
        // No real clock: the replays run on virtual time.
        val clocks = ReplayClocks { testScheduler.currentTime * 1_000_000 }
        val created = mutableListOf<ReplaySockTransport>()
        val devices = object : DeviceFactory {
            override fun create(foot: Foot, choice: DeviceChoice): DeviceResult = when (choice) {
                is DeviceChoice.Replay -> replayDevice(choice, store, clocks.of(choice.sessionId)).also {
                    (it as? DeviceResult.Ready)?.replay?.let(created::add)
                }

                else -> unsupportedOnThisPlatform(choice, "Test")
            }

            override fun check(foot: Foot, choice: DeviceChoice): DeviceResult.Unavailable? =
                (choice as? DeviceChoice.Replay)?.let { replayProblem(it, store.find(it.sessionId)) }
        }
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial)), backgroundScope)
        val controller = LiveController(settings, devices, backgroundScope, reconnectDelayMillis = noJitter) {
            testScheduler.currentTime * 1_000_000
        }
        return Fixture(store, settings, controller, created)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `both feet replay one recording at its settings, whatever the app's are`() = runTest {
        val store = MemorySessionStore()
        val id = store.record(seconds = 10.0)
        val f = fixture(
            store,
            AppSettings(
                left = DeviceChoice.Replay(id, Foot.LEFT),
                right = DeviceChoice.Replay(id, Foot.RIGHT),
                stream = StreamSettings(SampleRate.HZ_200, MeasurementRange.K64),
            ),
        )
        advance(1_500)
        for (foot in Foot.entries) {
            val link = f.controller.link(foot)
            assertEquals(LinkState.Streaming, link.status.value.state, "$foot")
            val session = assertNotNull(link.session.value)
            assertEquals(recorded, session.settings, "read at the recorded rate and range")
            assertNotNull(link.latest)
            assertTrue(link.status.value.deviceName.orEmpty().startsWith("Replay · "))
        }
        assertEquals(50, f.controller.left.status.value.rate.nominalHz)
        assertFalse(f.controller.left.status.value.rate.rateMismatch)
    }

    @Test
    fun `a replay that ends stays down, one that loops goes on`() = runTest {
        val store = MemorySessionStore()
        val id = store.record(seconds = 2.0)
        val f = fixture(
            store,
            AppSettings(
                left = DeviceChoice.Replay(id, Foot.LEFT, loop = false),
                right = DeviceChoice.Replay(id, Foot.RIGHT, loop = true),
                autoReconnect = true,
            ),
        )
        advance(1_000)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        advance(10_000)
        val left = f.controller.left.status.value
        assertEquals(LinkState.Disconnected("Link closed"), left.state, "no reconnect after the end")
        assertFalse(left.wanted)
        assertEquals(LinkState.Streaming, f.controller.right.status.value.state)
        assertEquals(1, store.openRawCount, "only the looping foot holds its dump open")

        // Connecting again plays where the other foot is (one clock per recording).
        f.controller.left.connect()
        advance(300)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        val ended = f.created.single { it.sourceFoot == Foot.LEFT }
        val looping = f.created.single { it.sourceFoot == Foot.RIGHT }
        assertTrue(ended.packetsSkipped > 0, "sought into the running pass")
        assertTrue(looping.loops > 0)
    }

    @Test
    fun `changing the stream settings or the loop does not reopen a replay`() = runTest {
        val store = MemorySessionStore()
        val id = store.record(seconds = 2.0)
        val f = fixture(store, AppSettings(left = DeviceChoice.Replay(id, Foot.LEFT), right = DeviceChoice.None))
        advance(500)
        val session = assertNotNull(f.controller.left.session.value)
        f.settings.update { it.copy(stream = StreamSettings(SampleRate.HZ_200, MeasurementRange.K256)) }
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Replay(id, Foot.LEFT, loop = false)) }
        advance(100)
        assertSame(session, f.controller.left.session.value)
        assertEquals(1, f.created.size)
        assertFalse(f.created.single().loop, "the loop setting reached the running replay")
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        // Another foot of the recording is another device.
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Replay(id, Foot.RIGHT)) }
        advance(500)
        assertEquals(2, f.created.size)
    }

    @Test
    fun `a foot that joined a recording cut short before its summary can be replayed`() = runTest {
        val store = MemorySessionStore()
        // The header names the left foot only; without a summary nobody knows the right one joined.
        val id = store.record(seconds = 3.0, joinAt = mapOf(Foot.RIGHT to 1.0))
        val choice = DeviceChoice.Replay(id, Foot.RIGHT)
        assertEquals(setOf(Foot.LEFT), store.find(id)!!.feet)
        assertNull(replayProblem(choice, store.find(id)))
        val entry = DeviceCatalog.SimulatorOnly.withReplay(store).replaySessions().single()
        assertFalse(entry.feetKnown)
        assertEquals(choice, DeviceOptions.replays(listOf(entry), Foot.RIGHT, AppSettings()).single().choice)

        val f = fixture(store, AppSettings(left = DeviceChoice.None, right = choice))
        advance(1_500)
        assertEquals(LinkState.Streaming, f.controller.right.status.value.state)
        assertNotNull(f.controller.right.latest)

        // With a summary that does not list the foot, it is refused before connecting.
        store.summaries[id] = SessionSummary(3_000.0, setOf(Foot.LEFT), 150, 0)
        assertNotNull(replayProblem(choice, store.find(id)))
    }

    @Test
    fun `deleting or renaming a replayed recording lets go of it first`() = runTest {
        val store = MemorySessionStore()
        val id = store.record(seconds = 2.0)
        val f = fixture(
            store,
            AppSettings(
                left = DeviceChoice.Replay(id, Foot.LEFT),
                right = DeviceChoice.Replay(id, Foot.RIGHT, loop = false),
            ),
        )
        advance(500)
        assertEquals(2, store.openRawCount)
        val users = ReplayFileUsers(f.controller, f.settings)
        val sessions = SessionsController(
            store,
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            fileUsers = users,
        )
        val stored = store.find(id)!!
        val rename = async { sessions.rename(stored, "walk") }
        advance(1_000)
        assertNull(rename.await())
        assertEquals(0, store.openRawCount, "both replays closed before the rename")
        assertEquals(DeviceChoice.Replay("walk", Foot.LEFT), f.settings.settings.value.left)
        assertEquals(DeviceChoice.Replay("walk", Foot.RIGHT, loop = false), f.settings.settings.value.right)
        for (foot in Foot.entries) assertFalse(f.controller.link(foot).wantsConnection)

        f.controller.left.connect()
        advance(500)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state, "replays the renamed recording")
        sessions.delete(store.find("walk")!!)
        advance(1_000)
        assertNull(store.find("walk"))
        assertEquals(0, store.openRawCount)
        assertFalse(f.controller.left.wantsConnection)
    }

    @Test
    fun `the same foot of one recording on both feet is a conflict`() = runTest {
        val store = MemorySessionStore()
        val id = store.record()
        val f = fixture(
            store,
            AppSettings(left = DeviceChoice.Replay(id, Foot.LEFT), right = DeviceChoice.Replay(id, Foot.LEFT)),
        )
        advance(500)
        assertEquals(LinkState.Streaming, f.controller.left.status.value.state)
        val right = assertIs<LinkState.Unavailable>(f.controller.right.status.value.state)
        assertEquals(UnavailableReason.InUse, right.reason)
    }
}

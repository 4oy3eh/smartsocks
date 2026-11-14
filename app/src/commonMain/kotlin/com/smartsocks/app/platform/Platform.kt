package com.smartsocks.app.platform

import com.smartsocks.app.live.LiveGeometry
import com.smartsocks.app.recording.SessionNames
import com.smartsocks.app.recording.SessionStore
import com.smartsocks.app.recording.StoredSession
import com.smartsocks.core.model.Foot
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SettingsStore
import com.smartsocks.core.sim.ReplayClock
import com.smartsocks.core.sim.ReplayOptions
import com.smartsocks.core.sim.ReplaySockTransport
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.sim.preloadedInsole
import com.smartsocks.core.transport.SockTransport
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/** Platform services, created by the launcher (desktop `main`, Android activity) and handed to the shared app. */
class Platform(
    val settingsStore: SettingsStore,
    val devices: DeviceFactory,
    val catalog: DeviceCatalog,
    val sessionStore: SessionStore,
    val sessionActions: SessionActions = SessionActions.None,
)

/** Why a device cannot be used right now; lets the UI offer the fix. */
enum class UnavailableReason {
    /** No transport for it on this platform, or it could not be created: nothing to fix here but the choice. */
    Unsupported,

    /** The Bluetooth permission is not granted; asking for it fixes this. */
    PermissionNeeded,

    /** Bluetooth is switched off. */
    BluetoothOff,

    /** The other foot already uses this physical device. */
    InUse,
    ;

    /**
     * Fixable without choosing another device (grant the permission, switch Bluetooth on): a pending connect is kept
     * and `LiveController.recheckDevices` tries again.
     */
    val recoverable: Boolean get() = this == PermissionNeeded || this == BluetoothOff
}

/** What [DeviceFactory.create] gives back: a transport ready to open, or why this device cannot be used here. */
sealed interface DeviceResult {
    /**
     * [simulator]: the simulator behind [transport], if it is one (also when wrapped), so that its mode can be
     * switched live.
     */
    class Ready(
        val transport: SockTransport,
        val simulator: SimulatedSockTransport? = transport as? SimulatedSockTransport,
        /**
         * The device streams at these settings whatever the app asks for, so the session is built with them instead
         * of the app's: a replay's recorded ones (its packets only convert right at the recorded range).
         */
        val stream: StreamSettings? = (transport as? ReplaySockTransport)?.recorded,
        /** A link that ends is retried (with auto-reconnect on); not a replay's: its end is final. */
        val reconnects: Boolean = transport !is ReplaySockTransport,
        /** The replay behind [transport], if it is one, so that its loop setting can be switched live. */
        val replay: ReplaySockTransport? = transport as? ReplaySockTransport,
    ) : DeviceResult

    data class Unavailable(val message: String, val reason: UnavailableReason = UnavailableReason.Unsupported) :
        DeviceResult
}

/** Builds the transport for a remembered [DeviceChoice]. Must not throw; must not open the transport. */
fun interface DeviceFactory {
    fun create(foot: Foot, choice: DeviceChoice): DeviceResult

    /**
     * Checked right before every connect: why [choice] cannot be connected now (Bluetooth switched off or the
     * permission revoked since [create]), or null if nothing speaks against it. Must be quick and not throw.
     */
    fun check(foot: Foot, choice: DeviceChoice): DeviceResult.Unavailable? = null

    companion object {
        /**
         * The simulator on every platform; [hardware] handles the real devices this platform supports and returns
         * [DeviceResult.Unavailable] for the others. [check] is consulted for real devices before [hardware] and
         * before every connect. [simulatorConfig] adjusts every simulator's config (development: a simulated
         * link that drops, see `SMARTSOCKS_SIM_DROP_AFTER` in the desktop app). [sessions]: where the recordings
         * for [DeviceChoice.Replay] are; without it a replay is unsupported. The replays of one recording share one
         * [ReplayClock] (made here, one per recording), so both feet play it in step.
         */
        fun withSimulator(
            check: (DeviceChoice) -> DeviceResult.Unavailable? = { null },
            simulatorConfig: (SimConfig) -> SimConfig = { it },
            sessions: SessionStore? = null,
            hardware: (Foot, DeviceChoice) -> DeviceResult,
        ): DeviceFactory {
            val clock = TimeSource.Monotonic.markNow()
            val clockNanos = { clock.elapsedNow().inWholeNanoseconds }
            val replayClocks = ReplayClocks(clockNanos)
            return object : DeviceFactory {
                override fun create(foot: Foot, choice: DeviceChoice): DeviceResult = when (choice) {
                    DeviceChoice.None -> DeviceResult.Unavailable("No device selected")

                    is DeviceChoice.Simulator ->
                        DeviceResult.Ready(simulatorTransport(foot, choice, clockNanos, simulatorConfig))

                    is DeviceChoice.Replay -> if (sessions != null) {
                        replayDevice(choice, sessions, replayClocks.of(choice.sessionId))
                    } else {
                        hardware(foot, choice)
                    }

                    else -> check(choice) ?: hardware(foot, choice)
                }

                override fun check(foot: Foot, choice: DeviceChoice): DeviceResult.Unavailable? = when (choice) {
                    DeviceChoice.None, is DeviceChoice.Simulator -> null
                    is DeviceChoice.Replay -> sessions?.let { replayProblem(choice, findSession(it, choice.sessionId)) }
                    else -> check(choice)
                }
            }
        }
    }
}

/**
 * The simulator for [choice]. Give it a real [clockNanos] in the app so it keeps its rate in real time; without one
 * it runs on `delay` alone (deterministic under virtual time, for tests).
 */
fun simulatorTransport(
    foot: Foot,
    choice: DeviceChoice.Simulator,
    clockNanos: (() -> Long)? = null,
    adjust: (SimConfig) -> SimConfig = { it },
): SimulatedSockTransport {
    // The app's layout, so the simulated forces land where the session's processor and the views place the sensors.
    val config = SimConfig(mode = choice.mode, layout = LiveGeometry.layout)
    return SimulatedSockTransport(foot, adjust(if (choice.preloaded) config.preloadedInsole() else config), clockNanos)
}

/** One [ReplayClock] per recording, made on first use. Thread-safe. */
@OptIn(ExperimentalAtomicApi::class)
class ReplayClocks(private val clockNanos: () -> Long) {
    private val clocks = AtomicReference<Map<String, ReplayClock>>(emptyMap())

    fun of(sessionId: String): ReplayClock {
        while (true) {
            val current = clocks.load()
            current[sessionId]?.let { return it }
            val clock = ReplayClock(clockNanos = clockNanos)
            if (clocks.compareAndSet(current, current + (sessionId to clock))) return clock
        }
    }
}

/**
 * The replay for [choice] from [store], or why there is none: the recording is gone, has no raw packets or no data
 * of the foot to play, or its header lacks the stream settings. Reads the recording's header (brief, blocking). The
 * session is built with the recorded stream settings ([DeviceResult.Ready.stream]). [clock]: the one of every
 * replay of this recording (see [ReplayClocks]).
 */
fun replayDevice(choice: DeviceChoice.Replay, store: SessionStore, clock: ReplayClock): DeviceResult {
    val session = findSession(store, choice.sessionId)
    replayProblem(choice, session)?.let { return it }
    val stream = session!!.header.stream
        ?: return DeviceResult.Unavailable("The recording does not say its sample rate and range")
    val transport = ReplaySockTransport(
        choice.foot,
        stream,
        { store.openRaw(choice.sessionId) },
        clock,
        ReplayOptions(loop = choice.loop),
        displayName = replayName(choice),
    )
    return DeviceResult.Ready(transport)
}

/** Short, for the live screen: "Replay · 08:09:48 · L". */
fun replayName(choice: DeviceChoice.Replay): String =
    "Replay · ${SessionNames.short(choice.sessionId)} · ${if (choice.foot == Foot.LEFT) "L" else "R"}"

private fun findSession(store: SessionStore, id: String): StoredSession? = try {
    store.find(id)
} catch (_: Exception) {
    null
}

/** Why [session] (the one [choice] names, null if it is gone) cannot be replayed, or null if it can. */
internal fun replayProblem(choice: DeviceChoice.Replay, session: StoredSession?): DeviceResult.Unavailable? {
    val name = SessionNames.display(choice.sessionId)
    return when {
        session == null -> DeviceResult.Unavailable("The recording $name is gone")

        !session.hasRaw -> DeviceResult.Unavailable("The recording $name has no raw packets to replay")

        // Known only from a summary: a file cut short by a crash lists the feet of its start, not those that joined
        // (opening the replay finds out).
        session.summary != null && choice.foot !in session.feet ->
            DeviceResult.Unavailable("The recording $name has no ${choice.foot.name.lowercase()} foot")

        else -> null
    }
}

/** The standard message for a device kind this platform has no transport for. */
fun unsupportedOnThisPlatform(choice: DeviceChoice, platformName: String): DeviceResult.Unavailable {
    val kind = when (choice) {
        is DeviceChoice.SerialPort -> "Serial port ${choice.portName}"
        is DeviceChoice.Bluetooth -> "Bluetooth device ${choice.name.ifBlank { choice.address }}"
        is DeviceChoice.Simulator -> "Simulator"
        is DeviceChoice.Replay -> "Replay"
        DeviceChoice.None -> "No device"
    }
    return DeviceResult.Unavailable("$kind is not supported on $platformName")
}

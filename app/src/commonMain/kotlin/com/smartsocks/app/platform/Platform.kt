package com.smartsocks.app.platform

import com.smartsocks.app.live.LiveGeometry
import com.smartsocks.app.recording.SessionStore
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SettingsStore
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.sim.preloadedInsole
import com.smartsocks.core.transport.SockTransport
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
         * link that drops, see `SMARTSOCKS_SIM_DROP_AFTER` in the desktop app).
         */
        fun withSimulator(
            check: (DeviceChoice) -> DeviceResult.Unavailable? = { null },
            simulatorConfig: (SimConfig) -> SimConfig = { it },
            hardware: (Foot, DeviceChoice) -> DeviceResult,
        ): DeviceFactory {
            val clock = TimeSource.Monotonic.markNow()
            val clockNanos = { clock.elapsedNow().inWholeNanoseconds }
            return object : DeviceFactory {
                override fun create(foot: Foot, choice: DeviceChoice): DeviceResult = when (choice) {
                    DeviceChoice.None -> DeviceResult.Unavailable("No device selected")

                    is DeviceChoice.Simulator ->
                        DeviceResult.Ready(simulatorTransport(foot, choice, clockNanos, simulatorConfig))

                    else -> check(choice) ?: hardware(foot, choice)
                }

                override fun check(foot: Foot, choice: DeviceChoice): DeviceResult.Unavailable? = when (choice) {
                    DeviceChoice.None, is DeviceChoice.Simulator -> null
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

/** The standard message for a device kind this platform has no transport for. */
fun unsupportedOnThisPlatform(choice: DeviceChoice, platformName: String): DeviceResult.Unavailable {
    val kind = when (choice) {
        is DeviceChoice.SerialPort -> "Serial port ${choice.portName}"
        is DeviceChoice.Bluetooth -> "Bluetooth device ${choice.name.ifBlank { choice.address }}"
        is DeviceChoice.Simulator -> "Simulator"
        DeviceChoice.None -> "No device"
    }
    return DeviceResult.Unavailable("$kind is not supported on $platformName")
}

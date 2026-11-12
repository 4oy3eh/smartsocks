package com.smartsocks.app.platform

import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SettingsStore
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.sim.preloadedInsole
import com.smartsocks.core.transport.SockTransport
import kotlin.time.TimeSource

/** Platform services, created by the launcher (desktop `main`, Android activity) and handed to the shared app. */
class Platform(val settingsStore: SettingsStore, val devices: DeviceFactory)

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

    data class Unavailable(val message: String) : DeviceResult
}

/** Builds the transport for a remembered [DeviceChoice]. Must not throw; must not open the transport. */
fun interface DeviceFactory {
    fun create(foot: Foot, choice: DeviceChoice): DeviceResult

    companion object {
        /**
         * The simulator on every platform; [hardware] handles the real devices this platform supports and returns
         * [DeviceResult.Unavailable] for the others.
         */
        fun withSimulator(hardware: (Foot, DeviceChoice) -> DeviceResult): DeviceFactory {
            val clock = TimeSource.Monotonic.markNow()
            val clockNanos = { clock.elapsedNow().inWholeNanoseconds }
            return DeviceFactory { foot, choice -> create(foot, choice, clockNanos, hardware) }
        }

        private fun create(
            foot: Foot,
            choice: DeviceChoice,
            clockNanos: () -> Long,
            hardware: (Foot, DeviceChoice) -> DeviceResult,
        ): DeviceResult = when (choice) {
            DeviceChoice.None -> DeviceResult.Unavailable("No device selected")
            is DeviceChoice.Simulator -> DeviceResult.Ready(simulatorTransport(foot, choice, clockNanos))
            else -> hardware(foot, choice)
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
): SimulatedSockTransport {
    val config = SimConfig(mode = choice.mode)
    return SimulatedSockTransport(foot, if (choice.preloaded) config.preloadedInsole() else config, clockNanos)
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

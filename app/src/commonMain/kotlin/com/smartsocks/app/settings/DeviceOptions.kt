package com.smartsocks.app.settings

import com.smartsocks.app.platform.BluetoothDeviceEntry
import com.smartsocks.app.platform.SerialPortEntry
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice

val Foot.other: Foot get() = if (this == Foot.LEFT) Foot.RIGHT else Foot.LEFT

val Foot.title: String get() = if (this == Foot.LEFT) "Left foot" else "Right foot"

/**
 * Both choices name the same physical sock: the same COM port (Windows port names ignore case) or the same
 * Bluetooth address. The simulator and "none" never clash: every foot gets its own simulator.
 */
fun DeviceChoice.isSameDeviceAs(other: DeviceChoice): Boolean = when (this) {
    is DeviceChoice.SerialPort -> other is DeviceChoice.SerialPort && portName.equals(other.portName, ignoreCase = true)
    is DeviceChoice.Bluetooth -> other is DeviceChoice.Bluetooth && address.equals(other.address, ignoreCase = true)
    DeviceChoice.None, is DeviceChoice.Simulator -> false
}

/**
 * Which physical insole this is, for remembering things about it (its calibration): the port or address in upper
 * case, never the display name; for the simulator only what changes its readings (preloaded or not).
 */
val DeviceChoice.identity: String
    get() = when (this) {
        DeviceChoice.None -> "none"
        is DeviceChoice.Simulator -> "simulator:$preloaded"
        is DeviceChoice.SerialPort -> "serial:${portName.uppercase()}"
        is DeviceChoice.Bluetooth -> "bluetooth:${address.uppercase()}"
    }

/** [foot]'s device is also the other foot's. The left foot keeps it; the right one is the one in conflict. */
fun AppSettings.deviceInUseByOtherFoot(foot: Foot): Boolean = device(foot).isSameDeviceAs(device(foot.other))

/**
 * Sets [foot]'s device unless the other foot already uses that sock; then the settings stay as they are. Pure, so
 * it can be passed to `SettingsRepository.update`.
 */
fun AppSettings.withDeviceIfFree(foot: Foot, choice: DeviceChoice): AppSettings =
    if (choice.isSameDeviceAs(device(foot.other))) this else withDevice(foot, choice)

/** One row of a device list in the settings. */
data class DeviceOption(
    val choice: DeviceChoice,
    /** "COM5", "Block 3". */
    val title: String,
    /** Port description or Bluetooth address; may be empty. */
    val detail: String,
    val looksLikeSock: Boolean,
    /** False for the remembered device when it is not in the list right now (unplugged, unpaired). */
    val present: Boolean,
    /** The foot's current device. */
    val selected: Boolean,
    /** The other foot uses this device: it cannot be picked here. */
    val usedByOtherFoot: Boolean,
) {
    val selectable: Boolean get() = !usedByOtherFoot
}

/** Turns what the platform lists into picker rows for one foot. */
object DeviceOptions {
    /**
     * Bluetooth serial links first (that is how a paired sock appears), then by port number; [foot]'s remembered
     * port first of all if it is missing from [ports].
     */
    fun serialPorts(ports: List<SerialPortEntry>, foot: Foot, settings: AppSettings): List<DeviceOption> {
        val current = settings.device(foot)
        val other = settings.device(foot.other)
        val rows = ports
            .distinctBy { it.name.uppercase() }
            .sortedWith(compareByDescending<SerialPortEntry> { it.looksLikeSock }.thenBy(PortOrder) { it.name })
            .map {
                val choice = DeviceChoice.SerialPort(it.name)
                DeviceOption(
                    choice = choice,
                    title = it.name,
                    detail = it.description,
                    looksLikeSock = it.looksLikeSock,
                    present = true,
                    selected = choice.isSameDeviceAs(current),
                    usedByOtherFoot = usedByOther(choice, choice.isSameDeviceAs(current), foot, other),
                )
            }
        val missing = (current as? DeviceChoice.SerialPort)?.takeIf { c -> rows.none { it.selected } }?.let {
            missingOption(it, it.portName, "", foot, other)
        }
        return listOfNotNull(missing) + rows
    }

    /** Likely socks first, then by name; [foot]'s remembered device first of all if it is no longer bonded. */
    fun bluetooth(devices: List<BluetoothDeviceEntry>, foot: Foot, settings: AppSettings): List<DeviceOption> {
        val current = settings.device(foot)
        val other = settings.device(foot.other)
        val rows = devices
            .distinctBy { it.address.uppercase() }
            .sortedWith(
                compareByDescending<BluetoothDeviceEntry> { it.looksLikeSock }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name ?: it.address },
            )
            .map {
                val choice = DeviceChoice.Bluetooth(it.address, it.name.orEmpty())
                DeviceOption(
                    choice = choice,
                    title = it.name?.takeIf(String::isNotBlank) ?: it.address,
                    detail = it.address,
                    looksLikeSock = it.looksLikeSock,
                    present = true,
                    selected = choice.isSameDeviceAs(current),
                    usedByOtherFoot = usedByOther(choice, choice.isSameDeviceAs(current), foot, other),
                )
            }
        val missing = (current as? DeviceChoice.Bluetooth)?.takeIf { rows.none { it.selected } }?.let {
            missingOption(it, it.name.ifBlank { it.address }, it.address, foot, other)
        }
        return listOfNotNull(missing) + rows
    }

    /** What to select when the user switches a foot to this kind of device: the first free, present sock-like one. */
    fun suggestion(options: List<DeviceOption>): DeviceOption? {
        val free = options.filter { it.present && !it.usedByOtherFoot }
        return free.firstOrNull { it.looksLikeSock } ?: free.firstOrNull()
    }

    /**
     * The other foot names this device. If both name it, the left foot keeps it (as the controller does): only on
     * the right foot is the selected row "used by the other foot".
     */
    private fun usedByOther(choice: DeviceChoice, selected: Boolean, foot: Foot, other: DeviceChoice): Boolean =
        choice.isSameDeviceAs(other) && !(selected && foot == Foot.LEFT)

    private fun missingOption(choice: DeviceChoice, title: String, detail: String, foot: Foot, other: DeviceChoice) =
        DeviceOption(
            choice = choice,
            title = title,
            detail = detail,
            looksLikeSock = false,
            present = false,
            selected = true,
            usedByOtherFoot = usedByOther(choice, true, foot, other),
        )

    /** "COM3" before "COM10"; names without a trailing number by text. */
    private object PortOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val (pa, na) = split(a)
            val (pb, nb) = split(b)
            val byPrefix = pa.compareTo(pb, ignoreCase = true)
            if (byPrefix != 0) return byPrefix
            return compareValues(na, nb)
        }

        private fun split(name: String): Pair<String, Long?> {
            val digits = name.takeLastWhile(Char::isDigit)
            return name.dropLast(digits.length) to digits.take(18).toLongOrNull()
        }
    }
}

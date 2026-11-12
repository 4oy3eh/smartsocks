package com.smartsocks.core.protocol

import kotlin.math.pow

/**
 * Wire format of one sensor packet, as reverse-engineered from the legacy app (see docs/legacy-analysis.md).
 *
 * ```
 * [0]      0xF0  start
 * [1..28]  unknown (probably device timestamp)
 * [29..44] 8 x uint16 little-endian sensor counts (6 used)
 * [45]     unknown (maybe checksum)
 * [46]     0x55  end
 * ```
 */
object PacketFormat {
    const val SIZE = 47
    const val START: Byte = 0xF0.toByte()
    const val END: Byte = 0x55
    const val SENSOR_OFFSET = 29
    const val CHANNELS = 8
    const val MAX_COUNT = 0xFFFF
}

/** Number of sensors actually wired in the insole. */
const val SENSOR_COUNT = 6

/** `BTS1=n`: n=0 is 10 Hz, otherwise 25*n Hz. */
enum class SampleRate(val code: Int, val hz: Int) {
    HZ_10(0, 10),
    HZ_25(1, 25),
    HZ_50(2, 50),
    HZ_75(3, 75),
    HZ_100(4, 100),
    HZ_125(5, 125),
    HZ_150(6, 150),
    HZ_175(7, 175),
    HZ_200(8, 200),
    ;

    val periodNanos: Long get() = 1_000_000_000L / hz
    val label: String get() = "$hz Hz"
}

/** `BTS2=n`: full-scale resistance is 2 kOhm * 2^n; one count is 2^(n-5) Ohm. */
enum class MeasurementRange(val code: Int) {
    K2(0),
    K4(1),
    K8(2),
    K16(3),
    K32(4),
    K64(5),
    K128(6),
    K256(7),
    K512(8),
    K1024(9),
    ;

    val ohmsPerCount: Double get() = 2.0.pow(code - 5)
    val fullScaleOhms: Double get() = 2000.0 * 2.0.pow(code)
    val label: String get() = "${2 shl code} kΩ"

    /**
     * A full-scale count only says "at least the range maximum", which for an FSR means unloaded, so it maps to an
     * open circuit (infinite resistance, zero load) rather than to a phantom conductance.
     */
    fun countsToOhms(counts: Int): Double =
        if (counts >= PacketFormat.MAX_COUNT) Double.POSITIVE_INFINITY else counts * ohmsPerCount

    fun ohmsToCounts(ohms: Double): Int =
        (ohms / ohmsPerCount).toLong().coerceIn(0L, PacketFormat.MAX_COUNT.toLong()).toInt()
}

/** ASCII commands understood by the sock firmware; each is terminated by `\r`. */
sealed interface SockCommand {
    val wire: String

    fun encode(): ByteArray = wire.encodeToByteArray()

    data object Start : SockCommand {
        override val wire = "BT^START\r"
    }

    data object Stop : SockCommand {
        override val wire = "BT^STOP\r"
    }

    data object ResetTimestamp : SockCommand {
        override val wire = "BT^TRES\r"
    }

    data class SetSampleRate(val rate: SampleRate) : SockCommand {
        override val wire get() = "BTS1=${rate.code}\r"
    }

    data class SetRange(val range: MeasurementRange) : SockCommand {
        override val wire get() = "BTS2=${range.code}\r"
    }

    companion object {
        /** Parses one command without the trailing `\r`. Returns null for anything unknown. */
        fun parse(line: String): SockCommand? = when {
            line == "BT^START" -> Start

            line == "BT^STOP" -> Stop

            line == "BT^TRES" -> ResetTimestamp

            line.startsWith("BTS1=") -> line.removePrefix("BTS1=").toIntOrNull()
                ?.let { code -> SampleRate.entries.firstOrNull { it.code == code } }
                ?.let(::SetSampleRate)

            line.startsWith("BTS2=") -> line.removePrefix("BTS2=").toIntOrNull()
                ?.let { code -> MeasurementRange.entries.firstOrNull { it.code == code } }
                ?.let(::SetRange)

            else -> null
        }

        /**
         * Configure-then-start order. The legacy app sent START first and STOP afterwards, which looks like a bug;
         * this order still needs to be confirmed on real hardware.
         */
        fun startSequence(rate: SampleRate, range: MeasurementRange): List<SockCommand> =
            listOf(Stop, SetRange(range), SetSampleRate(rate), ResetTimestamp, Start)
    }
}

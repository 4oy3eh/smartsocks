package com.smartsocks.core.settings

import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.sim.SimMode

/**
 * [AppSettings] as `key=value` lines, the first being `smartsocks-settings=<version>`. Values are escaped (`\\`,
 * `\n`, `\r`, `\=`), so any string survives; keys are fixed identifiers. `#` lines and blank lines are ignored.
 *
 * Decoding never throws: unknown keys are ignored and every missing or invalid value falls back to its default on
 * its own. A file without a valid version line is not ours and decodes to the defaults; a newer version is read
 * best effort (keys are only ever added).
 */
object SettingsCodec {
    const val VERSION = 1
    const val VERSION_KEY = "smartsocks-settings"

    fun encode(settings: AppSettings): String {
        val out = StringBuilder()
        fun put(key: String, value: Any) {
            out.append(key).append('=').append(escape(value.toString())).append('\n')
        }
        put(VERSION_KEY, VERSION)
        for (foot in Foot.entries) {
            val p = foot.prefix
            when (val d = settings.device(foot)) {
                DeviceChoice.None -> put("$p.device", NONE)

                is DeviceChoice.Simulator -> {
                    put("$p.device", SIMULATOR)
                    put("$p.sim.mode", d.mode.name)
                    put("$p.sim.preloaded", d.preloaded)
                }

                is DeviceChoice.SerialPort -> {
                    put("$p.device", SERIAL)
                    put("$p.serial.port", d.portName)
                }

                is DeviceChoice.Bluetooth -> {
                    put("$p.device", BLUETOOTH)
                    put("$p.bt.address", d.address)
                    put("$p.bt.name", d.name)
                }
            }
        }
        put("stream.rate", settings.stream.rate.name)
        put("stream.range", settings.stream.range.name)
        with(settings.processor) {
            put("processor.contactOnThreshold", contactOnThreshold)
            put("processor.contactOffThreshold", contactOffThreshold)
            put("processor.autoZeroSeconds", autoZeroSeconds)
            put("processor.autoZeroMaxMicroSiemens", autoZeroMaxMicroSiemens)
            put("processor.autoZeroSwingFraction", autoZeroSwingFraction)
            put("processor.copMinCutoffHz", copMinCutoffHz)
            put("processor.copBeta", copBeta)
        }
        put("theme", settings.theme.name)
        put("liveView", settings.liveView.name)
        put("chart.window", settings.chart.window.name)
        put("chart.sensorFoot", settings.chart.sensorFoot.name)
        put("autoConnect", settings.autoConnect)
        return out.toString()
    }

    fun decode(text: String): AppSettings {
        val values = HashMap<String, String>()
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            values[line.substring(0, eq).trim()] = unescape(line.substring(eq + 1))
        }
        val version = values[VERSION_KEY]?.trim()?.toIntOrNull()
        if (version == null || version < 1) return AppSettings()
        return Reader(values).settings()
    }

    private class Reader(private val values: Map<String, String>) {
        private val defaults = AppSettings()

        fun settings() = AppSettings(
            left = device(Foot.LEFT),
            right = device(Foot.RIGHT),
            stream = StreamSettings(
                rate = enum("stream.rate", defaults.stream.rate) { SampleRate.valueOf(it) },
                range = enum("stream.range", defaults.stream.range) { MeasurementRange.valueOf(it) },
            ),
            processor = processor(),
            theme = enum("theme", defaults.theme) { AppTheme.valueOf(it) },
            liveView = enum("liveView", defaults.liveView) { LiveView.valueOf(it) },
            chart = ChartSettings(
                window = enum("chart.window", defaults.chart.window) { ChartWindow.valueOf(it) },
                sensorFoot = enum("chart.sensorFoot", defaults.chart.sensorFoot) { Foot.valueOf(it) },
            ),
            autoConnect = boolean("autoConnect", defaults.autoConnect),
        )

        private fun device(foot: Foot): DeviceChoice {
            val p = foot.prefix
            val fallback = defaults.device(foot)
            return when (values["$p.device"]?.trim()?.lowercase()) {
                NONE -> DeviceChoice.None

                SIMULATOR -> DeviceChoice.Simulator(
                    mode = enum("$p.sim.mode", DeviceChoice.Simulator().mode) { SimMode.valueOf(it) },
                    preloaded = boolean("$p.sim.preloaded", DeviceChoice.Simulator().preloaded),
                )

                SERIAL -> values["$p.serial.port"]?.takeIf { it.isNotBlank() }?.let { DeviceChoice.SerialPort(it) }
                    ?: fallback

                BLUETOOTH -> values["$p.bt.address"]?.takeIf { it.isNotBlank() }?.let {
                    DeviceChoice.Bluetooth(it, values["$p.bt.name"] ?: "")
                } ?: fallback

                else -> fallback
            }
        }

        /**
         * Field by field (each range-checked as [ProcessorSettings] requires); thresholds in the wrong order both
         * fall back to their defaults.
         */
        private fun processor(): ProcessorSettings {
            val d = defaults.processor
            var on = number("processor.contactOnThreshold", d.contactOnThreshold)
            var off = number("processor.contactOffThreshold", d.contactOffThreshold)
            if (off > on) {
                on = d.contactOnThreshold
                off = d.contactOffThreshold
            }
            return runCatching {
                ProcessorSettings(
                    contactOnThreshold = on,
                    contactOffThreshold = off,
                    autoZeroSeconds = number("processor.autoZeroSeconds", d.autoZeroSeconds) { it >= 0.0 },
                    autoZeroMaxMicroSiemens =
                    number("processor.autoZeroMaxMicroSiemens", d.autoZeroMaxMicroSiemens) { it >= 0.0 },
                    autoZeroSwingFraction =
                    number("processor.autoZeroSwingFraction", d.autoZeroSwingFraction) { it in 0.0..1.0 },
                    copMinCutoffHz = number("processor.copMinCutoffHz", d.copMinCutoffHz) { it > 0.0 },
                    copBeta = number("processor.copBeta", d.copBeta) { it >= 0.0 },
                )
            }.getOrDefault(d)
        }

        private inline fun number(key: String, default: Double, valid: (Double) -> Boolean = { true }): Double =
            values[key]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && valid(it) } ?: default

        private fun boolean(key: String, default: Boolean): Boolean = when (values[key]?.trim()?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> default
        }

        private inline fun <T> enum(key: String, default: T, parse: (String) -> T): T {
            val text = values[key]?.trim() ?: return default
            return try {
                parse(text)
            } catch (_: IllegalArgumentException) {
                default
            }
        }
    }

    internal fun escape(value: String): String {
        val out = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '=' -> out.append("\\=")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Inverse of [escape]; an unknown escape or a trailing backslash is kept literally. */
    internal fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '=' -> out.append('=')
                    else -> out.append(c).append(next)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private val Foot.prefix: String get() = name.lowercase()

    private const val NONE = "none"
    private const val SIMULATOR = "simulator"
    private const val SERIAL = "serial"
    private const val BLUETOOTH = "bluetooth"
}

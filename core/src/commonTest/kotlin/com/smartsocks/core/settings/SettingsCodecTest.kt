package com.smartsocks.core.settings

import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.sim.SimMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsCodecTest {
    private val custom = AppSettings(
        left = DeviceChoice.SerialPort("COM7"),
        right = DeviceChoice.Bluetooth("00:11:22:AA:BB:CC", "SmartSock R"),
        stream = StreamSettings(SampleRate.HZ_200, MeasurementRange.K128),
        processor = ProcessorSettings(
            contactOnThreshold = 35.5,
            contactOffThreshold = 12.25,
            autoZeroSeconds = 0.0,
            autoZeroMaxMicroSiemens = 12.0,
            autoZeroSwingFraction = 0.2,
            copMinCutoffHz = 2.5,
            copBeta = 0.125,
        ),
        theme = AppTheme.DARK,
        liveView = LiveView.NUMBERS,
        chart = ChartSettings(ChartWindow.SECONDS_30, Foot.RIGHT),
        autoConnect = false,
    )

    private fun roundTrip(settings: AppSettings) = SettingsCodec.decode(SettingsCodec.encode(settings))

    private fun withVersion(vararg lines: String) = (listOf("smartsocks-settings=1") + lines).joinToString("\n")

    @Test
    fun `defaults are two walking simulators`() {
        val d = AppSettings()
        assertEquals(DeviceChoice.Simulator(SimMode.Walking, preloaded = false), d.left)
        assertEquals(DeviceChoice.Simulator(SimMode.Walking, preloaded = false), d.right)
        assertEquals(StreamSettings(), d.stream)
        assertEquals(ProcessorSettings(), d.processor)
        assertEquals(AppTheme.SYSTEM, d.theme)
        assertEquals(LiveView.COP, d.liveView)
        assertEquals(ChartSettings(ChartWindow.SECONDS_10, Foot.LEFT), d.chart)
        assertTrue(d.autoConnect)
    }

    @Test
    fun `every field round trips`() {
        assertEquals(custom, roundTrip(custom))
        assertEquals(AppSettings(), roundTrip(AppSettings()))
        for (theme in AppTheme.entries) assertEquals(theme, roundTrip(AppSettings(theme = theme)).theme)
        for (view in LiveView.entries) assertEquals(view, roundTrip(AppSettings(liveView = view)).liveView)
        for (window in ChartWindow.entries) {
            for (foot in Foot.entries) {
                val s = AppSettings(chart = ChartSettings(window, foot))
                assertEquals(s, roundTrip(s))
            }
        }
        for (rate in SampleRate.entries) {
            for (range in MeasurementRange.entries) {
                val s = AppSettings(stream = StreamSettings(rate, range))
                assertEquals(s, roundTrip(s))
            }
        }
    }

    @Test
    fun `every device choice round trips on both feet`() {
        val choices = listOf(
            DeviceChoice.None,
            DeviceChoice.Simulator(),
            DeviceChoice.Simulator(SimMode.Standing, preloaded = true),
            DeviceChoice.Simulator(SimMode.Lifted),
            DeviceChoice.SerialPort("/dev/rfcomm0"),
            DeviceChoice.Bluetooth("00:11:22:33:44:55", ""),
            DeviceChoice.Bluetooth("00:11:22:33:44:55", "Left sock"),
        )
        for (l in choices) {
            for (r in choices) {
                val s = AppSettings(left = l, right = r)
                assertEquals(s, roundTrip(s), "$l / $r")
            }
        }
    }

    @Test
    fun `device accessors`() {
        val s = AppSettings().withDevice(Foot.RIGHT, DeviceChoice.None)
        assertEquals(DeviceChoice.None, s.device(Foot.RIGHT))
        assertEquals(DeviceChoice.Simulator(), s.device(Foot.LEFT))
    }

    @Test
    fun `encoded text starts with the version and has one entry per line`() {
        val text = SettingsCodec.encode(custom)
        val lines = text.trimEnd('\n').split('\n')
        assertEquals("smartsocks-settings=1", lines.first())
        assertTrue(lines.all { '=' in it })
        assertTrue("right.bt.name=SmartSock R" in lines)
    }

    @Test
    fun `values with separators and newlines are escaped`() {
        val nasty = "a=b\nc\\d\r\\n=e  "
        val s =
            AppSettings(left = DeviceChoice.Bluetooth("x=y", nasty), right = DeviceChoice.SerialPort("\\\\.\\COM10"))
        val text = SettingsCodec.encode(s)
        assertEquals(s, SettingsCodec.decode(text))
        val line = text.split('\n').single { it.startsWith("left.bt.name=") }
        assertEquals("left.bt.name=a\\=b\\nc\\\\d\\r\\\\n\\=e  ", line)
        assertEquals(nasty, SettingsCodec.unescape(SettingsCodec.escape(nasty)))
    }

    @Test
    fun `unescape keeps unknown escapes and a trailing backslash`() {
        assertEquals("a\\tb\\", SettingsCodec.unescape("a\\tb\\"))
        assertEquals("plain", SettingsCodec.unescape("plain"))
    }

    @Test
    fun `garbage and foreign files decode to the defaults`() {
        val texts = listOf(
            "",
            "\n\n",
            "lorem ipsum\n\u0000\u0001==\n=\n",
            "theme=DARK\nautoConnect=false", // no version line: not ours
            "smartsocks-settings=abc\ntheme=DARK",
            "smartsocks-settings=0\ntheme=DARK",
        )
        for (text in texts) assertEquals(AppSettings(), SettingsCodec.decode(text), "text: $text")
    }

    @Test
    fun `missing keys fall back one by one and unknown keys are ignored`() {
        val s = SettingsCodec.decode(
            withVersion("theme=LIGHT", "some.future.key=42", "# a comment", "", "no equals sign"),
        )
        assertEquals(AppSettings(theme = AppTheme.LIGHT), s)
    }

    @Test
    fun `a newer version is read best effort`() {
        assertEquals(LiveView.CHART, SettingsCodec.decode("smartsocks-settings=7\nliveView=CHART").liveView)
    }

    @Test
    fun `bad values fall back to the default of that field only`() {
        val s = SettingsCodec.decode(
            withVersion(
                "theme=PURPLE",
                "liveView=NUMBERS",
                "stream.rate=HZ_999",
                "stream.range=K256",
                "autoConnect=maybe",
                "processor.contactOnThreshold=lots",
                "processor.contactOffThreshold=5",
                "processor.autoZeroSeconds=-3",
                "processor.autoZeroSwingFraction=1.5",
                "processor.copMinCutoffHz=NaN",
                "processor.copBeta=Infinity",
                "processor.autoZeroMaxMicroSiemens=7",
                "left.device=teleport",
                "right.device=simulator",
                "right.sim.mode=Flying",
                "right.sim.preloaded=TRUE",
            ),
        )
        val d = ProcessorSettings()
        assertEquals(
            AppSettings(
                right = DeviceChoice.Simulator(SimMode.Walking, preloaded = true),
                stream = StreamSettings(SampleRate.HZ_100, MeasurementRange.K256),
                processor = d.copy(contactOffThreshold = 5.0, autoZeroMaxMicroSiemens = 7.0),
                liveView = LiveView.NUMBERS,
            ),
            s,
        )
    }

    @Test
    fun `chart settings fall back field by field`() {
        assertEquals(
            ChartSettings(ChartWindow.SECONDS_10, Foot.RIGHT),
            SettingsCodec.decode(withVersion("chart.window=SECONDS_7", "chart.sensorFoot=RIGHT")).chart,
        )
        assertEquals(
            ChartSettings(ChartWindow.SECONDS_5, Foot.LEFT),
            SettingsCodec.decode(withVersion("chart.window=SECONDS_5", "chart.sensorFoot=middle")).chart,
        )
        assertEquals(ChartSettings(), SettingsCodec.decode(withVersion("theme=DARK")).chart)
    }

    @Test
    fun `thresholds in the wrong order both fall back`() {
        val s = SettingsCodec.decode(
            withVersion(
                "processor.contactOnThreshold=10",
                "processor.contactOffThreshold=30",
                "processor.copBeta=0.5",
            ),
        )
        assertEquals(ProcessorSettings(copBeta = 0.5), s.processor)
    }

    @Test
    fun `incomplete devices fall back to the default device`() {
        val s = SettingsCodec.decode(
            withVersion("left.device=serial", "left.serial.port=  ", "right.device=bluetooth", "right.bt.name=x"),
        )
        assertEquals(DeviceChoice.Simulator(), s.left)
        assertEquals(DeviceChoice.Simulator(), s.right)
        val noName = SettingsCodec.decode(withVersion("left.device=BLUETOOTH", "left.bt.address=AA"))
        assertEquals(DeviceChoice.Bluetooth("AA", ""), noName.left)
        assertEquals(DeviceChoice.None, SettingsCodec.decode(withVersion("right.device=none")).right)
    }

    @Test
    fun `windows line endings and padded keys are accepted`() {
        val s = SettingsCodec.decode("smartsocks-settings=1\r\n theme =DARK\r\nautoConnect= false \r\n")
        assertEquals(AppTheme.DARK, s.theme)
        assertEquals(false, s.autoConnect)
    }
}

package com.smartsocks.core.settings

import com.smartsocks.core.analytics.FootGains
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.sim.SimMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        chart = ChartSettings(
            ChartWindow.SECONDS_30,
            Foot.RIGHT,
            SensorSelection.of(listOf(0, 3, 5)),
            ChartLoad.SELECTED,
        ),
        recording = RecordingSettings(saveRaw = false),
        autoConnect = false,
        autoReconnect = false,
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
        assertTrue(d.autoReconnect)
        assertTrue(d.recording.saveRaw)
    }

    @Test
    fun `auto reconnect is on unless explicitly off`() {
        assertFalse(roundTrip(AppSettings(autoReconnect = false)).autoReconnect)
        assertTrue(roundTrip(AppSettings(autoReconnect = true)).autoReconnect)
        // Files written before the setting existed, and garbage values: the default (on).
        assertTrue(SettingsCodec.decode(withVersion("autoConnect=false")).autoReconnect)
        assertTrue(SettingsCodec.decode(withVersion("autoReconnect=sometimes")).autoReconnect)
        assertFalse(SettingsCodec.decode(withVersion("autoReconnect= FALSE ")).autoReconnect)
        assertEquals(AppSettings(autoReconnect = false), SettingsCodec.decode(withVersion("autoReconnect=false")))
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
            DeviceChoice.Replay("2026-11-13_14-05-22", Foot.LEFT),
            DeviceChoice.Replay("walk = slow\\fast", Foot.RIGHT, loop = false),
        )
        for (l in choices) {
            for (r in choices) {
                val s = AppSettings(left = l, right = r)
                assertEquals(s, roundTrip(s), "$l / $r")
            }
        }
    }

    @Test
    fun `a replay is read forgivingly`() {
        fun decode(vararg lines: String) = SettingsCodec.decode(withVersion(*lines))

        // The foot defaults to the same foot, the loop to on; foot names in any case.
        assertEquals(
            DeviceChoice.Replay("rec", Foot.RIGHT, loop = true),
            decode("right.device=replay", "right.replay.session=rec", "right.replay.loop=maybe").right,
        )
        assertEquals(
            DeviceChoice.Replay("rec", Foot.LEFT, loop = false),
            decode(
                "right.device=REPLAY",
                "right.replay.session=rec",
                "right.replay.foot=left",
                "right.replay.loop=false",
            )
                .right,
        )
        assertEquals(
            DeviceChoice.Replay("rec", Foot.LEFT),
            decode("left.device=replay", "left.replay.session=rec", "left.replay.foot=MIDDLE").left,
        )
        // Without a session there is nothing to replay: the default device.
        assertEquals(AppSettings().left, decode("left.device=replay", "left.replay.session=  ").left)
        assertEquals(AppSettings().right, decode("right.device=replay", "right.replay.foot=LEFT").right)
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
    fun `sensor selection and load mode round trip`() {
        for (mask in 1..SensorSelection.ALL_MASK) {
            for (load in ChartLoad.entries) {
                val s = AppSettings(chart = ChartSettings(sensors = SensorSelection(mask), load = load))
                assertEquals(s, roundTrip(s))
            }
        }
        val evens = AppSettings(chart = ChartSettings(sensors = SensorSelection.of(listOf(1, 3, 5))))
        assertTrue("chart.sensors=2,4,6\n" in SettingsCodec.encode(evens))
        assertTrue("chart.load=SELECTED\n" in SettingsCodec.encode(custom))
    }

    @Test
    fun `files from before the sensor selection show all sensors and the total`() {
        val old = SettingsCodec.decode(withVersion("chart.window=SECONDS_5", "chart.sensorFoot=RIGHT")).chart
        assertEquals(ChartSettings(ChartWindow.SECONDS_5, Foot.RIGHT, SensorSelection.All, ChartLoad.TOTAL), old)
    }

    @Test
    fun `a bad sensor selection is read forgivingly`() {
        fun sensors(text: String) = SettingsCodec.decode(withVersion("chart.sensors=$text")).chart.sensors
        assertEquals(SensorSelection.of(listOf(0, 2)), sensors(" 1, x ,3,9,0,-1"), "junk entries are skipped")
        assertEquals(SensorSelection.of(listOf(4)), sensors("5,5"))
        assertEquals(SensorSelection.All, sensors(""), "nothing selected: all")
        assertEquals(SensorSelection.All, sensors("7,8"))
        assertEquals(SensorSelection.All, sensors("none"))
        assertEquals(ChartLoad.TOTAL, SettingsCodec.decode(withVersion("chart.load=SUM")).chart.load)
        assertEquals(ChartLoad.SELECTED, SettingsCodec.decode(withVersion("chart.load= SELECTED")).chart.load)
    }

    @Test
    fun `a selection never becomes empty`() {
        val one = SensorSelection.of(listOf(2))
        assertEquals(one, one.toggle(2), "the last sensor stays")
        assertEquals(SensorSelection.of(listOf(2, 4)), one.toggle(4))
        assertEquals(SensorSelection.All, SensorSelection.All.toggle(0).toggle(0))
        assertEquals(listOf(2, 4), one.toggle(4).indices)
        assertTrue(4 in one.toggle(4))
        assertFalse(6 in SensorSelection.All)
        assertEquals("3+5", one.toggle(4).numbersText())
        assertEquals("1–6", SensorSelection.All.numbersText())
        assertEquals(SensorSelection.All, SensorSelection.of(emptyList()))
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

    private val serial = DeviceChoice.SerialPort("com7")
    private val bt = DeviceChoice.Bluetooth("00:16:a4:00:00:01", "Block 3")

    @Test
    fun `balance gains round trip per pair in order`() {
        val replay = DeviceChoice.Replay("a|b=c%7C", Foot.LEFT)
        val settings = AppSettings(left = serial, right = bt)
            .withCurrentGains(FootGains(1.0712, 0.9335))
            .copy(left = replay)
            .withCurrentGains(FootGains.NEUTRAL)
            .copy(left = serial)
        val read = roundTrip(settings)
        assertEquals(settings.balanceGains, read.balanceGains)
        assertEquals(settings.balanceGains.keys.toList(), read.balanceGains.keys.toList(), "oldest first")
        assertEquals(settings, read)
        assertEquals(FootGains(1.0712, 0.9335), read.currentGains)
        assertEquals(FootGains.NEUTRAL, read.copy(left = replay).currentGains, "calibrated, no correction needed")
        // By identity: the port name's case and a sock's display name do not matter.
        val same = read.copy(
            left = DeviceChoice.SerialPort("COM7"),
            right = bt.copy(address = "00:16:A4:00:00:01", name = ""),
        )
        assertEquals(FootGains(1.0712, 0.9335), same.currentGains)
    }

    @Test
    fun `another pair is not calibrated`() {
        val calibrated = AppSettings(left = serial, right = bt).withCurrentGains(FootGains(1.05, 1 / 1.05))
        assertNull(calibrated.copy(right = DeviceChoice.Bluetooth("00:16:A4:00:00:02", "")).currentGains, "replaced")
        assertNull(calibrated.copy(left = bt, right = serial).currentGains, "sides swapped")
        assertNull(AppSettings().currentGains)
        assertTrue(calibrated.withCurrentGains(null).balanceGains.isEmpty())
        assertFalse("insoleGain" in SettingsCodec.encode(AppSettings()))
    }

    @Test
    fun `at most the newest pairs are kept`() {
        var s = AppSettings()
        repeat(AppSettings.MAX_BALANCE_PAIRS + 5) {
            s =
                s.copy(left = DeviceChoice.SerialPort("COM$it")).withCurrentGains(FootGains(1.1, 1 / 1.1))
        }
        assertEquals(AppSettings.MAX_BALANCE_PAIRS, s.balanceGains.size)
        assertNull(s.copy(left = DeviceChoice.SerialPort("COM4")).currentGains, "among the oldest")
        assertEquals(1.1, s.copy(left = DeviceChoice.SerialPort("COM36")).currentGains?.left)
        // Recalibrating an old pair makes it the newest.
        val again = s.copy(left = DeviceChoice.SerialPort("COM5")).withCurrentGains(FootGains.NEUTRAL)
        assertEquals(InsolePair.of(DeviceChoice.SerialPort("COM5"), s.right), again.balanceGains.keys.last())
        assertEquals(AppSettings.MAX_BALANCE_PAIRS, again.balanceGains.size)
        assertEquals(again.balanceGains, roundTrip(again).balanceGains)
    }

    @Test
    fun `replay gains are dropped with their recording`() {
        val replay = DeviceChoice.Replay("2026-11-13_14-05-22", Foot.RIGHT)
        val s = AppSettings(left = serial, right = replay).withCurrentGains(FootGains(1.1, 1 / 1.1))
            .copy(right = bt).withCurrentGains(FootGains(1.2, 1 / 1.2))
        val pruned = s.withoutReplayGains("2026-11-13_14-05-22")
        assertEquals(listOf(InsolePair.of(serial, bt)), pruned.balanceGains.keys.toList())
        assertSame(pruned, pruned.withoutReplayGains("other"))
    }

    @Test
    fun `bad gain entries are skipped one by one`() {
        val read = SettingsCodec.decode(
            withVersion(
                "insoleGain.0=serial:COM7|serial:COM8|1.07|0.93",
                "insoleGain.1=serial:COM7|serial:COM9|abc|1",
                "insoleGain.2=serial:COM7|serial:COM9|NaN|1",
                "insoleGain.3=serial:COM7|serial:COM9|-1|1",
                "insoleGain.4=serial:COM7|serial:COM9|9|1",
                "insoleGain.5=|serial:COM9|1.1|1",
                "insoleGain.6=garbage",
                "insoleGain.7=serial:COM7|serial:COM9|1.1",
                "insoleGain.x=serial:COM1|serial:COM2|1.1|1",
                "insoleGain.10=bluetooth:00:11|replay:LEFT:a%7Cb|0.95|1.05",
            ),
        )
        assertEquals(
            mapOf(
                InsolePair("serial:COM7", "serial:COM8") to FootGains(1.07, 0.93),
                InsolePair("bluetooth:00:11", "replay:LEFT:a|b") to FootGains(0.95, 1.05),
            ),
            read.balanceGains,
        )
    }
}

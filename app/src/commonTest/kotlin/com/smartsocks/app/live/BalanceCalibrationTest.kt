package com.smartsocks.app.live

import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.unsupportedOnThisPlatform
import com.smartsocks.core.analytics.BalanceCalculator
import com.smartsocks.core.analytics.BalanceCalibrator
import com.smartsocks.core.analytics.BalanceResult
import com.smartsocks.core.analytics.FootGains
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.InMemorySettingsStore
import com.smartsocks.core.settings.InsolePair
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimConfig
import com.smartsocks.core.sim.SimMode
import com.smartsocks.core.sim.SimulatedSockTransport
import com.smartsocks.core.sim.preloadedInsole
import com.smartsocks.core.transport.SockTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.Volatile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The L/R balance calibration (weight shifting) through the [LiveController], two simulated feet in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class BalanceCalibrationTest {
    /**
     * Simulators without per-sensor spread whose right insole (not the preloaded one) is [rightSensitivity] times as
     * sensitive as the left one; a replay is a walking simulator that says its recording was made with [recorded]
     * gains. A foot's stream can be [stalled] (no data, still connected).
     */
    private class Devices(
        private val rightSensitivity: Double = 1.12,
        private val recorded: FootGains = FootGains(0.95, 1 / 0.95),
    ) : DeviceFactory {
        @Volatile
        var stalled: Set<Foot> = emptySet()

        override fun create(foot: Foot, choice: DeviceChoice): DeviceResult {
            val (mode, preloaded) = when (choice) {
                is DeviceChoice.Simulator -> choice.mode to choice.preloaded
                is DeviceChoice.Replay -> SimMode.Walking to false
                else -> return unsupportedOnThisPlatform(choice, "Test")
            }
            val sensitivity = if (foot == Foot.RIGHT && !preloaded) rightSensitivity else 1.0
            val config =
                SimConfig(mode = mode, gainSpread = 0.0, sensitivity = sensitivity, layout = LiveGeometry.layout)
            val sim = SimulatedSockTransport(foot, if (preloaded) config.preloadedInsole() else config)
            val transport = Stallable(foot, sim)
            val recordedGain = if (choice is DeviceChoice.Replay) recorded.of(foot) else null
            return DeviceResult.Ready(transport, simulator = sim, recordedGain = recordedGain)
        }

        inner class Stallable(private val foot: Foot, private val sim: SimulatedSockTransport) :
            SockTransport by sim {
            override suspend fun read(buffer: ByteArray): Int {
                while (foot in stalled) delay(50)
                return sim.read(buffer)
            }
        }
    }

    private class Fixture(val settings: SettingsRepository, val devices: Devices, val controller: LiveController)

    private fun TestScope.fixture(initial: AppSettings = walking, devices: Devices = Devices()): Fixture {
        val settings = SettingsRepository(InMemorySettingsStore(SettingsCodec.encode(initial)), backgroundScope)
        val controller = LiveController(settings, devices, backgroundScope, reconnectDelayMillis = noJitter) {
            testScheduler.currentTime * 1_000_000
        }
        return Fixture(settings, devices, controller)
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private val walking = AppSettings(left = DeviceChoice.Simulator(), right = DeviceChoice.Simulator())

    /** Both simulators to [mode] (the same insoles: the sessions stay). */
    private fun Fixture.mode(mode: SimMode) = settings.update { s ->
        Foot.entries.fold(s) { acc, foot ->
            val d = acc.device(foot)
            if (d is DeviceChoice.Simulator) acc.withDevice(foot, d.copy(mode = mode)) else acc
        }
    }

    /**
     * Calibrates both feet's zero points with the feet lifted (as the card asks), then stands in [mode]. Auto-zero
     * would do too, but while its window still holds a walk it moves (the measurement is then refused, see
     * [walkThenShiftIsRefusedUntilAutoZeroSettles]) and its cap distorts quiet standing.
     */
    private fun TestScope.readyToShift(f: Fixture, mode: SimMode = SimMode.Shifting) {
        f.mode(SimMode.Lifted)
        advance(1_000)
        assertTrue(f.controller.left.calibrate())
        assertTrue(f.controller.right.calibrate())
        advance(2_500)
        assertTrue(f.controller.left.calibrated.value && f.controller.right.calibrated.value)
        f.mode(mode)
        advance(1_000)
    }

    /** The mean left share of the live balance over [millis], looked at every 10 ms. */
    private fun TestScope.meanLeftShare(controller: LiveController, millis: Long): Double {
        var sum = 0.0
        val n = (millis / 10).toInt()
        repeat(n) {
            advance(10)
            sum += assertNotNull(BalanceCalculator.compute(controller.left.latest, controller.right.latest)).left
        }
        return sum / n
    }

    private val pair = InsolePair("simulator:false", "simulator:false")

    @Test
    fun weightShiftingCalibratesThePairAndTheBalanceReadsTrue() = runTest {
        val f = fixture()
        val c = f.controller
        readyToShift(f)
        assertEquals(GainsInEffect(), c.gains.value)

        assertTrue(c.calibrateBalance())
        assertEquals(BalanceCalibrationState.Measuring(0.0), c.balanceCalibration.value)
        advance(5_000)
        val measuring = assertIs<BalanceCalibrationState.Measuring>(c.balanceCalibration.value)
        assertTrue(measuring.progress in 0.4..0.6, "$measuring")
        assertTrue(f.settings.settings.value.balanceGains.isEmpty(), "nothing stored before the end")

        advance(6_000)
        val done = assertIs<BalanceCalibrationState.Done>(c.balanceCalibration.value)
        assertEquals(1.12, done.result.rightToLeft, 0.03)
        val gains = done.result.gains
        assertEquals(1.0, gains.left * gains.right, 1e-9, "the overall level is kept")
        assertEquals(GainsInEffect(gains, GainSource.CALIBRATED), c.gains.value)
        assertEquals(mapOf(pair to gains), f.settings.settings.value.balanceGains)
        assertEquals(gains.left, assertNotNull(c.left.latest).gain)
        assertEquals(gains.right, assertNotNull(c.right.latest).gain)

        f.mode(SimMode.Standing)
        advance(500)
        assertEquals(0.5, meanLeftShare(c, 3_000), 0.015)
        assertTrue(c.sample(0).balanceCalibrated)
    }

    @Test
    fun resetGoesBackToNeutralGains() = runTest {
        val f = fixture()
        val c = f.controller
        readyToShift(f)
        c.calibrateBalance()
        advance(11_000)
        assertIs<BalanceCalibrationState.Done>(c.balanceCalibration.value)

        c.resetBalance()
        assertEquals(BalanceCalibrationState.Idle, c.balanceCalibration.value)
        advance(100)
        assertTrue(f.settings.settings.value.balanceGains.isEmpty())
        assertEquals(GainsInEffect(), c.gains.value)
        assertEquals(1.0, assertNotNull(c.left.latest).gain)
        assertEquals(1.0, assertNotNull(c.right.latest).gain)
        assertFalse(c.sample(0).balanceCalibrated)
    }

    @Test
    fun refusedUnlessBothFeetStreamStandAndHaveAZeroPoint() = runTest {
        val one = fixture(walking.copy(right = DeviceChoice.None))
        advance(1_000)
        assertFalse(one.controller.calibrateBalance())
        assertEquals(
            BalanceCalibrationState.Failed(LiveController.BOTH_FEET_STREAMING),
            one.controller.balanceCalibration.value,
        )

        val lifted =
            fixture(
                walking.copy(
                    left = DeviceChoice.Simulator(SimMode.Standing),
                    right = DeviceChoice.Simulator(SimMode.Lifted),
                ),
            )
        advance(1_000)
        assertFalse(lifted.controller.calibrateBalance())
        assertEquals(
            BalanceCalibrationState.Failed("Stand on both feet first: the right foot is not on the ground"),
            lifted.controller.balanceCalibration.value,
        )

        // Standing from the start: auto-zero never saw a foot lifted.
        val standing = DeviceChoice.Simulator(SimMode.Standing)
        val noZero = fixture(AppSettings(left = standing, right = standing))
        advance(1_000)
        assertFalse(noZero.controller.calibrateBalance())
        assertEquals(
            BalanceCalibrationState.Failed(BalanceCalibrator.NO_BASELINE_MESSAGE),
            noZero.controller.balanceCalibration.value,
        )
    }

    @Test
    fun walkThenShiftIsRefusedUntilAutoZeroSettles() = runTest {
        val f = fixture()
        advance(2_000)
        f.mode(SimMode.Shifting)
        advance(1_000)
        assertTrue(f.controller.calibrateBalance())
        advance(11_000)
        val failed = assertIs<BalanceCalibrationState.Failed>(f.controller.balanceCalibration.value)
        assertTrue("zero point changed" in failed.message, failed.message)
        assertTrue(failed.message.endsWith(BalanceCalibrator.ZERO_POINT_FIX), failed.message)
        // Once the walk has left auto-zero's window it stays put, and the measurement goes through.
        assertTrue(f.controller.calibrateBalance())
        advance(11_000)
        assertIs<BalanceCalibrationState.Done>(f.controller.balanceCalibration.value)
    }

    @Test
    fun standingStillIsRefused() = runTest {
        val f = fixture()
        readyToShift(f, SimMode.Standing)
        assertTrue(f.controller.calibrateBalance())
        advance(11_000)
        val failed = assertIs<BalanceCalibrationState.Failed>(f.controller.balanceCalibration.value)
        assertTrue(failed.message.startsWith("Shift your weight further"), failed.message)
        assertTrue(f.settings.settings.value.balanceGains.isEmpty())
    }

    @Test
    fun liftingAFootFailsAtOnceAndKeepsTheGains() = runTest {
        val f = fixture()
        readyToShift(f)
        assertTrue(f.controller.calibrateBalance())
        advance(3_000)
        f.settings.update { it.withDevice(Foot.LEFT, DeviceChoice.Simulator(SimMode.Lifted)) }
        advance(200)
        assertEquals(
            BalanceCalibrationState.Failed("Keep both feet on the ground: the left foot was lifted"),
            f.controller.balanceCalibration.value,
        )
        advance(10_000)
        assertIs<BalanceCalibrationState.Failed>(f.controller.balanceCalibration.value, "stays failed")
        assertTrue(f.settings.settings.value.balanceGains.isEmpty())
    }

    @Test
    fun aDisconnectFails() = runTest {
        val f = fixture()
        readyToShift(f)
        assertTrue(f.controller.calibrateBalance())
        advance(3_000)
        f.controller.right.disconnect()
        advance(500)
        assertEquals(
            BalanceCalibrationState.Failed("A foot was disconnected during the measurement"),
            f.controller.balanceCalibration.value,
        )
        assertTrue(f.settings.settings.value.balanceGains.isEmpty())
    }

    @Test
    fun aStreamWithoutDataTimesOut() = runTest {
        val f = fixture()
        readyToShift(f)
        assertTrue(f.controller.calibrateBalance())
        advance(2_000)
        f.devices.stalled = setOf(Foot.RIGHT)
        advance(20_000)
        assertIs<BalanceCalibrationState.Measuring>(f.controller.balanceCalibration.value)
        advance(10_000)
        assertEquals(
            BalanceCalibrationState.Failed(LiveController.TIMED_OUT),
            f.controller.balanceCalibration.value,
        )
    }

    @Test
    fun anotherPairIsNotCalibratedAndTheGainsComeBackWithThePair() = runTest {
        val f = fixture()
        val c = f.controller
        readyToShift(f)
        c.calibrateBalance()
        advance(11_000)
        val gains = assertIs<BalanceCalibrationState.Done>(c.balanceCalibration.value).result.gains

        // Another right insole: neither foot keeps its gain, a gain only means something for its pair.
        f.settings.update { it.withDevice(Foot.RIGHT, DeviceChoice.Simulator(SimMode.Shifting, preloaded = true)) }
        advance(1_000)
        assertEquals(GainsInEffect(), c.gains.value)
        assertEquals(BalanceCalibrationState.Idle, c.balanceCalibration.value)
        assertEquals(1.0, assertNotNull(c.left.latest).gain, "the left session stays, with the new pair's gain")
        assertEquals(1.0, assertNotNull(c.right.latest).gain)

        f.settings.update { it.withDevice(Foot.RIGHT, DeviceChoice.Simulator(SimMode.Shifting)) }
        advance(1_000)
        assertEquals(gains, c.gains.value.gains)
        assertEquals(gains.right, assertNotNull(c.right.latest).gain)

        // A restart with the stored settings: the gains apply from the first sample.
        val again = fixture(f.settings.settings.value)
        advance(1_000)
        assertEquals(gains.right, assertNotNull(again.controller.right.latest).gain)
    }

    @Test
    fun aResetWhileTheResultIsStoredWins() = runTest {
        val f = fixture()
        val c = f.controller
        readyToShift(f)
        // The reset comes from inside the store of the result, between its check and its write.
        var fired = false
        f.settings.guard = { old, new ->
            if (!fired && old.currentGains == null && new.currentGains != null) {
                fired = true
                c.resetBalance()
            }
            new
        }
        assertTrue(c.calibrateBalance())
        advance(11_000)
        assertTrue(fired)
        assertTrue(f.settings.settings.value.balanceGains.isEmpty(), "the reset is the last word")
        assertEquals(BalanceCalibrationState.Idle, c.balanceCalibration.value)
        assertEquals(GainsInEffect(), c.gains.value)
    }

    @Test
    fun aReplayedPairUsesTheRecordingsGainsUntilCalibrated() = runTest {
        val recorded = FootGains(0.95, 1 / 0.95)
        val replays = AppSettings(
            left = DeviceChoice.Replay("rec", Foot.LEFT),
            right = DeviceChoice.Replay("rec", Foot.RIGHT),
        )
        val f = fixture(replays)
        val c = f.controller
        advance(1_000)
        assertEquals(GainsInEffect(recorded, GainSource.RECORDING), c.gains.value)
        assertEquals(recorded.right, assertNotNull(c.right.latest).gain)

        f.settings.update { it.withCurrentGains(FootGains.NEUTRAL) }
        advance(100)
        assertEquals(GainsInEffect(FootGains.NEUTRAL, GainSource.CALIBRATED), c.gains.value, "its own calibration wins")
        assertEquals(1.0, assertNotNull(c.right.latest).gain)

        c.resetBalance()
        advance(100)
        assertEquals(GainsInEffect(recorded, GainSource.RECORDING), c.gains.value)

        // Deleting the recording drops the pair's gains.
        f.settings.update { it.withCurrentGains(FootGains(1.1, 1 / 1.1)) }
        ReplayFileUsers(c, f.settings).deleted("rec")
        assertTrue(f.settings.settings.value.balanceGains.isEmpty())
        assertNull(f.settings.settings.value.currentGains)
    }

    @Test
    fun resultTexts() {
        fun calibrated(ratio: Double) = BalanceResult.Calibrated(FootGains.forRatio(ratio), ratio)
        assertEquals(
            "The right insole read 12 % more than the left one: corrected.",
            BalanceTexts.result(calibrated(1.12)),
        )
        assertEquals(
            "The left insole read 25 % more than the right one: corrected.",
            BalanceTexts.result(calibrated(0.8)),
        )
        assertEquals(
            "Both insoles read the same: no correction needed.",
            BalanceTexts.result(BalanceResult.Calibrated(FootGains.NEUTRAL, 1.006)),
        )
        assertEquals("Left ×1.058 · right ×0.945", BalanceTexts.gains(FootGains(1.0583, 0.9449)))
    }
}

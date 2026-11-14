package com.smartsocks.app.live

import com.smartsocks.app.playback.PlaybackOverview
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.Point
import com.smartsocks.core.playback.SessionTimeline
import com.smartsocks.core.playback.SessionTimelineBuilder
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.recording.CsvRow
import com.smartsocks.core.settings.SensorSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The chart's sum of the selected sensors: added up while copying a window, never stored as history. */
class SelectedLoadTest {
    private val ms = 1_000_000L

    /** Sensor s carries `k * 10^s`-ish distinct loads: k + 10 s, so every subset sums differently. */
    private fun loadsOf(k: Int) = DoubleArray(SENSOR_COUNT) { k + 10.0 * it }

    private fun state(t: Long, k: Int, foot: Foot = Foot.LEFT): FootState {
        val loads = loadsOf(k)
        return FootState(
            foot = foot,
            timestampNanos = t,
            resistanceOhms = DoubleArray(SENSOR_COUNT) { 1e5 },
            loads = loads,
            totalLoad = loads.sum(),
            shares = DoubleArray(SENSOR_COUNT),
            cop = Point(0.0, 0.0),
            rawCop = Point(0.0, 0.0),
            baselineSource = BaselineSource.NONE,
        )
    }

    private fun mask(vararg channels: Int) = SensorSelection.of(channels.map { it - 1 }).mask

    @Test
    fun theSelectedSumIsTheSumOfTheChosenSensorsPerSample() {
        val h = FootHistory(capacity = 64)
        for (k in 0 until 10) h.append(state(k * 10 * ms, k))
        val w = HistoryWindow(64)
        w.copy(h, nowNanos = 90 * ms, spanNanos = 1000 * ms, selection = mask(1, 3))
        assertEquals(10, w.size)
        assertEquals(mask(1, 3), w.selection)
        for (i in 0 until w.size) {
            // Sensor 1 = k, sensor 3 = k + 20.
            assertEquals(2f * i + 20f, w.selected[i], 1e-4f)
            assertEquals(w.loads[0][i] + w.loads[2][i], w.selected[i], 1e-4f)
        }
        assertEquals(2f * 9 + 20f, w.maxSelected(), 1e-4f)
        // Sensors 1 and 3 only: the largest single load is sensor 3's.
        assertEquals(9f + 20f, w.maxLoad(mask(1, 3)), 1e-4f)
        assertEquals(9f + 50f, w.maxLoad(), 1e-4f)

        // All sensors: the same as the total.
        w.copy(h, nowNanos = 90 * ms, spanNanos = 1000 * ms)
        for (i in 0 until w.size) assertEquals(w.total[i], w.selected[i], 1e-3f)
        // A single sensor: exactly its load.
        w.copy(h, nowNanos = 90 * ms, spanNanos = 1000 * ms, selection = mask(6))
        for (i in 0 until w.size) assertEquals(w.loads[5][i], w.selected[i])
    }

    @Test
    fun theSumStaysAlignedWhenARacingWriterForcesADrop() {
        val h = FootHistory(capacity = 16)
        for (k in 0 until 20) h.append(state(k * 10 * ms, k))
        val w = HistoryWindow(16)
        var next = 20
        // 8 more samples during the copy: the first copied sample is suspect and dropped (see HistoryTest).
        w.beforeRecheck = { repeat(8) { h.append(state(next * 10 * ms, next++)) } }
        w.copy(h, nowNanos = 190 * ms, spanNanos = 100_000 * ms, selection = mask(2, 4, 6))
        assertEquals(7, w.size)
        for (i in 0 until w.size) {
            assertEquals(w.loads[1][i] + w.loads[3][i] + w.loads[5][i], w.selected[i], 1e-4f)
        }
        assertEquals(3f * 13 + 90f, w.selected[0], 1e-4f)
    }

    @Test
    fun bothFeetGetTheSameSelectionAndAFootWithoutDataHidesNothing() {
        val history = LiveHistory(capacity = 64)
        for (k in 0 until 10) history.left.append(state(k * 10 * ms, k, Foot.LEFT))
        val frame = ChartFrame(capacity = 32)
        frame.update(history, nowNanos = 90 * ms, spanSeconds = 5, selection = mask(5))
        assertEquals(mask(5), frame.right.selection)
        assertEquals(0, frame.right.size)
        assertEquals(9f + 40f, frame.maxSelected(), 1e-4f)
        frame.update(LiveHistory(capacity = 8), 0, 5, mask(5))
        assertTrue(frame.maxSelected().isNaN())
    }

    private fun timeline(samples: Int, feet: Set<Foot> = setOf(Foot.LEFT), loads: (Foot, Int) -> DoubleArray) =
        SessionTimelineBuilder().apply {
            for (k in 0 until samples) {
                for (foot in feet) {
                    val l = loads(foot, k)
                    add(
                        CsvRow(
                            tMillis = k * 5.0,
                            foot = foot,
                            resistanceOhms = DoubleArray(SENSOR_COUNT) { 1e5 },
                            loads = l,
                            totalLoad = l.sum(),
                            cop = Point(0.0, 0.0),
                            rawCop = Point(0.0, 0.0),
                            inContact = true,
                            baseline = BaselineSource.AUTO,
                        ),
                    )
                }
            }
        }.build(mapOf("smartsocks-csv" to "2", "sample_rate_hz" to "200"))

    @Test
    fun aDecimatedPlaybackWindowSumsTheSamplesItTakes() {
        // 2000 samples at 200 Hz (10 s) into a window of 100: every 20th sample.
        val t: SessionTimeline = timeline(2000) { _, k -> loadsOf(k) }
        val w = HistoryWindow(100)
        w.copy(t.track(Foot.LEFT), endMillis = 9_995.0, spanMillis = 10_000.0, maxGapMillis = 50.0, mask(1, 2, 3))
        assertEquals(100, w.size)
        for (i in 0 until w.size) {
            val k = (w.t[i] * 1000 + 9_995.0) / 5.0 // the sample taken
            assertEquals((3 * k + 30).toFloat(), w.selected[i], 1e-2f)
            assertEquals(w.loads[0][i] + w.loads[1][i] + w.loads[2][i], w.selected[i], 1e-3f)
        }
        // Decimation keeps the line whole: no breaks after the first point although samples are 100 ms apart.
        assertTrue((1 until w.size).none { w.breaks[it] })
    }

    @Test
    fun aLoadMissingFromTheRecordingCountsAsZeroInTheSum() {
        val t = timeline(3) { _, k ->
            doubleArrayOf(if (k == 1) Double.NaN else 10.0, 5.0, Double.POSITIVE_INFINITY, 1.0, 0.0, 0.0)
        }
        val w = HistoryWindow(16)
        w.copy(t.track(Foot.LEFT), endMillis = 10.0, spanMillis = 1000.0, maxGapMillis = 50.0, mask(1, 2, 3))
        assertEquals(listOf(15f, 5f, 15f), (0 until w.size).map { w.selected[it] })
        assertTrue(w.loads[0][1].isNaN(), "the sensor's own line keeps its gap")
    }

    @Test
    fun theOverviewKnowsThePeakOfEverySubsetOverTheSession() {
        // The peak of a sum is not the sum of the peaks: sensor 1 peaks at sample 0, sensor 2 at sample 1.
        val peaks = mapOf(
            (Foot.LEFT to 0) to doubleArrayOf(10.0, 0.0, 0.0, 0.0, 0.0, 0.0),
            (Foot.LEFT to 1) to doubleArrayOf(0.0, 8.0, 0.0, 0.0, 0.0, 1.0),
            (Foot.RIGHT to 0) to doubleArrayOf(4.0, 4.0, 0.0, 3.0, 0.0, 0.0),
            (Foot.RIGHT to 1) to doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0),
        )
        val o = PlaybackOverview.of(timeline(2, Foot.entries.toSet()) { foot, k -> peaks.getValue(foot to k) }, 4)
        assertEquals(10f, o.maxSelectedLoad(mask(1, 2)), "not 18")
        assertEquals(11f, o.maxSelectedLoad(mask(1, 2, 4)), "the right foot: 4 + 4 + 3")
        assertEquals(10f, o.maxSensorLoad(mask(1, 2)))
        assertEquals(8f, o.maxSensorLoad(mask(2, 6)))
        assertEquals(10f, o.maxSensorLoad)
        assertEquals(0f, o.maxSelectedLoad(mask(3, 5)))
        assertEquals(11f, o.maxSelectedLoad(SensorSelection.ALL_MASK))
    }
}

package com.smartsocks.core.model

import com.smartsocks.core.protocol.SENSOR_COUNT

enum class Foot { LEFT, RIGHT }

/**
 * Insole coordinates, viewed from above: +y towards the toes, +x to the right of the screen.
 * Units are arbitrary but shared by layout and COP output.
 */
data class Point(val x: Double, val y: Double)

/**
 * Sensor positions of the LEFT insole; the right insole is the mirror image, so a channel sits on the same anatomical
 * side of both feet (the legacy app did the same: the right foot's picture mirrored, labels swapped 1↔2, 3↔4, 5↔6).
 *
 * Coordinates are viewed from above with +x to the person's right on both feet (see [Point]). On the LEFT insole
 * that is towards the other foot, so **+x is medial** (big-toe side) and −x lateral; on the right insole it is the
 * other way round. Everything that names or models a side ([isMedial], the app's sensor names, the simulator)
 * derives it from the positions here, never from channel numbers.
 *
 * The sensors form three rows along the foot (forefoot, midfoot, heel), each with one medial and one lateral sensor:
 * the two most forward sensors are the forefoot row, the two rearmost the heel row ([region], [rowY]).
 *
 * TODO(hardware): replace with measured positions (mm) and confirm which physical sensor is channel 1..6.
 */
class SensorLayout(private val leftPositions: List<Point>) {
    private val regions: Array<FootRegion>
    private val rowYs = DoubleArray(FootRegion.entries.size)

    init {
        require(leftPositions.size == SENSOR_COUNT) { "Expected $SENSOR_COUNT sensor positions" }
        require(leftPositions.all { it.x.isFinite() && it.y.isFinite() }) { "Sensor positions must be finite" }
        // Front to back (a stable sort: ties keep channel order), two sensors per row.
        val order = leftPositions.indices.sortedByDescending { leftPositions[it].y }
        val rows = arrayOfNulls<FootRegion>(SENSOR_COUNT)
        for (region in FootRegion.entries) {
            val a = order[2 * region.ordinal]
            val b = order[2 * region.ordinal + 1]
            require(
                (leftPositions[a].x > 0) != (leftPositions[b].x > 0) && leftPositions[a].x != 0.0 &&
                    leftPositions[b].x != 0.0,
            ) {
                "Each row needs one medial (+x) and one lateral (−x) sensor: channels ${a + 1} and ${b + 1}"
            }
            rows[a] = region
            rows[b] = region
            rowYs[region.ordinal] = (leftPositions[a].y + leftPositions[b].y) / 2
        }
        regions = Array(SENSOR_COUNT) { rows[it]!! }
    }

    fun positions(foot: Foot): List<Point> = when (foot) {
        Foot.LEFT -> leftPositions
        Foot.RIGHT -> leftPositions.map { Point(-it.x, it.y) }
    }

    /** Channel [index] (0-based) sits on the medial (big-toe) side of either foot. */
    fun isMedial(index: Int): Boolean = leftPositions[index].x > 0

    /** The row of channel [index] (0-based). */
    fun region(index: Int): FootRegion = regions[index]

    /** The mean position along the foot (y) of [region]'s two sensors. */
    fun rowY(region: FootRegion): Double = rowYs[region.ordinal]

    /** This layout with medial and lateral swapped: every x negated. */
    fun mirrored(): SensorLayout = SensorLayout(positions(Foot.RIGHT))

    override fun equals(other: Any?): Boolean = other is SensorLayout && other.leftPositions == leftPositions

    override fun hashCode(): Int = leftPositions.hashCode()

    override fun toString(): String = "SensorLayout($leftPositions)"

    companion object {
        /**
         * The assumption about the hardware, in one place: channels 1, 3, 5 are medial and 2, 4, 6 lateral on both
         * feet. Unconfirmed on real socks; if the hardware turns out the other way round, set this to false and every
         * view, name, COP and the simulator follow (recordings made before keep their own layout, see
         * `CsvSessionReader`).
         */
        const val ODD_CHANNELS_MEDIAL = true

        /**
         * The 2x3 grid the legacy app encoded as angles (alpha = 75°): x = ±cos 75°, y = ±sin 75° or 0.
         * Order: 1 front, 2 front, 3 mid, 4 mid, 5 heel, 6 heel; [oddMedial]: odd channels on the medial side, i.e.
         * at +x on the left insole.
         */
        fun legacy(oddMedial: Boolean): SensorLayout {
            val m = if (oddMedial) 0.259 else -0.259
            return SensorLayout(
                listOf(
                    Point(m, 0.966),
                    Point(-m, 0.966),
                    Point(m, 0.0),
                    Point(-m, 0.0),
                    Point(m, -0.966),
                    Point(-m, -0.966),
                ),
            )
        }

        /** The layout in use: [legacy] with [ODD_CHANNELS_MEDIAL]. */
        val Legacy = legacy(ODD_CHANNELS_MEDIAL)
    }
}

/** A row of sensors along the foot, front to back. */
enum class FootRegion { FORE, MID, HEEL }

/** One decoded sample: resistance of every sensor in Ohms, stamped with host monotonic time. */
class SensorFrame(val foot: Foot, val timestampNanos: Long, val resistanceOhms: DoubleArray) {
    init {
        require(resistanceOhms.size == SENSOR_COUNT)
    }
}

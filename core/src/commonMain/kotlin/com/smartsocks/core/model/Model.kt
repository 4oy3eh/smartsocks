package com.smartsocks.core.model

import com.smartsocks.core.protocol.SENSOR_COUNT

enum class Foot { LEFT, RIGHT }

/**
 * Insole coordinates, viewed from above: +y towards the toes, +x to the right of the screen.
 * Units are arbitrary but shared by layout and COP output.
 */
data class Point(val x: Double, val y: Double)

/**
 * Sensor positions of the LEFT insole; the right insole is the mirror image.
 *
 * TODO(hardware): replace with measured positions (mm) and confirm which physical sensor is channel 1..6.
 */
class SensorLayout(private val leftPositions: List<Point>) {
    init {
        require(leftPositions.size == SENSOR_COUNT) { "Expected $SENSOR_COUNT sensor positions" }
    }

    fun positions(foot: Foot): List<Point> = when (foot) {
        Foot.LEFT -> leftPositions
        Foot.RIGHT -> leftPositions.map { Point(-it.x, it.y) }
    }

    companion object {
        /**
         * The 2x3 grid the legacy app encoded as angles (alpha = 75°): x = ±cos 75°, y = ±sin 75° or 0.
         * Order: 1 front +x, 2 front -x, 3 mid +x, 4 mid -x, 5 heel +x, 6 heel -x.
         */
        val Legacy = SensorLayout(
            listOf(
                Point(0.259, 0.966),
                Point(-0.259, 0.966),
                Point(0.259, 0.0),
                Point(-0.259, 0.0),
                Point(0.259, -0.966),
                Point(-0.259, -0.966),
            ),
        )
    }
}

/** One decoded sample: resistance of every sensor in Ohms, stamped with host monotonic time. */
class SensorFrame(val foot: Foot, val timestampNanos: Long, val resistanceOhms: DoubleArray) {
    init {
        require(resistanceOhms.size == SENSOR_COUNT)
    }
}

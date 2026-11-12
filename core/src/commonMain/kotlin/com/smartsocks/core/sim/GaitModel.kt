package com.smartsocks.core.sim

import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.SENSOR_COUNT
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class SimMode {
    /** Normal walking: heel strike → midfoot → forefoot → toe-off, feet half a stride apart. */
    Walking,

    /** Quiet standing with slow postural sway. */
    Standing,

    /** Foot in the air: use this to calibrate. */
    Lifted,
}

/**
 * Plausible (not biomechanically exact) per-sensor forces in Newtons for one foot.
 *
 * Channels 1, 3, 5 (index 0, 2, 4) are assumed medial (big-toe side), 2, 4, 6 lateral; rows are forefoot, midfoot,
 * heel — matching [com.smartsocks.core.model.SensorLayout.Legacy].
 */
class GaitModel(
    private val foot: Foot,
    private val bodyWeightKg: Double = 70.0,
    private val cadenceStepsPerMin: Double = 100.0,
) {
    private val weightN get() = bodyWeightKg * 9.81

    fun forces(tSeconds: Double, mode: SimMode, out: DoubleArray = DoubleArray(SENSOR_COUNT)): DoubleArray {
        when (mode) {
            SimMode.Lifted -> out.fill(0.0)
            SimMode.Walking -> walking(tSeconds, out)
            SimMode.Standing -> standing(tSeconds, out)
        }
        return out
    }

    private fun walking(t: Double, out: DoubleArray) {
        val strideSeconds = 120.0 / cadenceStepsPerMin
        val offset = if (foot == Foot.LEFT) 0.0 else 0.5
        val phase = ((t / strideSeconds) + offset).mod(1.0)
        if (phase >= STANCE_FRACTION) {
            out.fill(0.0)
            return
        }
        val p = phase / STANCE_FRACTION
        val heel = 1.1 * weightN * bump(p, 0.15, 0.11)
        val mid = 0.35 * weightN * bump(p, 0.45, 0.18)
        val fore = 1.1 * weightN * bump(p, 0.75, 0.12)
        // Weight rolls from the lateral heel to the medial forefoot (big toe) at push-off.
        val foreMedial = 0.4 + 0.4 * smoothstep(0.6, 0.95, p)
        split(out, fore, foreMedial, heel = heel, heelMedial = 0.45, mid = mid, midMedial = 0.3)
    }

    private fun standing(t: Double, out: DoubleArray) {
        val sideShift = 0.05 * sin(2 * PI * 0.1 * t)
        val share = 0.5 + if (foot == Foot.LEFT) sideShift else -sideShift
        val load = share * weightN
        val ap = 0.08 * sin(2 * PI * 0.25 * t) + 0.03 * sin(2 * PI * 0.7 * t + 1.0)
        val ml = 0.06 * sin(2 * PI * 0.33 * t + 0.5)
        split(
            out,
            fore = load * (0.35 + ap),
            foreMedial = 0.5 + ml,
            heel = load * (0.5 - ap),
            heelMedial = 0.5 + ml,
            mid = load * 0.15,
            midMedial = 0.4 + ml,
        )
    }

    private fun split(
        out: DoubleArray,
        fore: Double,
        foreMedial: Double,
        heel: Double,
        heelMedial: Double,
        mid: Double,
        midMedial: Double,
    ) {
        out[0] = fore * foreMedial
        out[1] = fore * (1 - foreMedial)
        out[2] = mid * midMedial
        out[3] = mid * (1 - midMedial)
        out[4] = heel * heelMedial
        out[5] = heel * (1 - heelMedial)
    }

    private fun bump(x: Double, center: Double, width: Double): Double {
        val d = (x - center) / width
        return exp(-d * d)
    }

    private fun smoothstep(from: Double, to: Double, x: Double): Double {
        val k = ((x - from) / (to - from)).coerceIn(0.0, 1.0)
        return k * k * (3 - 2 * k)
    }

    private companion object {
        const val STANCE_FRACTION = 0.62
    }
}

/** Force-sensitive resistor: conductance grows linearly with force on top of a small leakage. */
object FsrModel {
    const val LEAK_MICROSIEMENS = 0.5
    const val MICROSIEMENS_PER_NEWTON = 2.5

    fun ohms(forceN: Double, gain: Double = 1.0): Double =
        1e6 / (LEAK_MICROSIEMENS + MICROSIEMENS_PER_NEWTON * gain * forceN.coerceAtLeast(0.0))
}

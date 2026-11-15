package com.smartsocks.app.live

import com.smartsocks.core.analytics.BalanceResult
import com.smartsocks.core.analytics.FootGains
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the settings say about a balance calibration. */
object BalanceTexts {
    /**
     * "The right insole read 12 % more than the left one: corrected." The difference is between the two insoles
     * (right against left); without a correction (see [com.smartsocks.core.analytics.BalanceLimits.neutralBand]) they
     * read the same.
     */
    fun result(result: BalanceResult.Calibrated): String {
        val ratio = result.rightToLeft
        val percent = ((if (ratio >= 1) ratio else 1 / ratio) - 1) * 100
        val rounded = percent.roundToInt()
        if (result.gains.isNeutral || rounded == 0) return "Both insoles read the same: no correction needed."
        val side = if (ratio >= 1) "right" else "left"
        val other = if (ratio >= 1) "left" else "right"
        return "The $side insole read $rounded % more than the $other one: corrected."
    }

    /** "Left ×1.058 · right ×0.945". */
    fun gains(gains: FootGains): String = "Left ×${factor(gains.left)} · right ×${factor(gains.right)}"

    private fun factor(value: Double): String {
        val thousandths = (value * 1000).roundToInt()
        return "${thousandths / 1000}.${abs(thousandths % 1000).toString().padStart(3, '0')}"
    }
}

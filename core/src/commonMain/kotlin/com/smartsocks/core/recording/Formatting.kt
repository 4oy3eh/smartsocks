package com.smartsocks.core.recording

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

private const val HEX = "0123456789abcdef"

/** Locale-independent fixed-point text: '.' separator, `inf` / `-inf` / `nan` for non-finite values. */
internal fun formatNumber(value: Double, decimals: Int): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    val scale = 10.0.pow(decimals)
    if (abs(value) * scale >= 9e18) return value.toString()
    val scaled = (abs(value) * scale).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return "$sign$scaled"
    val digits = scaled.toString().padStart(decimals + 1, '0')
    val split = digits.length - decimals
    return "$sign${digits.substring(0, split)}.${digits.substring(split)}"
}

/** Nanoseconds as milliseconds with 3 decimals, exact integer arithmetic. */
internal fun formatMillis(nanos: Long): String {
    val micros = (abs(nanos) + 500) / 1000
    val sign = if (nanos < 0 && micros != 0L) "-" else ""
    return "$sign${micros / 1000}.${(micros % 1000).toString().padStart(3, '0')}"
}

internal fun parseNumber(text: String): Double = when (text) {
    "inf" -> Double.POSITIVE_INFINITY
    "-inf" -> Double.NEGATIVE_INFINITY
    "nan" -> Double.NaN
    else -> text.toDouble()
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

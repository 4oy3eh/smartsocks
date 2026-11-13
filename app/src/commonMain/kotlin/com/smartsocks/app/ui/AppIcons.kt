package com.smartsocks.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The few icons the app needs, drawn here instead of pulling in the (frozen) material-icons artifact. */
object AppIcons {
    /** A gear: eight teeth around a ring, with a hole (even-odd fill). */
    val Settings: ImageVector by lazy {
        ImageVector.Builder("Settings", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                val teeth = 8
                val outer = 10.2f
                val inner = 7.6f
                val step = 2 * PI / teeth
                for (i in 0 until teeth) {
                    val a = i * step
                    // Tooth: flat top spanning ±0.22 rad, flanks down to the root circle.
                    val points = listOf(
                        inner to a - step / 2 + 0.12,
                        outer to a - 0.24,
                        outer to a + 0.24,
                        inner to a + step / 2 - 0.12,
                    )
                    points.forEachIndexed { k, (r, angle) ->
                        val x = 12f + r * cos(angle).toFloat()
                        val y = 12f + r * sin(angle).toFloat()
                        if (i == 0 && k == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                close()
                // Hole.
                moveTo(12f + 3.2f, 12f)
                arcTo(3.2f, 3.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 12f - 3.2f, 12f)
                arcTo(3.2f, 3.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 12f + 3.2f, 12f)
                close()
            }
        }.build()
    }

    /** Pointing down; rotate it for "collapse". */
    val Chevron: ImageVector by lazy {
        ImageVector.Builder("Chevron", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 9f)
                lineTo(12f, 15f)
                lineTo(18f, 9f)
            }
        }.build()
    }

    val Back: ImageVector by lazy {
        ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(19f, 12f)
                lineTo(5f, 12f)
                moveTo(11f, 6f)
                lineTo(5f, 12f)
                lineTo(11f, 18f)
            }
        }.build()
    }

    /** Two bars. */
    val Pause: ImageVector by lazy {
        ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 5f)
                lineTo(10f, 5f)
                lineTo(10f, 19f)
                lineTo(7f, 19f)
                close()
                moveTo(14f, 5f)
                lineTo(17f, 5f)
                lineTo(17f, 19f)
                lineTo(14f, 19f)
                close()
            }
        }.build()
    }

    /** A triangle pointing right. */
    val Play: ImageVector by lazy {
        ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), strokeLineJoin = StrokeJoin.Round) {
                moveTo(8f, 5f)
                lineTo(19f, 12f)
                lineTo(8f, 19f)
                close()
            }
        }.build()
    }
}

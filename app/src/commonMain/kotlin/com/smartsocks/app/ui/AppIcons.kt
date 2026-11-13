package com.smartsocks.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
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

    /** A filled dot: record. */
    val Record: ImageVector by lazy {
        icon("Record") {
            path(fill = SolidColor(Color.Black)) { circle(12f, 12f, 6.5f) }
        }
    }

    /** A filled square: stop. */
    val Stop: ImageVector by lazy {
        icon("Stop") { path(fill = SolidColor(Color.Black)) { rect(6.5f, 6.5f, 17.5f, 17.5f) } }
    }

    /** A flag on a pole: a marker. */
    val Flag: ImageVector by lazy {
        icon("Flag") {
            stroked {
                moveTo(6f, 21f)
                lineTo(6f, 4f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 4f)
                lineTo(18f, 4f)
                lineTo(15f, 8f)
                lineTo(18f, 12f)
                lineTo(6f, 12f)
                close()
            }
        }
    }

    /** Three lines with bullets: the sessions list. */
    val Sessions: ImageVector by lazy {
        icon("Sessions") {
            path(fill = SolidColor(Color.Black)) {
                circle(5f, 6.5f, 1.6f)
                circle(5f, 12f, 1.6f)
                circle(5f, 17.5f, 1.6f)
            }
            stroked {
                moveTo(9.5f, 6.5f)
                lineTo(20f, 6.5f)
                moveTo(9.5f, 12f)
                lineTo(20f, 12f)
                moveTo(9.5f, 17.5f)
                lineTo(20f, 17.5f)
            }
        }
    }

    /** A bar and a triangle pointing left: one frame back. */
    val FrameBack: ImageVector by lazy { frame(back = true) }

    val FrameForward: ImageVector by lazy { frame(back = false) }

    /** Two triangles and a bar: previous step (heel strike). */
    val StepBack: ImageVector by lazy { skip(back = true) }

    val StepForward: ImageVector by lazy { skip(back = false) }

    val Folder: ImageVector by lazy {
        icon("Folder") {
            stroked {
                moveTo(3.5f, 7f)
                lineTo(3.5f, 18.5f)
                lineTo(20.5f, 18.5f)
                lineTo(20.5f, 8.5f)
                lineTo(11.5f, 8.5f)
                lineTo(9.5f, 5.5f)
                lineTo(3.5f, 5.5f)
                close()
            }
        }
    }

    /** Three connected dots. */
    val Share: ImageVector by lazy {
        icon("Share") {
            path(fill = SolidColor(Color.Black)) {
                circle(17.5f, 5.5f, 2.5f)
                circle(6.5f, 12f, 2.5f)
                circle(17.5f, 18.5f, 2.5f)
            }
            stroked {
                moveTo(17.5f, 5.5f)
                lineTo(6.5f, 12f)
                lineTo(17.5f, 18.5f)
            }
        }
    }

    /** Three vertical dots: more actions. */
    val More: ImageVector by lazy {
        icon("More") {
            path(fill = SolidColor(Color.Black)) {
                circle(12f, 5.5f, 1.8f)
                circle(12f, 12f, 1.8f)
                circle(12f, 18.5f, 1.8f)
            }
        }
    }

    val Close: ImageVector by lazy {
        icon("Close") {
            stroked {
                moveTo(6f, 6f)
                lineTo(18f, 18f)
                moveTo(18f, 6f)
                lineTo(6f, 18f)
            }
        }
    }

    private fun frame(back: Boolean): ImageVector = icon(if (back) "FrameBack" else "FrameForward") {
        val m = if (back) -1f else 1f
        path(fill = SolidColor(Color.Black)) {
            // Triangle towards the bar.
            moveTo(12f - 5f * m, 6f)
            lineTo(12f + 3f * m, 12f)
            lineTo(12f - 5f * m, 18f)
            close()
            rect(minOf(12f + 4f * m, 12f + 6f * m), 6f, maxOf(12f + 4f * m, 12f + 6f * m), 18f)
        }
    }

    private fun skip(back: Boolean): ImageVector = icon(if (back) "StepBack" else "StepForward") {
        val m = if (back) -1f else 1f
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f - 8f * m, 6f)
            lineTo(12f - 1f * m, 12f)
            lineTo(12f - 8f * m, 18f)
            close()
            moveTo(12f - 1f * m, 6f)
            lineTo(12f + 6f * m, 12f)
            lineTo(12f - 1f * m, 18f)
            close()
            rect(minOf(12f + 6.5f * m, 12f + 8.5f * m), 6f, maxOf(12f + 6.5f * m, 12f + 8.5f * m), 18f)
        }
    }

    private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private fun ImageVector.Builder.stroked(block: PathBuilder.() -> Unit) {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        )
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx + r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = false, cx + r, cy)
        close()
    }

    private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
        moveTo(l, t)
        lineTo(r, t)
        lineTo(r, b)
        lineTo(l, b)
        close()
    }
}

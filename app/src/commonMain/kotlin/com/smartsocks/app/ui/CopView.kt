package com.smartsocks.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import com.smartsocks.app.live.FootView
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveGeometry
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.core.model.Foot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Insole outline of the LEFT foot in layout units (+x medial, i.e. towards the other foot; +y towards the toes),
 * wide enough to contain the legacy 2x3 sensor grid. The right insole is its mirror image.
 */
private object Insole {
    const val MIN_X = -0.5f
    const val MAX_X = 0.5f
    const val MIN_Y = -1.36f
    const val MAX_Y = 1.46f

    fun path(mirror: Boolean): Path {
        val s = if (mirror) -1f else 1f
        return Path().apply {
            moveTo(0f, -1.36f)
            cubicTo(0.24f * s, -1.36f, 0.41f * s, -1.2f, 0.41f * s, -0.92f) // medial heel
            cubicTo(0.41f * s, -0.55f, 0.36f * s, -0.3f, 0.37f * s, 0.05f) // arch
            cubicTo(0.38f * s, 0.4f, 0.49f * s, 0.6f, 0.49f * s, 0.98f) // ball
            cubicTo(0.49f * s, 1.3f, 0.34f * s, 1.46f, 0.14f * s, 1.46f) // big toe
            cubicTo(-0.08f * s, 1.46f, -0.3f * s, 1.34f, -0.42f * s, 1.12f) // smaller toes
            cubicTo(-0.5f * s, 0.95f, -0.5f * s, 0.7f, -0.47f * s, 0.48f) // lateral forefoot
            cubicTo(-0.44f * s, 0.1f, -0.42f * s, -0.45f, -0.41f * s, -0.9f) // lateral midfoot
            cubicTo(-0.4f * s, -1.2f, -0.24f * s, -1.36f, 0f, -1.36f) // heel
            close()
        }
    }
}

/** Maps layout units to pixels: x right, y up (towards the toes), origin between the feet. */
private class Mapping(size: Size) {
    private val halfGap = LiveGeometry.STANCE_HALF_WIDTH.toFloat()
    private val widthUnits = 2 * (halfGap + Insole.MAX_X) + 0.2f
    private val heightUnits = (Insole.MAX_Y - Insole.MIN_Y) + LABEL_UNITS + 0.15f

    val scale = min(size.width / widthUnits, size.height / heightUnits)
    private val originX = size.width / 2
    private val centerY = (Insole.MAX_Y + Insole.MIN_Y) / 2

    // The block (insole + label below) is centred vertically.
    private val originY = size.height / 2 - LABEL_UNITS * scale / 2 + centerY * scale

    fun x(u: Double) = originX + u.toFloat() * scale
    fun y(u: Double) = originY - u.toFloat() * scale
    fun at(x: Double, y: Double) = Offset(x(x), y(y))
    fun footCenterX(foot: Foot) = if (foot == Foot.LEFT) -halfGap else halfGap
    fun labelTop() = y(Insole.MIN_Y.toDouble()) + 0.12f * scale
    fun units(u: Float) = u * scale

    companion object {
        const val LABEL_UNITS = 0.62f
    }
}

/** Both insoles with sensor loads, each foot's COP with a fading trail, and the combined COP between them. */
@Composable
fun CopView(snapshot: State<LiveSnapshot>, modifier: Modifier = Modifier) {
    val colors = LocalSockColors.current
    val accent = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelLarge
    val stateStyle = MaterialTheme.typography.labelMedium
    val measurer = rememberTextMeasurer()
    val leftPath = remember { Insole.path(mirror = false) }
    val rightPath = remember { Insole.path(mirror = true) }

    Canvas(modifier) {
        // Read in the draw phase only: a new snapshot redraws, it never recomposes.
        val frame = snapshot.value
        val m = Mapping(size)
        for (foot in Foot.entries) {
            drawFoot(
                view = frame.foot(foot),
                path = if (foot == Foot.LEFT) leftPath else rightPath,
                m = m,
                fullScale = frame.fullScaleLoad,
                colors = colors,
                accent = accent,
            )
            drawFootLabel(frame.foot(foot), m, measurer, labelStyle.copy(color = onSurface), stateStyle, colors, muted)
        }
        val combined = frame.balance?.combinedCop
        // With one foot in the air the combined COP is that foot's COP: only worth a marker when both carry load.
        if (combined != null && frame.left.state?.inContact == true && frame.right.state?.inContact == true) {
            val c = m.at(combined.x, combined.y)
            val r = m.units(0.075f)
            drawLine(accent.copy(alpha = 0.35f), Offset(c.x - 2.2f * r, c.y), Offset(c.x + 2.2f * r, c.y), 1.5f)
            drawLine(accent.copy(alpha = 0.35f), Offset(c.x, c.y - 2.2f * r), Offset(c.x, c.y + 2.2f * r), 1.5f)
            drawCircle(accent, r, c, style = Stroke(width = m.units(0.03f).coerceAtLeast(2f)))
        }
    }
}

private fun DrawScope.drawFoot(
    view: FootView,
    path: Path,
    m: Mapping,
    fullScale: Double,
    colors: SockColors,
    accent: Color,
) {
    val state = view.state
    val live = state != null
    val cx = m.footCenterX(view.foot)
    val alpha = if (live) 1f else 0.45f

    // Insole: the path is in layout units around the foot centre, y up.
    withTransform({
        translate(m.x(cx.toDouble()), m.y(0.0))
        scale(m.scale, -m.scale, pivot = Offset.Zero)
    }) {
        drawPath(path, colors.insole.copy(alpha = if (live) 1f else 0.6f))
        drawPath(path, colors.insoleEdge.copy(alpha = alpha), style = Stroke(width = 1.5f / m.scale))
    }

    val positions = LiveGeometry.layout.positions(view.foot)
    for (i in positions.indices) {
        val p = positions[i]
        val center = m.at(cx + p.x, p.y)
        val t = if (state != null) {
            sqrt((state.loads[i] / fullScale).coerceIn(0.0, 1.0)).toFloat()
        } else {
            0f
        }
        val radius = m.units(0.075f + 0.055f * t)
        if (state == null) {
            drawCircle(colors.sensorEmpty.copy(alpha = 0.8f), m.units(0.075f), center, style = Stroke(1.5f))
        } else {
            drawCircle(lerp(colors.sensorEmpty, accent, t), radius, center)
        }
    }

    if (state == null) return
    drawTrail(view, cx, m, colors.cop)
    val cop = state.cop ?: return
    val c = m.at(cx + cop.x, cop.y)
    val r = m.units(0.05f).coerceAtLeast(4f)
    drawCircle(colors.insole, r + m.units(0.018f).coerceAtLeast(1.5f), c)
    drawCircle(colors.cop, r, c)
}

/**
 * The COP trail, fading with age. Points are grouped into a few age bands, each stroked as one path: separate
 * translucent segments would overlap at every joint and show up as beads along the line.
 */
private fun DrawScope.drawTrail(view: FootView, cx: Float, m: Mapping, color: Color) {
    val trail = view.trail
    if (trail.size < 2) return
    val width = m.units(0.022f).coerceAtLeast(1.5f)
    var k = 1
    while (k < trail.size) {
        if (trail[k].startsSegment) {
            k++
            continue
        }
        val band = ageBand(trail[k].age)
        val path = Path()
        val from = trail[k - 1]
        path.moveTo(m.x(cx + from.x), m.y(from.y))
        while (k < trail.size && !trail[k].startsSegment && ageBand(trail[k].age) == band) {
            path.lineTo(m.x(cx + trail[k].x), m.y(trail[k].y))
            k++
        }
        val fade = 1f - (band + 0.5f) / TRAIL_BANDS
        drawPath(
            path,
            color.copy(alpha = 0.6f * fade * fade),
            style = Stroke(width * (0.4f + 0.6f * fade), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private const val TRAIL_BANDS = 6

private fun ageBand(age: Float): Int = (age * TRAIL_BANDS).toInt().coerceAtMost(TRAIL_BANDS - 1)

private fun DrawScope.drawFootLabel(
    view: FootView,
    m: Mapping,
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    stateStyle: TextStyle,
    colors: SockColors,
    muted: Color,
) {
    val cx = m.x(m.footCenterX(view.foot).toDouble())
    val width = m.units(1.5f).toInt().coerceAtLeast(1)
    val name = if (view.foot == Foot.LEFT) "Left" else "Right"
    val state = view.status.state
    val (detail, color) = when (state) {
        LinkState.Streaming ->
            (if (view.state?.inContact == false) "In the air" else view.status.deviceName ?: "Streaming") to muted

        else -> state.label() to colors.forState(state)
    }
    val top = m.labelTop()
    val title = measurer.measure(
        name,
        labelStyle.copy(textAlign = TextAlign.Center),
        constraints = Constraints.fixedWidth(width),
    )
    drawText(title, topLeft = Offset(cx - width / 2f, top))
    val sub = measurer.measure(
        detail,
        stateStyle.copy(color = color, textAlign = TextAlign.Center),
        overflow = TextOverflow.Ellipsis,
        maxLines = 3,
        constraints = Constraints.fixedWidth(width),
    )
    drawText(sub, topLeft = Offset(cx - width / 2f, top + title.size.height))
}

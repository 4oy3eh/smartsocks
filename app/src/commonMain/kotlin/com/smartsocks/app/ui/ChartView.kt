package com.smartsocks.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.AutoScale
import com.smartsocks.app.live.ChartFrame
import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.HistoryWindow
import com.smartsocks.app.live.LineReducer
import com.smartsocks.app.live.LineSink
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.LiveGeometry
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.app.live.NiceNumbers
import com.smartsocks.app.live.SensorNames
import com.smartsocks.app.settings.fixed
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.SENSOR_COUNT
import com.smartsocks.core.settings.ChartSettings
import com.smartsocks.core.settings.ChartWindow
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Per frame: the copied windows of both feet and the y scales. Owned by the UI thread: [update] runs in the frame
 * callback, the canvases read it while drawing the same frame.
 */
private class ChartModel {
    val frame = ChartFrame()
    val total = AutoScale(floor = 50f)
    val sensors = AutoScale(floor = 20f)

    /** [fitScales]: fit the y scales to this frame at once (paused, or another window or foot), no hysteresis. */
    fun update(controller: LiveController, nowNanos: Long, seconds: Int, sensorFoot: Foot, fitScales: Boolean) {
        frame.update(controller.history, nowNanos, seconds)
        val totalMax = frame.maxTotal()
        val sensorMax = frame.foot(sensorFoot).maxLoad()
        if (fitScales) {
            total.reset(totalMax)
            sensors.reset(sensorMax)
        } else {
            total.update(totalMax, nowNanos)
            sensors.update(sensorMax, nowNanos)
        }
    }
}

/** Line styles in pixels for the current density: created once, not per frame. */
private class ChartStrokes(density: Density) {
    val total = with(density) { lineStroke(2.dp.toPx()) }
    val sensor = with(density) { lineStroke(1.5.dp.toPx()) }

    private fun lineStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)
}

/**
 * Measured axis labels by text, so that a frame measures (and allocates) nothing for labels it drew before. The
 * texts are few: grid values of the current scales, time marks, row names; the cache is emptied if it ever grows.
 */
private class LabelCache(private val measurer: TextMeasurer, private val style: TextStyle) {
    private val layouts = HashMap<String, TextLayoutResult>()

    fun layout(text: String): TextLayoutResult {
        if (layouts.size > MAX_LABELS) layouts.clear()
        return layouts.getOrPut(text) { measurer.measure(text, style) }
    }

    private companion object {
        const val MAX_LABELS = 256
    }
}

/** The value-grid texts of one panel, rebuilt only when its scale or step changes. */
private class GridTexts {
    private var max = Float.NaN
    private var step = Float.NaN
    private var texts: Array<String> = emptyArray()

    /** Texts for 0, step, 2·step, ... up to [max]. */
    fun of(max: Float, step: Float): Array<String> {
        if (max == this.max && step == this.step) return texts
        this.max = max
        this.step = step
        val n = (max / step + 1e-3f).toInt() + 1
        texts = Array(n) {
            val v = it * step
            if (step >= 1f) v.roundToInt().toString() else fixed(v.toDouble(), 1)
        }
        return texts
    }
}

/** Collects [LineReducer] output into one reused [Path]. */
private class StrokeSink : LineSink {
    val path = Path()

    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)

    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
}

/** Drawing helpers shared by the three panels; one per chart, reused every frame. */
private class Plotter {
    val reducer = LineReducer()
    val sink = StrokeSink()
    val totalGrid = GridTexts()
    val sensorGrid = GridTexts()
}

/** Colours of the chart, resolved once per composition. */
private class ChartColors(
    val left: Color,
    val right: Color,
    val sensors: List<Color>,
    val grid: Color,
    val axis: Color,
    val label: Color,
    val contact: Color,
) {
    fun foot(foot: Foot) = if (foot == Foot.LEFT) left else right
}

/**
 * The live chart: total load per foot, the six sensors of one foot, and the COP's heel-to-toe position, stacked on
 * one time axis that scrolls right to left. It plots the controller's full-rate history: the data is copied once
 * per frame (only while a foot streams), Pause freezes the picture while recording goes on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChartView(
    controller: LiveController,
    snapshot: State<LiveSnapshot>,
    chart: ChartSettings,
    onChartChange: (ChartSettings) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    // Not saved: the frozen moment lives in [model], which a recreated screen does not have, so it starts live.
    var paused by remember { mutableStateOf(false) }
    val model = remember { ChartModel() }
    val plotter = remember { Plotter() }
    val tick = remember { mutableIntStateOf(0) }
    val truncated = remember { mutableStateOf(false) }
    val seconds = chart.window.seconds
    val sensorFoot = chart.sensorFoot

    LaunchedEffect(controller, paused, seconds, sensorFoot) {
        if (paused) {
            // Frozen at the moment of pausing; a new window or foot is re-read from the history at that moment, as
            // far as it is still there (otherwise the hint below says so).
            val now = model.frame.nowNanos.takeIf { it != 0L } ?: controller.nowNanos()
            model.update(controller, now, seconds, sensorFoot, fitScales = true)
            truncated.value = model.frame.truncated
            tick.intValue++
            return@LaunchedEffect
        }
        // Another window or foot (or resuming): fit the scales to it at once, then follow with hysteresis.
        var fit = true
        while (true) {
            withFrameNanos {
                val start = LivePerf.start()
                model.update(controller, controller.nowNanos(), seconds, sensorFoot, fit)
                LivePerf.work(start)
                fit = false
                truncated.value = model.frame.truncated
                tick.intValue++
            }
            val shown = snapshot.value
            if (!controller.isAnimating(shown)) controller.awaitStatusChange(shown)
        }
    }

    val scheme = MaterialTheme.colorScheme
    val sock = LocalSockColors.current
    val colors = ChartColors(
        left = scheme.primary,
        right = scheme.secondary,
        sensors = sock.series,
        grid = scheme.outlineVariant,
        axis = scheme.outline,
        label = scheme.onSurfaceVariant,
        contact = (if (sensorFoot == Foot.LEFT) scheme.primary else scheme.secondary).copy(alpha = 0.08f),
    )
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val baseLabel = MaterialTheme.typography.labelSmall
    val labels = remember(measurer, baseLabel, colors.label) {
        LabelCache(measurer, baseLabel.copy(color = colors.label, fontFeatureSettings = "tnum"))
    }
    val density = LocalDensity.current
    val strokes = remember(density) { ChartStrokes(density) }
    val leftStatus by remember { derivedStateOf { snapshot.value.left.status } }
    val rightStatus by remember { derivedStateOf { snapshot.value.right.status } }
    val names = SensorNames.Default

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.width(if (compact) 180.dp else 220.dp)) {
                val windows = ChartWindow.entries
                windows.forEachIndexed { i, w ->
                    SegmentedButton(
                        selected = w == chart.window,
                        onClick = { onChartChange(chart.copy(window = w)) },
                        shape = SegmentedButtonDefaults.itemShape(i, windows.size),
                        icon = {},
                        colors = segmentColors(),
                    ) { Text("${w.seconds} s", maxLines = 1) }
                }
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = { paused = !paused },
                contentPadding = PaddingValues(start = 14.dp, end = 18.dp),
            ) {
                Icon(
                    if (paused) AppIcons.Play else AppIcons.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (paused) "Resume" else "Pause")
            }
        }
        if (truncated.value) {
            Text(
                if (paused) "Older data is no longer available" else "Only the newest samples fit this window",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        val panelGap = if (compact) 6.dp else 10.dp
        ChartPanel(
            title = "Total load",
            unit = "µS",
            legend = {
                FootLegend(leftStatus, colors.left)
                FootLegend(rightStatus, colors.right)
            },
            modifier = Modifier.weight(1f),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                tick.intValue // a new frame redraws (draw phase only, no recomposition)
                val t0 = LivePerf.start()
                val plot = plotArea(withTimeLabels = false)
                val scale = model.total.max
                drawTimeGrid(plot, model.frame.spanSeconds, colors)
                val step = NiceNumbers.tickStep(scale, maxTicks(plot))
                drawValueGrid(plot, scale, step, plotter.totalGrid.of(scale, step), colors, labels)
                clipRect(plot.left, plot.top - 2f, plot.right, plot.bottom + 2f) {
                    val span = model.frame.spanSeconds
                    val l = model.frame.left
                    val r = model.frame.right
                    plotSeries(l, l.total, plot, span, 0f, scale, plotter, colors.left, strokes.total)
                    plotSeries(r, r.total, plot, span, 0f, scale, plotter, colors.right, strokes.total)
                }
                LivePerf.work(t0)
            }
        }
        Spacer(Modifier.height(panelGap))
        ChartPanel(
            title = "Sensors",
            unit = "µS",
            legend = {
                for (s in 0 until SENSOR_COUNT) LegendItem(names.short[s], colors.sensors[s])
            },
            action = {
                FootSelector(sensorFoot, onSelect = { onChartChange(chart.copy(sensorFoot = it)) })
            },
            legendBelow = compact,
            modifier = Modifier.weight(1.25f),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                tick.intValue
                val t0 = LivePerf.start()
                val plot = plotArea(withTimeLabels = false)
                val scale = model.sensors.max
                val w = model.frame.foot(sensorFoot)
                val span = model.frame.spanSeconds
                drawContact(w, plot, span, colors.contact)
                drawTimeGrid(plot, span, colors)
                val step = NiceNumbers.tickStep(scale, maxTicks(plot))
                drawValueGrid(plot, scale, step, plotter.sensorGrid.of(scale, step), colors, labels)
                clipRect(plot.left, plot.top - 2f, plot.right, plot.bottom + 2f) {
                    for (s in 0 until SENSOR_COUNT) {
                        plotSeries(w, w.loads[s], plot, span, 0f, scale, plotter, colors.sensors[s], strokes.sensor)
                    }
                }
                LivePerf.work(t0)
            }
        }
        Spacer(Modifier.height(panelGap))
        ChartPanel(
            title = "COP heel → toe",
            unit = null,
            legend = {
                LegendItem("Left", colors.left, muted = leftStatus.state != LinkState.Streaming)
                LegendItem("Right", colors.right, muted = rightStatus.state != LinkState.Streaming)
            },
            modifier = Modifier.weight(0.9f),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                tick.intValue
                val t0 = LivePerf.start()
                val plot = plotArea(withTimeLabels = true)
                val span = model.frame.spanSeconds
                drawTimeGrid(plot, span, colors)
                drawTimeLabels(plot, span, labels)
                drawCopGrid(plot, colors, labels)
                clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                    val l = model.frame.left
                    val r = model.frame.right
                    plotSeries(l, l.copY, plot, span, -COP_RANGE, COP_RANGE, plotter, colors.left, strokes.total)
                    plotSeries(r, r.copY, plot, span, -COP_RANGE, COP_RANGE, plotter, colors.right, strokes.total)
                }
                LivePerf.work(t0)
            }
        }
    }
}

/** COP y axis: ± this, in insole units (the sensor rows sit at about ±0.97). */
private const val COP_RANGE = 1.5f

@Composable
private fun segmentColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    inactiveContainerColor = MaterialTheme.colorScheme.surface,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartPanel(
    title: String,
    unit: String?,
    legend: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
    /** The legend gets its own line (narrow screens, many entries). */
    legendBelow: Boolean = false,
    plot: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)) {
            val legendRow = @Composable { m: Modifier ->
                FlowRow(
                    m,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) { legend() }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (unit != null) "$title ($unit)" else title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.width(12.dp))
                if (legendBelow) Spacer(Modifier.weight(1f)) else legendRow(Modifier.weight(1f))
                if (action != null) {
                    Spacer(Modifier.width(8.dp))
                    action()
                }
            }
            if (legendBelow) {
                Spacer(Modifier.height(4.dp))
                legendRow(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.weight(1f).fillMaxWidth(), content = plot)
        }
    }
}

@Composable
private fun LegendItem(text: String, color: Color, muted: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(width = 12.dp, height = 3.dp)
                .background(if (muted) color.copy(alpha = 0.4f) else color, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** "Left", or "Left · Disconnected" while the foot does not stream. */
@Composable
private fun FootLegend(status: FootStatus, color: Color) {
    val name = if (status.foot == Foot.LEFT) "Left" else "Right"
    val streaming = status.state == LinkState.Streaming
    LegendItem(if (streaming) name else "$name · ${status.statusText()}", color, muted = !streaming)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FootSelector(selected: Foot, onSelect: (Foot) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.height(30.dp)) {
        Foot.entries.forEachIndexed { i, foot ->
            SegmentedButton(
                selected = foot == selected,
                onClick = { onSelect(foot) },
                shape = SegmentedButtonDefaults.itemShape(i, Foot.entries.size),
                icon = {},
                colors = segmentColors(),
            ) {
                Text(if (foot == Foot.LEFT) "L" else "R", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Plot rectangle inside the canvas: room for y labels on the left and, at the bottom, the time labels. */
private fun DrawScope.plotArea(withTimeLabels: Boolean): Rect {
    val left = 40.dp.toPx()
    val top = 6.dp.toPx()
    val bottom = size.height - (if (withTimeLabels) 18.dp.toPx() else 4.dp.toPx())
    return Rect(left, top, size.width - 4.dp.toPx(), max(bottom, top + 1f))
}

/** Value gridlines at least 26 dp apart: fewer of them on short plots. */
private fun DrawScope.maxTicks(plot: Rect): Int = (plot.height / 26.dp.toPx()).toInt().coerceIn(1, 5)

private fun xOf(t: Float, plot: Rect, span: Float): Float = plot.right + t / span * plot.width

private fun yOf(v: Float, plot: Rect, min: Float, max: Float): Float =
    plot.bottom - (v - min) / (max - min) * plot.height

/**
 * How the chart draws its lines; a development knob (the Android debug build sets it from an intent extra to compare
 * both ways on a device).
 */
object ChartRendering {
    /**
     * A line up to this many pixels wider than a hairline is drawn as hairlines, a wider one as a stroke; see
     * [plotSeries]. 0: always strokes.
     */
    var maxHairlineSteps: Int = DEFAULT_MAX_HAIRLINE_STEPS

    const val DEFAULT_MAX_HAIRLINE_STEPS = 2
}

/** Skia's hairline: always one device pixel, antialiased. */
private val Hairline = Stroke(0f)

/**
 * Strokes one series of [w] ([values] scaled from [min]..[max] to the plot), thinned per pixel column, in [stroke].
 *
 * Measured with 30 s of 200 Hz on both feet. Desktop (Skia on the GPU, a 1000 px plot at 100 % scale, LivePerf): one
 * wide antialiased stroke per series gave ~55 fps (other joins, splitting the path or coarser thinning barely
 * helped), while hairlines of the same path keep the display's rate. So a line only a pixel or two wider than a
 * hairline is built from hairlines, shifted by up to the extra width horizontally and vertically in steps of at most
 * a pixel, which covers the width (at least `1 + (w - 1)/√2` for any slope) without gaps: ~117 fps. On a dense
 * screen (420 dpi: 3 to 5 extra pixels) that takes 8 to 12 passes per line; on the Android emulator (not a phone)
 * that was slower than strokes: ~27 fps against ~44 (LivePerf), gfxinfo frame time p50 101 ms against 65-69 ms.
 * So lines more than [ChartRendering.maxHairlineSteps] pixels wider than a hairline stay strokes.
 */
private fun DrawScope.plotSeries(
    w: HistoryWindow,
    values: FloatArray,
    plot: Rect,
    span: Float,
    min: Float,
    max: Float,
    plotter: Plotter,
    color: Color,
    stroke: Stroke,
) {
    val path = plotter.sink.path
    path.rewind()
    val reducer = plotter.reducer
    reducer.start(plotter.sink)
    for (i in 0 until w.size) {
        reducer.point(xOf(w.t[i], plot, span), yOf(values[i], plot, min, max), w.breaks[i])
    }
    reducer.finish()
    val extra = (stroke.width - 1f).coerceAtLeast(0f)
    val steps = ceil(extra).toInt()
    when {
        steps == 0 -> drawPath(path, color, style = Hairline)

        steps > ChartRendering.maxHairlineSteps -> drawPath(path, color, style = stroke)

        else -> {
            val step = extra / steps
            val half = extra / 2
            val transform = drawContext.transform
            for (k in 0..steps) {
                val o = -half + k * step
                transform.translate(o, 0f)
                drawPath(path, color, style = Hairline)
                transform.translate(-o, o)
                drawPath(path, color, style = Hairline)
                transform.translate(0f, -o)
            }
        }
    }
}

/** Vertical lines every 1, 2 or 5 s back from now (the right edge). */
private fun DrawScope.drawTimeGrid(plot: Rect, span: Float, colors: ChartColors) {
    val step = timeStep(span)
    var t = 0f
    while (t <= span + 1e-3f) {
        val x = xOf(-t, plot, span)
        drawLine(colors.grid, Offset(x, plot.top), Offset(x, plot.bottom), 1f)
        t += step
    }
    drawLine(colors.axis, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), 1f)
}

private fun timeStep(span: Float): Float = when {
    span <= 5f -> 1f
    span <= 10f -> 2f
    else -> 5f
}

/** "now", "−1 s" ... "−60 s": built once. */
private val TimeTexts = Array(61) { if (it == 0) "now" else "−$it s" }

private fun DrawScope.drawTimeLabels(plot: Rect, span: Float, labels: LabelCache) {
    val step = timeStep(span)
    var t = 0f
    while (t <= span + 1e-3f) {
        val layout = labels.layout(TimeTexts[t.roundToInt().coerceIn(0, TimeTexts.size - 1)])
        val x = xOf(-t, plot, span)
        val left = (x - layout.size.width / 2f).clampTo(plot.left - 8.dp.toPx(), size.width - layout.size.width)
        drawText(layout, topLeft = Offset(left, plot.bottom + 3.dp.toPx()))
        t += step
    }
}

/** [coerceIn] that tolerates an empty range (a canvas smaller than a label): then [lo] wins. */
private fun Float.clampTo(lo: Float, hi: Float): Float = if (hi < lo) lo else coerceIn(lo, hi)

/** Horizontal lines at 0, [step], 2·[step] ... up to [max], labelled on the left with [texts]. */
private fun DrawScope.drawValueGrid(
    plot: Rect,
    max: Float,
    step: Float,
    texts: Array<String>,
    colors: ChartColors,
    labels: LabelCache,
) {
    for (k in texts.indices) {
        val y = yOf(k * step, plot, 0f, max)
        if (k > 0) drawLine(colors.grid, Offset(plot.left, y), Offset(plot.right, y), 1f)
        drawAxisLabel(texts[k], y, plot, labels)
    }
}

private fun DrawScope.drawAxisLabel(text: String, y: Float, plot: Rect, labels: LabelCache) {
    val layout = labels.layout(text)
    val top = (y - layout.size.height / 2f).clampTo(0f, size.height - layout.size.height)
    drawText(layout, topLeft = Offset(plot.left - 6.dp.toPx() - layout.size.width, top))
}

/** The insole's sensor rows (y, name), front first: reference lines of the COP panel. */
private val CopRows: List<Pair<Float, String>> =
    LiveGeometry.layout.positions(Foot.LEFT).map { it.y.toFloat() }.distinct().sortedDescending().map { y ->
        y to when {
            y > 0.45f -> "Fore"
            y < -0.45f -> "Heel"
            else -> "Mid"
        }
    }

/** The sensor rows of the insole as reference lines: forefoot, midfoot, heel. */
private fun DrawScope.drawCopGrid(plot: Rect, colors: ChartColors, labels: LabelCache) {
    for (i in CopRows.indices) {
        val (y, label) = CopRows[i]
        val py = yOf(y, plot, -COP_RANGE, COP_RANGE)
        drawLine(colors.grid, Offset(plot.left, py), Offset(plot.right, py), 1f)
        drawAxisLabel(label, py, plot, labels)
    }
}

/** Light bands behind the plot while the foot is on the ground. */
private fun DrawScope.drawContact(w: HistoryWindow, plot: Rect, span: Float, color: Color) {
    var i = 0
    val n = w.size
    while (i < n) {
        if (!w.contact[i]) {
            i++
            continue
        }
        val start = i
        while (i < n && w.contact[i] && (i == start || !w.breaks[i])) i++
        val x0 = xOf(w.t[start], plot, span).coerceAtLeast(plot.left)
        val x1 = xOf(w.t[i - 1], plot, span).coerceAtMost(plot.right)
        if (x1 > x0) drawRect(color, topLeft = Offset(x0, plot.top), size = Size(x1 - x0, plot.height))
    }
}

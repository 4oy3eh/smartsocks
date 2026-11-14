package com.smartsocks.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import com.smartsocks.app.live.FootNumbers
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.app.live.SensorNames
import com.smartsocks.core.model.Foot
import com.smartsocks.core.model.FootRegion
import com.smartsocks.core.model.SensorLayout
import com.smartsocks.core.protocol.SENSOR_COUNT
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** How often the numbers change at most: fast enough to follow a step, slow enough to read. */
private const val NUMBERS_PERIOD_MILLIS = 100L

/** The live numbers: link statistics from the controller's sessions. */
@Composable
fun NumbersView(
    controller: LiveController,
    snapshot: State<LiveSnapshot>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    NumbersView(snapshot, compact, modifier, key = controller) { s ->
        Pair(
            FootNumbers.of(s.left, controller.left.session.value?.stats?.value, s.balance?.left, s.fullScaleLoad),
            FootNumbers.of(s.right, controller.right.session.value?.stats?.value, s.balance?.right, s.fullScaleLoad),
        )
    }
}

/**
 * Every value of both feet: per foot an insole map with each sensor's load, resistance and share at its place, then
 * the foot's totals and link statistics. The feet sit side by side when they fit, otherwise one above the other.
 * [numbersOf] formats a snapshot (live or playback); updated at most every [NUMBERS_PERIOD_MILLIS] (the newest frame
 * wins), not with every frame.
 */
@Composable
fun NumbersView(
    snapshot: State<LiveSnapshot>,
    compact: Boolean,
    modifier: Modifier = Modifier,
    key: Any? = null,
    numbersOf: (LiveSnapshot) -> Pair<FootNumbers, FootNumbers>,
) {
    val numbers by produceState(numbersOf(snapshot.value), key) {
        snapshotFlow { snapshot.value }.conflate().collect {
            value = numbersOf(it)
            delay(NUMBERS_PERIOD_MILLIS)
        }
    }
    val (left, right) = numbers
    val badge = rememberBadgeMetrics()
    BoxWithConstraints(modifier) {
        val gap = if (compact) 12.dp else 16.dp
        // A card needs two badge columns, an insole of a useful width and its padding.
        val minCard = badge.width * 2 + MapGap * 2 + MinSideBySideInsole + CardPadding * 2
        val sideBySide = maxWidth >= minCard * 2 + gap
        // The map gets what is left of the screen's height after the card's header and statistics, so that both
        // cards fit without scrolling where they can (desktop); stacked cards scroll anyway.
        val mapHeight = if (maxHeight.value.isFinite()) max(MinMapHeight, maxHeight - CardChrome) else MaxMapHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    FootCard(left, badge, mapHeight, Modifier.weight(1f))
                    FootCard(right, badge, mapHeight, Modifier.weight(1f))
                }
            } else {
                FootCard(left, badge, mapHeight)
                Spacer(Modifier.height(gap))
                FootCard(right, badge, mapHeight)
            }
        }
    }
}

private val CardPadding = 16.dp

/** Card height besides the map: header, statistics (four rows), dividers and padding. */
private val CardChrome = 285.dp
private val MinMapHeight = 170.dp
private val MaxMapHeight = 420.dp

/** Between a badge column and the insole: room for the leader lines. */
private val MapGap = 12.dp
private val MinSideBySideInsole = 84.dp
private val MinInsole = 44.dp
private val MaxInsole = 150.dp

/**
 * Size of one sensor badge, measured from its widest plausible texts in the current fonts (and font scale); [dot]:
 * the numbered circle, [labelRow]: the height of the label rows above ("Lateral · Toe · Medial") and below ("Heel")
 * the insole.
 */
private class BadgeMetrics(
    val width: Dp,
    val height: Dp,
    val dot: Dp,
    val labelRow: Dp,
    val load: TextStyle,
    val small: TextStyle,
)

@Composable
private fun rememberBadgeMetrics(): BadgeMetrics {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val load = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val small = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val number = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    return remember(measurer, density, load, small, number) {
        with(density) {
            val loadText = measurer.measure("8888.8 µS", load).size
            val ohms = measurer.measure("88.8 kΩ", small).size
            val share = measurer.measure("100%", small).size
            val digit = measurer.measure("8", number).size
            val text = max(loadText.width.toDp(), ohms.width.toDp() + BadgeInnerGap + share.width.toDp())
            val height = BadgePaddingV * 2 + loadText.height.toDp() + ohms.height.toDp()
            val dot = max(MinBadgeDot, digit.height.toDp() + 2.dp)
            BadgeMetrics(
                width = BadgePaddingH * 2 + dot + BadgeDotGap + text,
                height = max(height, dot + BadgePaddingV * 2),
                dot = dot,
                labelRow = ohms.height.toDp() + 4.dp,
                load = load,
                small = small,
            )
        }
    }
}

private val BadgePaddingH = 6.dp
private val BadgePaddingV = 5.dp
private val MinBadgeDot = 20.dp
private val BadgeDotGap = 6.dp
private val BadgeInnerGap = 6.dp

@Composable
private fun FootCard(n: FootNumbers, badge: BadgeMetrics, mapHeight: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val sock = LocalSockColors.current
    val footColor = if (n.foot == Foot.LEFT) colors.primary else colors.secondary
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(horizontal = CardPadding, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 4.dp).background(footColor, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(if (n.foot == Foot.LEFT) "Left" else "Right", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                val state = n.status.state
                val dot = if (state == LinkState.Streaming && n.rateMismatch) sock.busy else sock.forState(state)
                Box(Modifier.size(8.dp).background(if (n.caption != null) sock.idle else dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                val streaming = state == LinkState.Streaming
                Text(
                    n.caption ?: if (streaming) n.status.deviceName ?: "Streaming" else state.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (streaming || n.caption != null) colors.onSurfaceVariant else sock.forState(state),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            InsoleMap(n, badge, mapHeight)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(10.dp))
            FootStats(n)
        }
    }
}

/**
 * Where everything of one foot's map goes, in dp from the map's top left: the insole (centre and scale: dp per
 * layout unit) between two badge columns, each badge level with its sensor's row (spread apart if the rows are
 * closer than a badge is tall), and the label rows above and below.
 */
private class MapGeometry(width: Dp, maxHeight: Dp, badge: BadgeMetrics, foot: Foot, layout: SensorLayout) {
    val badgeWidth: Dp = min(badge.width, (width - MinInsole - MapGap * 2) / 2)
    val scale: Dp = minOf(
        width - badgeWidth * 2 - MapGap * 2,
        (maxHeight - badge.labelRow * 2) / (Insole.MAX_Y - Insole.MIN_Y),
        MaxInsole,
    ).coerceAtLeast(MinInsole)
    private val contentWidth = badgeWidth * 2 + MapGap * 2 + scale * (Insole.MAX_X - Insole.MIN_X)
    val leftColumn: Dp = ((width - contentWidth) / 2).coerceAtLeast(0.dp)
    val centerX: Dp = leftColumn + badgeWidth + MapGap + scale * -Insole.MIN_X
    val rightColumn: Dp = centerX + scale * Insole.MAX_X + MapGap

    /** Each row's y in the layout (insole units): badges sit level with their row where there is room. */
    private val foreY = layout.rowY(FootRegion.FORE).toFloat()
    private val midY = layout.rowY(FootRegion.MID).toFloat()
    private val heelY = layout.rowY(FootRegion.HEEL).toFloat()
    private val minPitch = badge.height + 6.dp

    /** From the middle row's badge to the front and heel rows' badges: the rows' distance, or more to fit. */
    private val pitchUp = max(scale * (foreY - midY), minPitch)
    private val pitchDown = max(scale * (midY - heelY), minPitch)

    /** Top of the insole: lowered when the spread front badges would otherwise reach above the label row. */
    private val shift: Dp = max(0.dp, pitchUp + badge.height / 2 - scale * (Insole.MAX_Y - midY))
    val labelRow: Dp = badge.labelRow
    val insoleTop: Dp = labelRow + shift
    val zeroY: Dp = insoleTop + scale * Insole.MAX_Y
    val insoleBottom: Dp = insoleTop + scale * (Insole.MAX_Y - Insole.MIN_Y)
    private val midRowY: Dp = zeroY - scale * midY
    val height: Dp = max(insoleBottom, midRowY + pitchDown + badge.height / 2) + labelRow

    private val positions = layout.positions(foot)

    /** The medial side is drawn on the right (left foot: towards the other foot, which is on the right). */
    val medialOnRight: Boolean = (0 until SENSOR_COUNT).any { layout.isMedial(it) && positions[it].x > 0 }

    fun sensorX(i: Int): Dp = centerX + scale * positions[i].x.toFloat()

    fun sensorY(i: Int): Dp = zeroY - scale * positions[i].y.toFloat()

    fun onRight(i: Int): Boolean = positions[i].x > 0

    fun badgeX(i: Int): Dp = if (onRight(i)) rightColumn else leftColumn

    /** Centre of sensor [i]'s badge: level with its row, spread apart where the rows are closer than a badge. */
    fun badgeCenterY(i: Int): Dp = when (regions[i]) {
        FootRegion.FORE -> midRowY - pitchUp
        FootRegion.MID -> midRowY
        FootRegion.HEEL -> midRowY + pitchDown
    }

    private val regions = Array(SENSOR_COUNT) { layout.region(it) }
}

/** One foot's insole with its six sensors where they sit and a badge per sensor beside it. */
@Composable
private fun InsoleMap(n: FootNumbers, badge: BadgeMetrics, maxHeight: Dp) {
    val names = SensorNames.Default
    val scheme = MaterialTheme.colorScheme
    val sock = LocalSockColors.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val dotStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
    val sideStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant)
    // The map's few fixed texts, measured once (not on every redraw).
    val numberTexts = remember(measurer, dotStyle) { names.numbers.map { measurer.measure(it, dotStyle) } }
    val sideTexts = remember(measurer, sideStyle) {
        listOf("Lateral", "Medial", "Toe", "Heel").associateWith { measurer.measure(it, sideStyle) }
    }
    val path = remember(n.foot) { Insole.path(mirror = n.foot == Foot.RIGHT) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val g = MapGeometry(maxWidth, maxHeight, badge, n.foot, names.layout)
        Box(Modifier.fillMaxWidth().height(g.height)) {
            Canvas(Modifier.fillMaxSize()) {
                val scale = g.scale.toPx()
                val cx = g.centerX.toPx()
                val zeroY = g.zeroY.toPx()
                // Insole: the path is in layout units around the foot centre, y up.
                withTransform({
                    translate(cx, zeroY)
                    scale(scale, -scale, pivot = Offset.Zero)
                }) {
                    drawPath(path, scheme.surfaceContainerHigh)
                    drawPath(path, sock.insoleEdge, style = Stroke(width = 1.5f / scale))
                }
                // Big enough for its number at any font scale.
                val radius = (scale * 0.1f).coerceIn(8.dp.toPx(), 12.dp.toPx())
                    .coerceAtLeast(badge.dot.toPx() / 2 - 1.dp.toPx())
                for (i in 0 until SENSOR_COUNT) {
                    val s = n.sensors[i]
                    val center = Offset(g.sensorX(i).toPx(), g.sensorY(i).toPx())
                    // Leader line from the sensor to its badge.
                    val right = g.onRight(i)
                    val from = Offset(center.x + if (right) radius else -radius, center.y)
                    val toX = if (right) g.rightColumn.toPx() else (g.leftColumn + g.badgeWidth).toPx()
                    drawLine(scheme.outline, from, Offset(toX, g.badgeCenterY(i).toPx()), 1.dp.toPx())
                    val fill = lerp(sock.sensorEmpty, scheme.primary, s.level)
                    if (n.live) {
                        drawCircle(fill, radius, center)
                    } else {
                        drawCircle(sock.sensorEmpty, radius, center, style = Stroke(1.5.dp.toPx()))
                    }
                    val label = numberTexts[i]
                    drawText(
                        label,
                        color = if (n.live && s.level > STRONG_LEVEL) scheme.onPrimary else scheme.onSurface,
                        topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f),
                    )
                }
                // Side and end labels: medial faces the other foot.
                val top = 1.dp.toPx()
                fun label(text: String, centerX: Float, y: Float) {
                    val l = sideTexts.getValue(text)
                    drawText(l, topLeft = Offset(centerX - l.size.width / 2f, y))
                }
                val leftX = (g.leftColumn + g.badgeWidth / 2).toPx()
                val rightX = (g.rightColumn + g.badgeWidth / 2).toPx()
                label(if (g.medialOnRight) "Lateral" else "Medial", leftX, top)
                label(if (g.medialOnRight) "Medial" else "Lateral", rightX, top)
                label("Toe", cx, top)
                label("Heel", cx, (g.height - g.labelRow).toPx() + top)
            }
            for (i in 0 until SENSOR_COUNT) {
                SensorBadge(
                    number = names.numbers[i],
                    description = names.labels[i],
                    n = n,
                    index = i,
                    badge = badge,
                    modifier = Modifier
                        .offset(g.badgeX(i), g.badgeCenterY(i) - badge.height / 2)
                        .size(g.badgeWidth, badge.height),
                )
            }
        }
    }
}

/** Above this colour level the accent is dark enough for light text. */
private const val STRONG_LEVEL = 0.6f

@Composable
private fun SensorBadge(
    number: String,
    description: String,
    n: FootNumbers,
    index: Int,
    badge: BadgeMetrics,
    modifier: Modifier = Modifier,
) {
    val s = n.sensors[index]
    val scheme = MaterialTheme.colorScheme
    val sock = LocalSockColors.current
    val level = if (n.live) s.level else 0f
    val fill = lerp(sock.sensorEmpty, scheme.primary, level)
    val open = s.resistance == "open"
    val ohms = if (open || s.resistance == FootNumbers.NONE) s.resistance else "${s.resistance} kΩ"
    val load = if (s.load == FootNumbers.NONE) s.load else "${s.load} µS"
    Surface(
        modifier.semantics(mergeDescendants = true) { contentDescription = "$description: $load, $ohms" },
        shape = RoundedCornerShape(10.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, if (level > 0f) fill else scheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = BadgePaddingH, vertical = BadgePaddingV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(badge.dot).background(if (n.live) fill else sock.sensorEmpty, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    number,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (level > STRONG_LEVEL) scheme.onPrimary else scheme.onSurface,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(BadgeDotGap))
            Column(Modifier.weight(1f)) {
                Text(
                    load,
                    style = badge.load,
                    color = if (n.live) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    Text(
                        ohms,
                        style = badge.small,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (n.live) {
                        Text(
                            "${(s.share * 100).toInt()}%",
                            style = badge.small,
                            color = scheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.padding(start = BadgeInnerGap),
                        )
                    }
                }
            }
        }
    }
}

/** The foot's totals and link statistics, two to a row. */
@Composable
private fun FootStats(n: FootNumbers) {
    val stats = listOf(
        Stat("Total load", n.total),
        Stat("Share of both feet", n.share),
        Stat("COP x (medial +)", n.copX),
        Stat("COP y (toe +)", n.copY),
        Stat("Contact", n.contact),
        Stat("Baseline", n.baseline),
        Stat("Stream", n.rate, warning = if (n.rateMismatch) "Rate mismatch" else null),
        Stat("Dropped bytes", n.dropped),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in stats.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (stat in row) StatCell(stat, n.live, Modifier.weight(1f))
            }
        }
    }
}

private class Stat(val name: String, val value: String, val warning: String? = null)

@Composable
private fun StatCell(stat: Stat, live: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            stat.name,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stat.value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (live) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (stat.warning != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    stat.warning,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalSockColors.current.busy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

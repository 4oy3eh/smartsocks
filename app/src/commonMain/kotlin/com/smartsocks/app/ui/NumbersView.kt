package com.smartsocks.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.FootNumbers
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.app.live.SensorNames
import com.smartsocks.core.model.Foot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/** How often the numbers change at most: fast enough to follow a step, slow enough to read. */
private const val NUMBERS_PERIOD_MILLIS = 100L

private fun numbersOf(controller: LiveController, s: LiveSnapshot): Pair<FootNumbers, FootNumbers> = Pair(
    FootNumbers.of(s.left, controller.left.session.value?.stats?.value, s.balance?.left),
    FootNumbers.of(s.right, controller.right.session.value?.stats?.value, s.balance?.right),
)

/**
 * Every value of both feet as a table: per sensor resistance, load and share; then the foot's totals and link
 * statistics. Updated at most every [NUMBERS_PERIOD_MILLIS] (the newest frame wins), not with every frame.
 */
@Composable
fun NumbersView(
    controller: LiveController,
    snapshot: State<LiveSnapshot>,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val numbers by produceState(numbersOf(controller, snapshot.value), controller) {
        snapshotFlow { snapshot.value }.conflate().collect {
            value = numbersOf(controller, it)
            delay(NUMBERS_PERIOD_MILLIS)
        }
    }
    val (left, right) = numbers
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (compact) {
            FootTable(left, compact = true)
            Spacer(Modifier.height(12.dp))
            FootTable(right, compact = true)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FootTable(left, compact = false, Modifier.weight(1f))
                FootTable(right, compact = false, Modifier.weight(1f))
            }
        }
    }
}

/** Table column widths; [compact] (phones) uses the short sensor names. */
private class Columns(compact: Boolean) {
    val name = if (compact) 84.dp else 136.dp
    val resistance = if (compact) 58.dp else 64.dp
    val load = if (compact) 64.dp else 76.dp
    val gap = if (compact) 10.dp else 16.dp
    val percent = if (compact) 36.dp else 40.dp
}

@Composable
private fun FootTable(n: FootNumbers, compact: Boolean, modifier: Modifier = Modifier) {
    val col = Columns(compact)
    val names = SensorNames.Default
    val colors = MaterialTheme.colorScheme
    val sock = LocalSockColors.current
    val footColor = if (n.foot == Foot.LEFT) colors.primary else colors.secondary
    val value = MaterialTheme.typography.bodyMedium.copy(
        color = if (n.live) colors.onSurface else colors.onSurfaceVariant,
    )
    val header = MaterialTheme.typography.labelMedium.copy(color = colors.onSurfaceVariant)

    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 14.dp, height = 4.dp).background(footColor, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(8.dp))
                Text(if (n.foot == Foot.LEFT) "Left" else "Right", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                val state = n.status.state
                val dot = if (state == LinkState.Streaming && n.rateMismatch) sock.busy else sock.forState(state)
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state == LinkState.Streaming) n.status.deviceName ?: "Streaming" else state.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state == LinkState.Streaming) colors.onSurfaceVariant else sock.forState(state),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sensor", style = header, modifier = Modifier.width(col.name))
                Text("R (kΩ)", style = header, textAlign = TextAlign.End, modifier = Modifier.width(col.resistance))
                Text("Load (µS)", style = header, textAlign = TextAlign.End, modifier = Modifier.width(col.load))
                Spacer(Modifier.width(col.gap))
                Text("Share", style = header, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = colors.outlineVariant)
            for ((i, s) in n.sensors.withIndex()) {
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(col.name), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(sock.series[i], CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (compact) names.short[i] else s.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        s.resistance,
                        style = value.copy(
                            color = if (s.resistance ==
                                "open"
                            ) {
                                colors.onSurfaceVariant
                            } else {
                                value.color
                            },
                        ),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(col.resistance),
                    )
                    Text(s.load, style = value, textAlign = TextAlign.End, modifier = Modifier.width(col.load))
                    Spacer(Modifier.width(col.gap))
                    ShareBar(s.share, footColor, Modifier.weight(1f))
                    Text(
                        if (n.live) "${(s.share * 100).toInt()}%" else "",
                        style = MaterialTheme.typography.labelMedium.copy(color = colors.onSurfaceVariant),
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(col.percent),
                    )
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Spacer(Modifier.height(8.dp))
            val w = if (compact) 136.dp else 160.dp
            Field("Total load", n.total, value, w)
            Field("Share of both feet", n.share, value, w)
            Field("COP x (medial +)", n.copX, value, w)
            Field("COP y (toe +)", n.copY, value, w)
            Field("Contact", n.contact, value, w)
            Field("Baseline", n.baseline, value, w)
            Field("Stream", n.rate, value, w, warning = if (n.rateMismatch) "Rate mismatch" else null)
            Field("Dropped bytes", n.dropped, value, w)
        }
    }
}

@Composable
private fun ShareBar(share: Float, color: Color, modifier: Modifier = Modifier) {
    val track = LocalSockColors.current.sensorEmpty
    Canvas(modifier.height(8.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = radius)
        if (share > 0f) drawRoundRect(color, size = Size(size.width * share, size.height), cornerRadius = radius)
    }
}

@Composable
private fun Field(name: String, text: String, style: TextStyle, nameWidth: Dp, warning: String? = null) {
    Row(Modifier.fillMaxWidth().height(26.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(nameWidth),
            maxLines = 1,
        )
        Text(text, style = style, maxLines = 1)
        if (warning != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                warning,
                style = MaterialTheme.typography.labelMedium,
                color = LocalSockColors.current.busy,
                maxLines = 1,
            )
        }
    }
}

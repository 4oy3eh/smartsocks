package com.smartsocks.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.FootView
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.ChartSettings
import com.smartsocks.core.settings.LiveView
import kotlin.math.roundToInt

/**
 * The controller's snapshot, refreshed once per displayed frame (not per sample) while a foot streams. Otherwise
 * nothing moves: the loop sleeps until a foot's status changes, then draws that one frame.
 */
@Composable
fun rememberLiveSnapshot(controller: LiveController): State<LiveSnapshot> {
    val state = remember(controller) { mutableStateOf(controller.sample(0)) }
    LaunchedEffect(controller) {
        while (true) {
            val snapshot = withFrameNanos {
                LivePerf.frame(it)
                controller.sample(it)
            }
            state.value = snapshot
            if (!controller.isAnimating(snapshot)) {
                LivePerf.idle()
                controller.awaitStatusChange(snapshot)
            }
        }
    }
    return state
}

@Composable
fun LiveScreen(
    controller: LiveController,
    view: LiveView,
    onViewChange: (LiveView) -> Unit,
    chart: ChartSettings,
    onChartChange: (ChartSettings) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val snapshot = rememberLiveSnapshot(controller)
    Column(
        modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(8.dp))
        LiveViewSelector(view, onViewChange, Modifier.widthIn(max = 420.dp).fillMaxWidth())
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = if (compact) 8.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (view) {
                LiveView.COP -> CopView(snapshot, Modifier.fillMaxSize())
                LiveView.CHART -> ChartView(controller, snapshot, chart, onChartChange, compact, Modifier.fillMaxSize())
                LiveView.NUMBERS -> NumbersView(controller, snapshot, compact, Modifier.fillMaxSize())
            }
        }
        // The chart needs the height; the numbers show each foot's share themselves.
        if (view == LiveView.COP) {
            BalancePanel(snapshot, Modifier.widthIn(max = 720.dp).fillMaxWidth())
            Spacer(Modifier.height(if (compact) 12.dp else 20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveViewSelector(selected: LiveView, onSelect: (LiveView) -> Unit, modifier: Modifier = Modifier) {
    val views = LiveView.entries
    SingleChoiceSegmentedButtonRow(modifier) {
        views.forEachIndexed { i, v ->
            SegmentedButton(
                selected = v == selected,
                onClick = { onSelect(v) },
                shape = SegmentedButtonDefaults.itemShape(i, views.size),
                icon = {},
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Text(v.title)
            }
        }
    }
}

private val LiveView.title: String
    get() = when (this) {
        LiveView.COP -> "COP"
        LiveView.CHART -> "Chart"
        LiveView.NUMBERS -> "Numbers"
    }

/** L/R split with percentages and each foot's total load. Text changes only when the shown numbers change. */
@Composable
private fun BalancePanel(snapshot: State<LiveSnapshot>, modifier: Modifier = Modifier) {
    val leftPercent by remember { derivedStateOf { snapshot.value.balance?.let { (it.left * 100).roundToInt() } } }
    val leftLoad by remember { derivedStateOf { snapshot.value.left.loadText() } }
    val rightLoad by remember { derivedStateOf { snapshot.value.right.loadText() } }
    val colors = MaterialTheme.colorScheme
    val track = LocalSockColors.current.sensorEmpty

    Surface(modifier, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer, tonalElevation = 0.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Side("Left", leftPercent, Foot.LEFT, Modifier.weight(1f))
                Text(
                    "Balance",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Side("Right", leftPercent?.let { 100 - it }, Foot.RIGHT, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                val balance = snapshot.value.balance
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = radius)
                if (balance != null) {
                    val split = (size.width * balance.left).toFloat()
                    drawRoundRect(colors.primary, size = Size(split, size.height), cornerRadius = radius)
                    drawRoundRect(
                        colors.secondary.copy(alpha = 0.55f),
                        topLeft = Offset(split, 0f),
                        size = Size(size.width - split, size.height),
                        cornerRadius = radius,
                    )
                }
                // The 50 % mark.
                val mid = size.width / 2
                drawLine(colors.onSurface.copy(alpha = 0.5f), Offset(mid, -3f), Offset(mid, size.height + 3f), 2f)
            }
            Spacer(Modifier.height(10.dp))
            Row {
                LoadText(leftLoad, TextAlign.Start, Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                LoadText(rightLoad, TextAlign.End, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Side(name: String, percent: Int?, foot: Foot, modifier: Modifier = Modifier) {
    val align = if (foot == Foot.LEFT) Alignment.Start else Alignment.End
    Column(modifier, horizontalAlignment = align) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(percent?.let { "$it%" } ?: "–", style = NumberStyle, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LoadText(text: String, align: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = modifier,
        maxLines = 1,
    )
}

/** Total load, rounded to 5 µS so the text does not flicker at frame rate. */
private fun FootView.loadText(): String {
    val s = state ?: return "Load –"
    if (!s.inContact) return "Load 0 µS"
    val rounded = (s.totalLoad / 5).roundToInt() * 5
    return "Load $rounded µS"
}

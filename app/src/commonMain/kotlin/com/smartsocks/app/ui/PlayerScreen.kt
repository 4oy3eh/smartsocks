package com.smartsocks.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.FootNumbers
import com.smartsocks.app.live.LiveSnapshot
import com.smartsocks.app.playback.PlayerController
import com.smartsocks.app.recording.SessionNames
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.ChartSettings
import com.smartsocks.core.settings.LiveView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The position's picture, rebuilt once per displayed frame while playing (from the player's ticks, not per sample)
 * and once per seek while paused.
 */
@Composable
fun rememberPlaybackSnapshot(player: PlayerController): State<LiveSnapshot> {
    val state = remember(player) { mutableStateOf(player.snapshot()) }
    LaunchedEffect(player) {
        var shown = Double.NaN
        while (true) {
            withFrameNanos {
                LivePerf.frame(it)
                val position = player.position
                if (position != shown) {
                    state.value = player.snapshot(position)
                    shown = position
                }
            }
            if (!player.state.value.playing) {
                LivePerf.idle()
                player.state.first { it.playing || it.positionMillis != shown }
            }
        }
    }
    return state
}

@Composable
internal fun PlayerTopBar(player: PlayerController?, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "Back to sessions") }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    player?.session?.id?.let(SessionNames::display) ?: "Player",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (player != null) {
                    Text(
                        sessionDetails(player.session),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * A recorded session like a video: the live views (COP, Chart, Numbers) fed from the recording, and a transport bar
 * with an overview of the whole session's load. Keys (desktop): Space play/pause, ←/→ one frame, Shift+←/→ previous
 * or next step, [ and ] loop A and B, L clear the loop, M / Shift+M next / previous marker, Esc back.
 */
@Composable
fun PlayerScreen(
    player: PlayerController,
    chart: ChartSettings,
    onChartChange: (ChartSettings) -> Unit,
    compact: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = rememberPlaybackSnapshot(player)
    val view by player.view.collectAsState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val visualisation = @Composable { m: Modifier ->
        Box(m, contentAlignment = Alignment.Center) {
            when (view) {
                LiveView.COP -> CopView(snapshot, Modifier.fillMaxSize())

                LiveView.CHART -> PlaybackChartView(player, chart, onChartChange, compact, Modifier.fillMaxSize())

                LiveView.NUMBERS -> NumbersView(snapshot, compact, Modifier.fillMaxSize(), key = player) { s ->
                    Pair(playbackNumbers(player, s, Foot.LEFT), playbackNumbers(player, s, Foot.RIGHT))
                }
            }
        }
    }
    BoxWithConstraints(
        modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { e ->
                e.type == KeyEventType.KeyDown && handleKey(player, e.key, e.isShiftPressed, onBack)
            },
    ) {
        val controlsWidth = minOf(maxWidth * 0.5f, 420.dp)
        if (maxHeight < ShortHeight && maxWidth > maxHeight) {
            // A phone in landscape: the picture on the left, the controls beside it.
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                visualisation(Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.width(controlsWidth).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    LiveViewSelector(view, player::setView, Modifier.fillMaxWidth())
                    TransportBar(player, compact = true, Modifier.fillMaxWidth())
                }
            }
            return@BoxWithConstraints
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(8.dp))
            LiveViewSelector(view, player::setView, Modifier.widthIn(max = 420.dp).fillMaxWidth())
            visualisation(Modifier.weight(1f).fillMaxWidth().padding(vertical = if (compact) 8.dp else 12.dp))
            if (view == LiveView.COP) {
                BalancePanel(snapshot, Modifier.widthIn(max = 720.dp).fillMaxWidth())
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            }
            TransportBar(player, compact, Modifier.widthIn(max = 1100.dp).fillMaxWidth())
            Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        }
    }
}

/** Below this height (a phone in landscape) the controls move beside the picture. */
private val ShortHeight = 520.dp

private fun playbackNumbers(player: PlayerController, s: LiveSnapshot, foot: Foot): FootNumbers {
    val view = s.foot(foot)
    val n = FootNumbers.of(view, null, s.balance?.let { if (foot == Foot.LEFT) it.left else it.right })
    val rate = player.session.header.rateHz
    return n.copy(rate = rate?.let { "Recorded at $it Hz" } ?: FootNumbers.NONE, dropped = FootNumbers.NONE)
}

/** True if [key] was a player shortcut (and is consumed). */
private fun handleKey(player: PlayerController, key: Key, shift: Boolean, onBack: () -> Unit): Boolean {
    when (key) {
        Key.Spacebar -> player.toggle()
        Key.DirectionLeft -> if (shift) player.previousStep() else player.stepFrame(-1)
        Key.DirectionRight -> if (shift) player.nextStep() else player.stepFrame(1)
        Key.LeftBracket -> player.setLoopStart()
        Key.RightBracket -> player.setLoopEnd()
        Key.L -> player.clearLoop()
        Key.M -> if (shift) player.previousMarker() else player.nextMarker()
        Key.Escape -> onBack()
        else -> return false
    }
    return true
}

@Composable
private fun TransportBar(player: PlayerController, compact: Boolean, modifier: Modifier = Modifier) {
    // Everything but the position: the bar does not recompose with every tick while playing.
    val state by remember(player) {
        player.state.map { it.copy(positionMillis = 0.0) }.distinctUntilChanged()
    }.collectAsState(player.state.value.copy(positionMillis = 0.0))
    val pendingA by player.pendingLoopStart.collectAsState()
    val loop = state.loop
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PositionText(player)
                Spacer(Modifier.weight(1f))
                val loopText = when {
                    loop != null -> "Loop ${relTime(player, loop.startMillis)} – ${relTime(player, loop.endMillis)}"
                    pendingA != null -> "A at ${relTime(player, pendingA!!)} · press B to set the end"
                    else -> null
                }
                if (loopText != null) {
                    Text(
                        loopText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    PlayerController.timeText(player.duration),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            PlaybackTimeline(player, Modifier.fillMaxWidth().height(if (compact) 52.dp else 60.dp))
            Spacer(Modifier.height(6.dp))
            val transport = @Composable {
                IconButton(onClick = { player.previousMarker() }, enabled = player.timeline.markers.isNotEmpty()) {
                    Icon(AppIcons.Flag, contentDescription = "Previous marker")
                }
                IconButton(onClick = { player.previousStep() }) {
                    Icon(AppIcons.StepBack, contentDescription = "Previous step")
                }
                IconButton(onClick = { player.stepFrame(-1) }) {
                    Icon(AppIcons.FrameBack, contentDescription = "One frame back")
                }
                FilledIconButton(onClick = player::toggle, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (state.playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (state.playing) "Pause" else "Play",
                    )
                }
                IconButton(onClick = { player.stepFrame(1) }) {
                    Icon(AppIcons.FrameForward, contentDescription = "One frame forward")
                }
                IconButton(onClick = { player.nextStep() }) {
                    Icon(AppIcons.StepForward, contentDescription = "Next step")
                }
                IconButton(onClick = { player.nextMarker() }, enabled = player.timeline.markers.isNotEmpty()) {
                    Icon(AppIcons.Flag, contentDescription = "Next marker", tint = MaterialTheme.colorScheme.primary)
                }
            }
            val extras = @Composable {
                SpeedMenu(state.speed, player::setSpeed)
                Spacer(Modifier.width(8.dp))
                LoopButton("A", active = pendingA != null || loop != null, onClick = player::setLoopStart)
                Spacer(Modifier.width(4.dp))
                LoopButton("B", active = loop != null, onClick = { player.setLoopEnd() })
                if (loop != null || pendingA != null) {
                    IconButton(onClick = player::clearLoop) {
                        Icon(AppIcons.Close, contentDescription = "Clear the loop", modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (compact) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) { transport() }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { extras() }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) { extras() }
                    transport()
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The position to a tenth of a second: recomposes at most 10 times a second while playing. */
@Composable
private fun PositionText(player: PlayerController) {
    val tenths by remember(player) {
        player.state.map { (it.positionMillis / 100).toLong() }.distinctUntilChanged()
    }.collectAsState((player.position / 100).toLong())
    Text(
        PlayerController.timeText(tenths * 100.0 - player.timeline.startMillis),
        style = MaterialTheme.typography.labelLarge,
    )
}

private fun relTime(player: PlayerController, t: Double) = PlayerController.timeText(t - player.timeline.startMillis)

@Composable
private fun LoopButton(text: String, active: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.height(34.dp),
    ) {
        Text(text, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SpeedMenu(speed: Double, onSpeed: (Double) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(
                PlayerController.speedText(speed),
                style = MaterialTheme.typography.labelLarge,
                color = if (speed != 1.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (s in PlayerController.SPEEDS) {
                DropdownMenuItem(
                    text = { Text(PlayerController.speedText(s) + if (s == speed) "  ✓" else "") },
                    onClick = {
                        open = false
                        onSpeed(s)
                    },
                )
            }
        }
    }
}

/**
 * The time slider with the whole session's load above it (left foot up, right foot down), markers as flags, steps as
 * ticks under the track and the A–B loop shaded. Tap or drag anywhere to seek.
 */
@Composable
private fun PlaybackTimeline(player: PlayerController, modifier: Modifier = Modifier) {
    val state = player.state.collectAsState()
    val pendingA = player.pendingLoopStart.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val sock = LocalSockColors.current
    val overview = player.overview
    val timeline = player.timeline
    val start = timeline.startMillis
    val span = timeline.durationMillis.coerceAtLeast(1.0)
    val markers = timeline.markers
    val steps = player.steps
    Canvas(
        modifier
            .pointerInput(player) { detectTapGestures { player.seekFraction((it.x / size.width).toDouble()) } }
            .pointerInput(player) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    player.seekFraction((change.position.x / size.width).toDouble())
                }
            }
            .drawWithCache {
                // The overview depends only on the size: built once per size, not per frame.
                val stripHeight = size.height - 18.dp.toPx()
                val mid = stripHeight / 2
                val half = mid - 2.dp.toPx()
                val paths = Foot.entries.map { foot ->
                    val values = overview.foot(foot)
                    val sign = if (foot == Foot.LEFT) -1f else 1f
                    Path().apply {
                        moveTo(0f, mid)
                        for (i in values.indices) {
                            val v = values[i].takeIf { !it.isNaN() } ?: 0f
                            val x0 = i * size.width / values.size
                            val x1 = (i + 1) * size.width / values.size
                            val y = mid + sign * half * (v / overview.maxTotal).coerceIn(0f, 1f)
                            lineTo(x0, y)
                            lineTo(x1, y)
                        }
                        lineTo(size.width, mid)
                        close()
                    }
                }
                onDrawBehind {
                    val s = state.value
                    fun x(t: Double) = ((t - start) / span * size.width).toFloat()
                    drawPath(paths[0], scheme.primary.copy(alpha = 0.55f))
                    drawPath(paths[1], scheme.secondary.copy(alpha = 0.55f))
                    drawLine(scheme.outlineVariant, Offset(0f, mid), Offset(size.width, mid), 1f)
                    val loop = s.loop
                    if (loop != null) {
                        val a = x(loop.startMillis)
                        val b = x(loop.endMillis)
                        drawRect(scheme.primary.copy(alpha = 0.16f), Offset(a, 0f), Size(b - a, size.height))
                        drawLine(scheme.primary, Offset(a, 0f), Offset(a, size.height), 2f)
                        drawLine(scheme.primary, Offset(b, 0f), Offset(b, size.height), 2f)
                    }
                    pendingA.value?.let { a ->
                        drawLine(scheme.primary, Offset(x(a), 0f), Offset(x(a), size.height), 2f)
                    }
                    // Track.
                    val trackY = stripHeight + 7.dp.toPx()
                    val playX = x(s.positionMillis)
                    drawLine(sock.sensorEmpty, Offset(0f, trackY), Offset(size.width, trackY), 4.dp.toPx())
                    drawLine(scheme.primary, Offset(0f, trackY), Offset(playX, trackY), 4.dp.toPx())
                    // Steps, only while they stay apart.
                    if (steps.size < size.width / 3) {
                        val tick = sock.idle.copy(alpha = 0.6f)
                        for (t in steps) {
                            val sx = x(t)
                            drawLine(tick, Offset(sx, trackY + 4.dp.toPx()), Offset(sx, trackY + 8.dp.toPx()), 1f)
                        }
                    }
                    for (m in markers) {
                        val mx = x(m.tMillis)
                        drawLine(sock.busy, Offset(mx, 0f), Offset(mx, stripHeight), 1.5f)
                        val flag = Path().apply {
                            moveTo(mx, 0f)
                            lineTo(mx + 8.dp.toPx(), 3.dp.toPx())
                            lineTo(mx, 6.dp.toPx())
                            close()
                        }
                        drawPath(flag, sock.busy)
                    }
                    drawLine(scheme.onSurface, Offset(playX, 0f), Offset(playX, stripHeight), 1.5f)
                    drawCircle(scheme.primary, 7.dp.toPx(), Offset(playX, trackY))
                }
            },
    ) {}
}

package com.smartsocks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.RecordingPrompts
import com.smartsocks.app.playback.PlayerController
import com.smartsocks.app.recording.RecordingController
import com.smartsocks.app.recording.RecordingResult
import com.smartsocks.app.recording.RecordingState
import com.smartsocks.app.recording.SessionNames
import com.smartsocks.app.recording.StopReason
import com.smartsocks.app.settings.fixed
import com.smartsocks.core.model.Foot
import kotlinx.coroutines.delay

/** Record (idle) or Stop (recording). Disabled while no foot streams; the strip below says why. */
@Composable
internal fun RecordButton(
    recorder: RecordingController,
    controller: LiveController,
    prompts: RecordingPrompts = RecordingPrompts.None,
) {
    val state by recorder.state.collectAsState()
    val left by controller.left.status.collectAsState()
    val right by controller.right.status.collectAsState()
    val canRecord = left.state == LinkState.Streaming || right.state == LinkState.Streaming
    val sock = LocalSockColors.current
    when (state) {
        RecordingState.Idle -> FilledTonalButton(
            onClick = {
                prompts.beforeRecording()
                recorder.start()
            },
            enabled = canRecord,
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        ) {
            Icon(
                AppIcons.Record,
                contentDescription = null,
                tint = if (canRecord) sock.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Record")
        }

        else -> OutlinedButton(
            onClick = { recorder.stop() },
            enabled = state is RecordingState.Recording,
            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
        ) {
            Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Stop")
        }
    }
}

/**
 * Under the view selector: while recording, a red dot with the elapsed time, size, drops and a Mark button; after a
 * recording, how it ended (with Open); while nothing streams, why Record is disabled.
 */
@Composable
internal fun RecordingStrip(
    recorder: RecordingController,
    controller: LiveController,
    compact: Boolean,
    onOpen: (String) -> Unit,
    prompts: RecordingPrompts = RecordingPrompts.None,
) {
    val state by recorder.state.collectAsState()
    val result by recorder.lastResult.collectAsState()
    val notice by prompts.notice.collectAsState()
    val left by controller.left.status.collectAsState()
    val right by controller.right.status.collectAsState()
    val canRecord = left.state == LinkState.Streaming || right.state == LinkState.Streaming
    val modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(top = 8.dp)
    when (val s = state) {
        is RecordingState.Recording -> {
            RecordingNow(s, recorder, controller, compact, modifier)
            notice?.let { PromptNotice(it, prompts::dismissNotice, modifier) }
        }

        is RecordingState.Stopping, RecordingState.Starting -> StripText(
            if (s is RecordingState.Stopping) "Saving the recording…" else "Starting the recording…",
            modifier,
        )

        RecordingState.Idle -> {
            val r = result
            when {
                r != null -> ResultNotice(r, recorder, onOpen, modifier)
                !canRecord -> StripText("Connect a foot to record a session.", modifier)
            }
        }
    }
}

/** A platform note about the running recording (e.g. notifications are off), until dismissed. */
@Composable
private fun PromptNotice(text: String, onDismiss: () -> Unit, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            IconButton(onClick = onDismiss) {
                Icon(AppIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun StripText(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RecordingNow(
    s: RecordingState.Recording,
    recorder: RecordingController,
    controller: LiveController,
    compact: Boolean,
    modifier: Modifier,
) {
    val sock = LocalSockColors.current
    val elapsed = remember { mutableLongStateOf(0L) }
    LaunchedEffect(s.startedNanos) {
        while (true) {
            elapsed.longValue = (controller.nowNanos() - s.startedNanos) / 1_000_000
            delay(200)
        }
    }
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(sock.error, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "REC  ${PlayerController.timeText(elapsed.longValue.toDouble(), tenths = false)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    recordingDetails(s),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = if (compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { recorder.mark() }) {
                Icon(AppIcons.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Mark")
            }
        }
    }
}

@Composable
private fun ResultNotice(
    r: RecordingResult,
    recorder: RecordingController,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    val failed = r.reason == StopReason.Error
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                resultText(r),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
            )
            val id = r.id
            if (id != null && r.rows > 0) TextButton(onClick = { onOpen(id) }) { Text("Open") }
            IconButton(onClick = recorder::dismissResult) {
                Icon(AppIcons.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The line under "REC": the recorded feet, those waiting to join, size, marks and drops, e.g. "Left foot · Right
 * waiting to connect · 820 KB · 1 mark".
 */
internal fun recordingDetails(s: RecordingState.Recording): String = buildList {
    add(recordingFeetText(s.feet, s.waiting))
    add(sizeText(s.bytes))
    if (s.markers > 0) add(if (s.markers == 1) "1 mark" else "${s.markers} marks")
    if (s.dropped > 0) add("${s.dropped} samples dropped")
    if (s.droppedRaw > 0) add("${s.droppedRaw} raw packets dropped")
}.joinToString(" · ")

internal fun resultText(r: RecordingResult): String {
    val name = r.id?.let(SessionNames::display)
    val details = buildList {
        add(PlayerController.timeText(r.durationMillis, tenths = false))
        for ((foot, t) in r.joinedMillis.entries.sortedBy { it.key }) {
            add("${footName(foot)} foot joined at ${PlayerController.timeText(t, tenths = false)}")
        }
        for (foot in r.neverJoined.sorted()) add("${footName(foot)} foot never connected")
        if (r.markers > 0) add(if (r.markers == 1) "1 mark" else "${r.markers} marks")
        if (r.dropped > 0) add("${r.dropped} samples dropped (storage too slow)")
        if (r.droppedRaw > 0) add("${r.droppedRaw} raw packets dropped")
    }.joinToString(" · ")
    if (name == null && r.reason != StopReason.Error) return "Nothing was recorded: no sample arrived before the stop"
    return when (r.reason) {
        StopReason.Error ->
            "Recording failed: ${r.error ?: "unknown error"}" +
                (if (name != null && r.rows > 0) ". Kept what was written: $name" else "")

        StopReason.Background -> "Recording stopped when the app went to the background. Saved $name · $details"

        StopReason.LinksLost ->
            "Recording stopped in the background: no recorded foot was connected. Saved $name · $details"

        StopReason.User, StopReason.AppClosed -> "Saved $name · $details"
    }
}

internal fun feetText(feet: Set<Foot>): String = when {
    feet.size == 2 -> "Both feet"
    Foot.LEFT in feet -> "Left foot"
    Foot.RIGHT in feet -> "Right foot"
    else -> "No feet"
}

internal fun footName(foot: Foot): String = if (foot == Foot.LEFT) "Left" else "Right"

/** The recorded feet, then those waiting to join: "Left foot · Right waiting to connect", or just "Both feet". */
internal fun recordingFeetText(feet: Set<Foot>, waiting: Set<Foot>): String = when (waiting.size) {
    0 -> feetText(feet)
    1 -> "${feetText(feet)} · ${footName(waiting.single())} waiting to connect"
    else -> "${feetText(feet)} · Both feet waiting to connect"
}

/** "820 KB", "12.4 MB". */
internal fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> "${fixed(bytes / (1024.0 * 1024), 1)} MB"
    else -> "${fixed(bytes / (1024.0 * 1024 * 1024), 2)} GB"
}

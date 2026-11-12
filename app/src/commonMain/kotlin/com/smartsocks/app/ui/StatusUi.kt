package com.smartsocks.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.FootStatus
import com.smartsocks.app.live.LinkState
import com.smartsocks.core.model.Foot

fun LinkState.label(): String = when (this) {
    LinkState.NoDevice -> "No device"
    is LinkState.Unavailable -> message
    LinkState.Idle -> "Not connected"
    LinkState.Connecting -> "Connecting…"
    LinkState.Configuring -> "Configuring…"
    LinkState.Streaming -> "Streaming"
    is LinkState.Disconnected -> reason?.let { "Disconnected: $it" } ?: "Disconnected"
}

fun SockColors.forState(state: LinkState): Color = when (state) {
    LinkState.Streaming -> ok
    LinkState.Connecting, LinkState.Configuring -> busy
    is LinkState.Unavailable, is LinkState.Disconnected -> error
    LinkState.NoDevice, LinkState.Idle -> idle
}

/** Chip text: the rate while streaming, the state otherwise ([compact]: shorter, for phones). */
private fun FootStatus.chipText(compact: Boolean): String = when (val s = state) {
    LinkState.Streaming -> {
        val rate = "${rate.packetsPerSecond}/s"
        if (compact) "Live · $rate" else "Streaming · $rate"
    }

    LinkState.Connecting, LinkState.Configuring -> s.label()

    LinkState.Idle -> "Not connected"

    LinkState.NoDevice -> "No device"

    is LinkState.Unavailable -> "Unavailable"

    is LinkState.Disconnected -> "Disconnected"
}

/** "L · Streaming · 100/s"; a tap connects or disconnects the foot. */
@Composable
fun FootChip(status: FootStatus, compact: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSockColors.current
    val enabled = status.state.isActive || status.state.canConnect
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Amber while streaming off the requested rate (firmware ignored it, or packets are lost).
            val dot = if (status.state == LinkState.Streaming && status.rate.rateMismatch) {
                colors.busy
            } else {
                colors.forState(status.state)
            }
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(
                if (status.foot == Foot.LEFT) "L" else "R",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                status.chipText(compact),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
    }
}

package com.smartsocks.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.smartsocks.app.live.CalibrationState
import com.smartsocks.app.live.LinkState
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.settings.title
import com.smartsocks.core.analytics.BaselineSource
import com.smartsocks.core.model.Foot
import kotlinx.coroutines.delay

/** Where the zero point of a foot comes from, as a tag. */
private enum class ZeroPoint(val text: String) {
    Calibrated("Calibrated"),
    Auto("Auto-zero"),
    None("Not calibrated"),
}

/** One foot's explicit calibration: what is in effect, the button, progress and outcome. */
@Composable
internal fun FootCalibrationCard(foot: Foot, controller: LiveController, modifier: Modifier = Modifier) {
    val link = controller.link(foot)
    val status by link.status.collectAsState()
    val calibration by link.calibration.collectAsState()
    val calibrated by link.calibrated.collectAsState()
    // Auto/none changes with the samples; four looks a second are plenty for a label.
    val source by produceState<BaselineSource?>(null, link) {
        while (true) {
            value = link.latest?.baselineSource
            delay(250)
        }
    }
    val zero = when {
        calibrated -> ZeroPoint.Calibrated
        source == BaselineSource.AUTO -> ZeroPoint.Auto
        else -> ZeroPoint.None
    }
    val streaming = status.state == LinkState.Streaming
    val measuring = calibration is CalibrationState.Measuring
    val sock = LocalSockColors.current

    SettingsCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(foot.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (zero == ZeroPoint.Calibrated) {
                Tag(
                    zero.text,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            } else {
                Tag(zero.text)
            }
        }
        Spacer(Modifier.height(8.dp))
        when (val c = calibration) {
            is CalibrationState.Measuring -> {
                Hint("Measuring… keep the foot lifted and still.")
                LinearProgressIndicator(
                    progress = { c.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }

            CalibrationState.Done -> Hint("Calibrated: loads are now measured from this zero point.", color = sock.ok)

            is CalibrationState.Failed -> Hint(
                "Calibration failed: ${c.message}.",
                color = MaterialTheme.colorScheme.error,
            )

            CalibrationState.Idle -> Hint(
                "Lift the foot (or take the insole off) and keep it still for " +
                    "${LiveController.CALIBRATION_SECONDS.toInt()} s after pressing Calibrate.",
            )
        }
        if (!streaming && !measuring) {
            Hint(
                "Connect the foot to calibrate: the zero point is measured from the live stream.",
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { link.calibrate() }, enabled = streaming && !measuring) { Text("Calibrate") }
            TextButton(onClick = link::clearCalibration, enabled = calibrated || measuring) {
                Text("Clear calibration")
            }
        }
    }
}

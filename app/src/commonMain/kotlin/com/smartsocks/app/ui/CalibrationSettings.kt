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
import com.smartsocks.app.live.BalanceCalibrationState
import com.smartsocks.app.live.BalanceTexts
import com.smartsocks.app.live.CalibrationState
import com.smartsocks.app.live.GainSource
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

/**
 * The L/R balance calibration: both insoles corrected to read the same for the same weight, measured by shifting the
 * weight from foot to foot. Needs both feet streaming; not while [recording] (the recording's file names the gains in
 * effect).
 */
@Composable
internal fun BalanceCalibrationCard(controller: LiveController, recording: Boolean, modifier: Modifier = Modifier) {
    val state by controller.balanceCalibration.collectAsState()
    val inEffect by controller.gains.collectAsState()
    val left by controller.left.status.collectAsState()
    val right by controller.right.status.collectAsState()
    val bothStreaming = left.state == LinkState.Streaming && right.state == LinkState.Streaming
    val leftZero by controller.left.calibrated.collectAsState()
    val rightZero by controller.right.calibrated.collectAsState()
    val measuring = state is BalanceCalibrationState.Measuring
    val sock = LocalSockColors.current
    val seconds = LiveController.BALANCE_SECONDS.toInt()

    SettingsCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Balance (L/R)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            when (inEffect.source) {
                GainSource.CALIBRATED -> Tag(
                    "Calibrated",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )

                GainSource.RECORDING -> Tag("From the recording")

                GainSource.NONE -> Tag("Not calibrated")
            }
        }
        Spacer(Modifier.height(8.dp))
        when (val s = state) {
            is BalanceCalibrationState.Measuring -> {
                Hint("Measuring… slowly shift your weight left and right, keeping both feet on the ground.")
                LinearProgressIndicator(
                    progress = { s.progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }

            is BalanceCalibrationState.Done -> Hint(BalanceTexts.result(s.result), color = sock.ok)

            // The messages say what to do; the tag still says what is in effect.
            is BalanceCalibrationState.Failed -> Hint("${s.message}.", color = MaterialTheme.colorScheme.error)

            BalanceCalibrationState.Idle -> Hint(
                "Two insoles differ in sensitivity, which skews the L/R split. Press Calibrate, then stand on both " +
                    "feet and slowly shift your weight left and right, from foot to foot, for $seconds s.",
            )
        }
        Hint(
            "This corrects the insoles' sensitivity, not your posture: an uneven stance still shows.",
            modifier = Modifier.padding(top = 6.dp),
        )
        if (inEffect.source != GainSource.NONE && !measuring) {
            Hint("Gains in effect: ${BalanceTexts.gains(inEffect.gains)}", modifier = Modifier.padding(top = 6.dp))
        }
        if (recording) {
            Hint(LOCKED_WHILE_RECORDING, modifier = Modifier.padding(top = 6.dp))
        } else if (!bothStreaming && !measuring) {
            Hint(
                "Connect both feet to calibrate: the balance is measured from both live streams.",
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        // Auto-zero may move during the measurement (it is then refused) and distorts quiet standing.
        if (!(leftZero && rightZero) && !measuring && !recording) {
            Hint(
                "Calibrate the zero point of both feet first (above) for a reliable result.",
                modifier = Modifier.padding(top = 6.dp),
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { controller.calibrateBalance() },
                enabled = bothStreaming && !measuring && !recording,
            ) { Text("Calibrate") }
            TextButton(
                onClick = controller::resetBalance,
                enabled = (inEffect.source == GainSource.CALIBRATED || measuring) && !recording,
            ) {
                Text("Reset balance")
            }
        }
    }
}

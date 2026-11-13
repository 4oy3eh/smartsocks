package com.smartsocks.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartsocks.app.AppInfo
import com.smartsocks.app.live.LiveController
import com.smartsocks.app.platform.DeviceActions
import com.smartsocks.app.platform.DeviceCatalog
import com.smartsocks.app.settings.ProcessorField
import com.smartsocks.core.analytics.ProcessorSettings
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
import com.smartsocks.core.protocol.SampleRate
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.AppTheme
import com.smartsocks.core.settings.LiveView

internal const val LOCKED_WHILE_RECORDING = "Stop the recording to change this."

/** From this width the settings use two columns (both feet side by side). */
private val TwoColumnWidth = 840.dp

/**
 * Devices, stream, calibration, processing and general settings. Every change goes through [onChange] (saved by
 * the settings repository) and applies live: the controller rebuilds or updates the sessions as needed.
 * [advancedExpanded]: open the processing section initially (previews).
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    controller: LiveController,
    catalog: DeviceCatalog,
    actions: DeviceActions,
    modifier: Modifier = Modifier,
    advancedExpanded: Boolean = false,
    /** A recording runs: devices and stream settings are locked (the recording controller refuses them too). */
    recording: Boolean = false,
) {
    val lists = rememberDeviceLists(catalog, actions)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= TwoColumnWidth
        val gap = if (wide) 16.dp else 12.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 16.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = if (wide) 1160.dp else 640.dp).fillMaxWidth()) {
                SectionHeader(
                    "Devices",
                    if (recording) LOCKED_WHILE_RECORDING else "Each foot is its own independent connection.",
                )
                BothFeet(wide, gap) { foot, m ->
                    FootDeviceCard(foot, settings, onChange, controller, catalog, lists, actions, m, locked = recording)
                }

                SectionHeader("Calibration", "Sets each insole's zero point, measured with the foot unloaded.")
                BothFeet(wide, gap) { foot, m -> FootCalibrationCard(foot, controller, m) }

                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Column(Modifier.weight(1f)) {
                            StreamSection(settings, onChange, recording)
                            RecordingSection(settings, onChange)
                        }
                        Column(Modifier.weight(1f)) { GeneralSection(settings, onChange) }
                    }
                } else {
                    StreamSection(settings, onChange, recording)
                    RecordingSection(settings, onChange)
                }
                ProcessingSection(settings.processor, onChange, wide, advancedExpanded)
                if (!wide) GeneralSection(settings, onChange)
            }
        }
    }
}

/** Both feet: side by side when [wide], stacked otherwise. */
@Composable
private fun BothFeet(wide: Boolean, gap: Dp, content: @Composable (Foot, Modifier) -> Unit) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            for (foot in Foot.entries) content(foot, Modifier.weight(1f))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (foot in Foot.entries) content(foot, Modifier)
        }
    }
}

@Composable
private fun StreamSection(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit, locked: Boolean) {
    SectionHeader(
        "Stream",
        if (locked) LOCKED_WHILE_RECORDING else "Changing these reconnects the connected socks.",
    )
    SettingsCard {
        FieldLabel("Sample rate")
        ChipChoice(SampleRate.entries, settings.stream.rate, { it.label }, enabled = !locked) { rate ->
            onChange { it.copy(stream = it.stream.copy(rate = rate)) }
        }
        Spacer(Modifier.height(12.dp))
        FieldLabel("Measurement range")
        ChipChoice(MeasurementRange.entries, settings.stream.range, { it.label }, enabled = !locked) { range ->
            onChange { it.copy(stream = it.stream.copy(range = range)) }
        }
        Hint(
            "The highest sensor resistance the sock measures. A higher range also registers a light touch; a lower " +
                "one resolves heavy load more finely, but a light touch beyond it reads as no load.",
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun RecordingSection(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    SectionHeader("Recording")
    SettingsCard {
        SwitchRow(
            title = "Also save raw packets",
            description = "A second file with every packet as received, so a session can be processed again " +
                "later (about twice the size).",
            checked = settings.recording.saveRaw,
            onCheckedChange = { on -> onChange { it.copy(recording = it.recording.copy(saveRaw = on)) } },
        )
    }
}

private val AppTheme.title: String
    get() = when (this) {
        AppTheme.SYSTEM -> "System"
        AppTheme.LIGHT -> "Light"
        AppTheme.DARK -> "Dark"
    }

private val LiveView.title: String
    get() = when (this) {
        LiveView.COP -> "COP"
        LiveView.CHART -> "Chart"
        LiveView.NUMBERS -> "Numbers"
    }

@Composable
private fun GeneralSection(settings: AppSettings, onChange: ((AppSettings) -> AppSettings) -> Unit) {
    SectionHeader("General")
    SettingsCard {
        FieldLabel("Theme")
        SegmentedChoice(AppTheme.entries, settings.theme, {
            it.title
        }, { theme -> onChange { it.copy(theme = theme) } })
        Spacer(Modifier.height(14.dp))
        FieldLabel("Live view")
        SegmentedChoice(
            LiveView.entries,
            settings.liveView,
            { it.title },
            { view -> onChange { it.copy(liveView = view) } },
        )
        Hint("The view the live screen opens with.", modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(14.dp))
        SwitchRow(
            title = "Connect on start",
            description = "Connect both feet to the remembered devices when the app opens.",
            checked = settings.autoConnect,
            onCheckedChange = { on -> onChange { it.copy(autoConnect = on) } },
        )
        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
        About()
    }
}

@Composable
private fun About() {
    val uri = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("SmartSocks", style = MaterialTheme.typography.bodyLarge)
            Hint("Version ${AppInfo.VERSION}")
        }
        TextButton(onClick = { runCatching { uri.openUri(AppInfo.REPOSITORY_URL) } }) {
            Text(AppInfo.REPOSITORY_URL.removePrefix("https://"))
        }
    }
}

@Composable
private fun ProcessingSection(
    processor: ProcessorSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    wide: Boolean,
    initiallyExpanded: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    SectionHeader("Processing")
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Advanced", style = MaterialTheme.typography.bodyLarge)
                    if (processor != ProcessorSettings()) Tag("Changed")
                }
                Hint("Contact detection, auto-zero and COP smoothing. Applied live, without reconnecting.")
            }
            Icon(
                AppIcons.Chevron,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.padding(start = 8.dp).rotate(if (expanded) 180f else 0f),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            val fields = ProcessorField.entries
            if (wide) {
                val half = (fields.size + 1) / 2
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { Fields(fields.take(half), processor, onChange) }
                    Column(Modifier.weight(1f)) { Fields(fields.drop(half), processor, onChange) }
                }
            } else {
                Column { Fields(fields, processor, onChange) }
            }
            OutlinedButton(
                onClick = { onChange { it.copy(processor = ProcessorSettings()) } },
                enabled = processor != ProcessorSettings(),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
private fun ColumnScope.Fields(
    fields: List<ProcessorField>,
    processor: ProcessorSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    for (field in fields) {
        ProcessorSlider(field, processor) { value ->
            onChange { it.copy(processor = field.with(it.processor, value)) }
        }
    }
}

@Composable
private fun ProcessorSlider(field: ProcessorField, processor: ProcessorSettings, onValue: (Double) -> Unit) {
    val value = field.valueIn(processor)
    val max = field.maxIn(processor)
    Column(Modifier.padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                field.format(value),
                style = MaterialTheme.typography.labelLarge,
                color = if (field.isChanged(processor)) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Slider(
            value = value.coerceIn(field.min, max).toFloat(),
            onValueChange = { onValue(it.toDouble()) },
            valueRange = field.min.toFloat()..max.toFloat(),
        )
        Hint(field.description)
    }
}

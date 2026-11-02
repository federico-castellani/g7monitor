package it.federico.g7monitor.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.G7App
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.data.GlucoseReading
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import it.federico.g7monitor.ui.home.GlucoseChart
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import it.federico.g7monitor.R

/** Smoothing levels, in the order of the buttons: label and what it does. */
private val SMOOTHING = listOf(
    R.string.smoothing_none to R.string.smoothing_none_text,
    R.string.smoothing_light to R.string.smoothing_light_text,
    R.string.smoothing_strong to R.string.smoothing_strong_text,
)

/** How the home chart looks, with a live preview on the last hours of readings. */
@Composable
fun ChartSections(settings: AppSettings) {
    val app = G7App.instance
    val scope = rememberCoroutineScope()
    var readings by remember { mutableStateOf<List<GlucoseReading>>(emptyList()) }
    var sticks by remember { mutableStateOf<List<Fingerstick>>(emptyList()) }
    val changes by app.readings.changes.collectAsState()
    val now = remember(changes) { System.currentTimeMillis() }
    LaunchedEffect(changes) {
        val from = now - PREVIEW_HOURS * 3_600_000L
        readings = app.readings.since(from)
        sticks = app.readings.fingersticksSince(from)
    }

    SectionCard(stringResource(R.string.chart_preview), Icons.AutoMirrored.Outlined.ShowChart) {
        if (readings.isEmpty()) {
            Text(
                stringResource(R.string.chart_preview_empty, PREVIEW_HOURS),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            GlucoseChart(
                readings = readings, fingersticks = sticks, windowHours = PREVIEW_HOURS, now = now,
                lowMgdl = settings.lowMgdl, highMgdl = settings.highMgdl, mmol = settings.useMmol,
                smoothing = settings.chartSmoothing, showDots = settings.chartDots,
                showFingersticks = settings.chartFingersticks, height = 200.dp,
            )
        }
    }

    SectionCard(stringResource(R.string.chart_smoothing), Icons.Outlined.Gesture) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SMOOTHING.forEachIndexed { level, (label, _) ->
                SegmentedButton(
                    selected = settings.chartSmoothing == level,
                    onClick = { scope.launch { app.settings.setChartSmoothing(level) } },
                    shape = SegmentedButtonDefaults.itemShape(level, SMOOTHING.size),
                ) { Text(stringResource(label)) }
            }
        }
        Text(
            stringResource(SMOOTHING[settings.chartSmoothing.coerceIn(0, 2)].second),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard(stringResource(R.string.chart_show), Icons.Outlined.Tune) {
        SettingRow(
            Icons.Outlined.Circle, stringResource(R.string.chart_dots),
            stringResource(if (settings.chartDots) R.string.chart_dots_on else R.string.chart_dots_off),
            trailing = { Switch(checked = settings.chartDots, onCheckedChange = { v -> scope.launch { app.settings.setChartDots(v) } }) },
        )
        SettingRow(
            Icons.Outlined.WaterDrop, stringResource(R.string.chart_fingersticks),
            stringResource(R.string.chart_fingersticks_text),
            trailing = {
                Switch(checked = settings.chartFingersticks, onCheckedChange = { v -> scope.launch { app.settings.setChartFingersticks(v) } })
            },
        )
        Text(
            stringResource(R.string.chart_window_note),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line for the settings menu. */
@Composable
fun chartSummary(s: AppSettings): String = stringResource(
    if (s.chartDots) R.string.chart_summary_dots else R.string.chart_summary_line,
    stringResource(SMOOTHING[s.chartSmoothing.coerceIn(0, 2)].first).lowercase(),
)

private const val PREVIEW_HOURS = 3

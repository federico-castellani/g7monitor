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

/** Smoothing levels, in the order of the buttons: label and what it does. */
private val SMOOTHING = listOf(
    "Nessuno" to "Una linea dritta da una lettura all'altra, come arrivano dal sensore.",
    "Leggero" to "Una curva morbida che passa esattamente per ogni lettura: i valori non cambiano e la curva non inventa picchi.",
    "Forte" to "Una curva sulla media delle letture vicine (circa 20 minuti): toglie le piccole oscillazioni del sensore. " +
        "I punti, se visibili, restano sui valori reali.",
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

    SectionCard("Anteprima", Icons.AutoMirrored.Outlined.ShowChart) {
        if (readings.isEmpty()) {
            Text(
                "Nessuna lettura nelle ultime $PREVIEW_HOURS ore: l'anteprima comparirà con i dati.",
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

    SectionCard("Lisciamento della linea", Icons.Outlined.Gesture) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SMOOTHING.forEachIndexed { level, (label, _) ->
                SegmentedButton(
                    selected = settings.chartSmoothing == level,
                    onClick = { scope.launch { app.settings.setChartSmoothing(level) } },
                    shape = SegmentedButtonDefaults.itemShape(level, SMOOTHING.size),
                ) { Text(label) }
            }
        }
        Text(
            SMOOTHING[settings.chartSmoothing.coerceIn(0, 2)].second,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("Cosa mostrare", Icons.Outlined.Tune) {
        SettingRow(
            Icons.Outlined.Circle, "Punti delle letture",
            if (settings.chartDots) "Un punto colorato per ogni lettura" else "Solo la linea, più marcata",
            trailing = { Switch(checked = settings.chartDots, onCheckedChange = { v -> scope.launch { app.settings.setChartDots(v) } }) },
        )
        SettingRow(
            Icons.Outlined.WaterDrop, "Misurazioni del glucometro",
            "I rombi viola con il valore",
            trailing = {
                Switch(checked = settings.chartFingersticks, onCheckedChange = { v -> scope.launch { app.settings.setChartFingersticks(v) } })
            },
        )
        Text(
            "L'intervallo del grafico (3, 6, 12 o 24 ore) si sceglie sopra il grafico e resta quello anche alla riapertura.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line for the settings menu. */
fun chartSummary(s: AppSettings): String =
    "Lisciamento ${SMOOTHING[s.chartSmoothing.coerceIn(0, 2)].first.lowercase()}" +
        if (s.chartDots) " · punti visibili" else " · solo linea"

private const val PREVIEW_HOURS = 3

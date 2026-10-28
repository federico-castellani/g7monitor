package it.federico.g7monitor.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BluetoothConnected
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.data.Link
import it.federico.g7monitor.protocol.SensorState
import it.federico.g7monitor.ui.ChartWindow
import it.federico.g7monitor.ui.Glucose
import it.federico.g7monitor.ui.HomeState
import it.federico.g7monitor.ui.components.IconBadge
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.StatTile
import it.federico.g7monitor.ui.components.StatusPill
import it.federico.g7monitor.ui.theme.LocalRangeColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeState,
    onWindow: (ChartWindow) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onStart: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("G7 Monitor", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenDiagnostics) { Icon(Icons.Outlined.MonitorHeart, "Diagnostica") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "Impostazioni") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val s = state.settings
            if (s.pairingCode.isBlank() || !s.collectorEnabled) {
                SetupCard(needsCode = s.pairingCode.isBlank(), onOpenSettings = onOpenSettings, onStart = onStart)
            }
            CurrentGlucoseCard(state)
            SensorCard(state, onOpenDiagnostics)
            SectionCard("Andamento", Icons.AutoMirrored.Outlined.ShowChart) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ChartWindow.entries.forEachIndexed { i, w ->
                        SegmentedButton(
                            selected = state.window == w,
                            onClick = { onWindow(w) },
                            shape = SegmentedButtonDefaults.itemShape(i, ChartWindow.entries.size),
                            icon = {},
                        ) { Text(w.label) }
                    }
                }
                GlucoseChart(
                    readings = state.readings,
                    windowHours = state.window.hours,
                    now = state.now,
                    lowMgdl = s.lowMgdl,
                    highMgdl = s.highMgdl,
                    mmol = s.useMmol,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(Icons.Outlined.TrackChanges, "In target", state.timeInRange?.let { "$it%" } ?: "—", Modifier.weight(1f))
                StatTile(Icons.Outlined.Equalizer, "Media", state.average?.let { Glucose.format(it, s.useMmol) } ?: "—", Modifier.weight(1f))
                StatTile(Icons.Outlined.Timeline, "Letture", state.readings.size.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SetupCard(needsCode: Boolean, onOpenSettings: () -> Unit, onStart: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                Icons.Outlined.Bluetooth,
                tint = MaterialTheme.colorScheme.onPrimary,
                container = MaterialTheme.colorScheme.primary,
                size = 44.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (needsCode) "Collega il tuo G7" else "Raccolta dati spenta", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (needsCode) "Inserisci il codice di abbinamento stampato sull'applicatore."
                    else "Avvia il collegamento per ricevere le letture.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = if (needsCode) onOpenSettings else onStart) {
                Text(if (needsCode) "Codice" else "Avvia")
            }
        }
    }
}

@Composable
private fun CurrentGlucoseCard(state: HomeState) {
    val range = LocalRangeColors.current
    val latest = state.latest
    val s = state.settings
    val target = when {
        latest == null || state.isStale -> range.stale
        latest.mgdl < s.lowMgdl -> range.low
        latest.mgdl > s.highMgdl -> range.high
        else -> range.inRange
    }
    val color by animateColorAsState(target, label = "glucoseColor")
    val trend = Glucose.trend(latest?.trend)
    val rangeLabel = when {
        latest == null -> null
        state.isStale -> "Lettura non recente"
        latest.mgdl < s.lowMgdl -> "Sotto il target"
        latest.mgdl > s.highMgdl -> "Sopra il target"
        else -> "Nel target"
    }

    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rangeLabel?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = color) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    latest?.let { Glucose.format(it.mgdl, s.useMmol) } ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                    color = color,
                )
                if (trend != null && !state.isStale) {
                    Spacer(Modifier.width(12.dp))
                    TrendArrow(trend, color, 52.dp)
                }
            }
            Text(Glucose.unit(s.useMmol), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            if (latest == null) {
                Text("Nessuna lettura", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.minutesAgo?.let {
                        StatusPill(Icons.Outlined.Schedule, if (it < 1) "adesso" else "$it min fa", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    state.delta?.let {
                        StatusPill(Icons.AutoMirrored.Outlined.ShowChart, "${Glucose.formatDelta(it, s.useMmol)} ${Glucose.unit(s.useMmol)}", MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (trend != null && !state.isStale) {
                    Text(trend.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TrendArrow(trend: Glucose.Trend, color: Color, size: Dp) {
    val angle by animateFloatAsState(trend.angle, label = "trendAngle")
    Box(Modifier.size(size).rotate(angle)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = this.size.width
            val h = this.size.height
            val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun chevron(dx: Float) {
                val p = Path().apply {
                    moveTo(w * 0.52f + dx, h * 0.24f)
                    lineTo(w * 0.80f + dx, h * 0.50f)
                    lineTo(w * 0.52f + dx, h * 0.76f)
                }
                drawPath(p, color, style = stroke)
            }
            drawLine(color, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.78f, h * 0.5f), w * 0.11f, StrokeCap.Round)
            chevron(0f)
            if (trend.double) chevron(-w * 0.24f)
        }
    }
}

private fun linkIcon(link: Link): ImageVector = when (link) {
    Link.Off -> Icons.Outlined.BluetoothDisabled
    Link.Scanning -> Icons.AutoMirrored.Outlined.BluetoothSearching
    Link.Error -> Icons.Outlined.ErrorOutline
    Link.Waiting, Link.Receiving -> Icons.Outlined.BluetoothConnected
    else -> Icons.Outlined.Bluetooth
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SensorCard(state: HomeState, onClick: () -> Unit) {
    val st = state.status
    val scheme = MaterialTheme.colorScheme
    val linkColor = when (st.link) {
        Link.Error -> scheme.error
        Link.Off -> scheme.outline
        Link.Waiting, Link.Receiving -> LocalRangeColors.current.inRange
        else -> scheme.primary
    }
    val life = sensorLife(state)

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(linkIcon(st.link), tint = linkColor, container = linkColor.copy(alpha = 0.14f), size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(st.message ?: st.link.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    st.deviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
                }
                Icon(Icons.Outlined.ChevronRight, contentDescription = "Diagnostica", tint = scheme.onSurfaceVariant)
            }
            if (life != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        if (life.warmingUp) Icons.Outlined.Timer else Icons.Outlined.Sensors,
                        tint = scheme.onSurfaceVariant, container = scheme.surfaceContainerHighest, size = 32.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(life.title, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(
                            progress = { life.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (life.progress > 0.9f) LocalRangeColors.current.high else scheme.primary,
                            trackColor = scheme.surfaceContainerHighest,
                        )
                        Text(life.subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private class SensorLife(val title: String, val subtitle: String, val progress: Float, val warmingUp: Boolean)

private fun sensorLife(state: HomeState): SensorLife? {
    val start = state.settings.sessionStart ?: return null
    val ageMin = (state.now - start) / 60_000L
    val st = state.status.sensorState
    if (st == SensorState.WarmingUp || ageMin < 30) {
        val left = (30 - ageMin).coerceAtLeast(0)
        return SensorLife("Riscaldamento sensore", "circa $left min rimanenti", (ageMin / 30f).coerceIn(0f, 1f), true)
    }
    // G7: 10 days + 12 h grace period
    val totalMin = 10 * 24 * 60 + 12 * 60
    val leftMin = totalMin - ageMin
    val day = ageMin / (24 * 60) + 1
    val left = if (leftMin <= 0) "scaduto" else "scade tra ${leftMin / (24 * 60)} g ${(leftMin / 60) % 24} h"
    val extra = st?.takeIf { it != SensorState.Ok }?.label?.let { " · $it" } ?: ""
    return SensorLife("Sensore · giorno $day$extra", left, (ageMin.toFloat() / totalMin).coerceIn(0f, 1f), false)
}

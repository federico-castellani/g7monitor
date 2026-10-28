package it.federico.g7monitor.ui.alarm

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SignalWifiOff
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import it.federico.g7monitor.alarm.AlarmConfig
import it.federico.g7monitor.alarm.AlarmOutput
import it.federico.g7monitor.alarm.AlarmType
import it.federico.g7monitor.alarm.BuiltInSound
import it.federico.g7monitor.alarm.ValueKind
import it.federico.g7monitor.ui.Glucose
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import it.federico.g7monitor.ui.theme.LocalRangeColors
import kotlinx.coroutines.delay
import java.util.Locale

/** Order shown in the list: from the lowest glucose to the highest, then signal. */
private val ORDER = listOf(
    AlarmType.UrgentLow, AlarmType.Low, AlarmType.FallingFast,
    AlarmType.RisingFast, AlarmType.High, AlarmType.UrgentHigh, AlarmType.SignalLoss,
)

private fun AlarmType.icon(): ImageVector = when (this) {
    AlarmType.UrgentLow -> Icons.Outlined.KeyboardDoubleArrowDown
    AlarmType.Low -> Icons.Outlined.ArrowDownward
    AlarmType.FallingFast -> Icons.AutoMirrored.Outlined.TrendingDown
    AlarmType.RisingFast -> Icons.AutoMirrored.Outlined.TrendingUp
    AlarmType.High -> Icons.Outlined.ArrowUpward
    AlarmType.UrgentHigh -> Icons.Outlined.KeyboardDoubleArrowUp
    AlarmType.SignalLoss -> Icons.Outlined.SignalWifiOff
}

/** "≤ 55 mg/dL", "più di 2,0 mg/dL/min", "20 min senza letture". */
fun AlarmConfig.valueText(mmol: Boolean, value: Int = this.value): String = when (type.kind) {
    ValueKind.Glucose -> {
        val op = when (type) {
            AlarmType.UrgentLow -> "≤"
            AlarmType.Low -> "<"
            AlarmType.UrgentHigh -> "≥"
            else -> ">"
        }
        "$op ${Glucose.format(value, mmol)} ${Glucose.unit(mmol)}"
    }
    ValueKind.Rate -> {
        val perMin = value / 10.0
        if (mmol) String.format(Locale.ITALY, "più di %.2f mmol/L al minuto", perMin / Glucose.MGDL_PER_MMOL)
        else String.format(Locale.ITALY, "più di %.1f mg/dL al minuto", perMin)
    }
    ValueKind.Minutes -> "$value min senza letture"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Spacer(Modifier.height(20.dp))
        }
    }
}

// ------------------------------------------------------------------ list

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmsScreen(
    configs: Map<AlarmType, AlarmConfig>,
    mmol: Boolean,
    receiving: Boolean,
    onOpen: (AlarmType) -> Unit,
    onToggle: (AlarmType, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    Page("Allarmi", onBack) {
        Warnings(receiving)
        Text(
            "Gli allarmi suonano sul canale \"sveglia\" del telefono, anche in modalità silenziosa. " +
                "Per disattivarne uno serve lo sblocco del telefono.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (type in ORDER) {
            val c = configs[type] ?: AlarmConfig(type)
            Card(
                onClick = { onOpen(type) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(type.icon(), contentDescription = null, tint = if (c.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(type.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            if (c.enabled) "${c.valueText(mmol)} · ${c.output.label}" else "Disattivato",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = c.enabled, onCheckedChange = { onToggle(type, it) })
                }
            }
        }
    }
}

/** Things that would keep an alarm from being heard. */
@Composable
private fun Warnings(receiving: Boolean) {
    val context = LocalContext.current
    val nm = context.getSystemService(NotificationManager::class.java)
    val problems = buildList {
        if (!receiving) add("La raccolta dati è spenta: senza di essa gli allarmi non suonano ad app chiusa." to null)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            add("Le notifiche dell'app sono disattivate." to Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !nm.canUseFullScreenIntent()) {
            add("L'allarme non può comparire a tutto schermo sul telefono bloccato." to Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
        }
        if (context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
            add("Il volume della sveglia del telefono è a zero: gli allarmi vibreranno soltanto." to null)
        }
    }
    if (problems.isEmpty()) return
    SectionCard("Attenzione", Icons.Outlined.WarningAmber) {
        problems.forEach { (text, fix) ->
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            if (fix != null) TextButton(onClick = { context.startActivity(fix) }) { Text("Correggi") }
        }
    }
}

// ------------------------------------------------------------------ detail

class AlarmDetailActions(
    val onChange: (AlarmConfig) -> Unit,
    /** Turning off asks for the screen lock; turning on does not. */
    val onToggle: (Boolean) -> Unit,
    val onPreview: (AlarmConfig) -> Unit,
    val onStopPreview: () -> Unit,
    val onPickSystemSound: () -> Unit,
)

@Composable
fun AlarmDetailScreen(config: AlarmConfig, mmol: Boolean, actions: AlarmDetailActions, onBack: () -> Unit) {
    val type = config.type
    val context = LocalContext.current
    var value by remember(config.value) { mutableFloatStateOf(config.value.toFloat()) }
    var volume by remember(config.volume) { mutableFloatStateOf(config.volume.toFloat()) }
    var previewing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { actions.onStopPreview() } }
    LaunchedEffect(previewing) {
        if (previewing) {
            delay(4_000) // same length as the preview played by the engine
            previewing = false
        }
    }

    Page(type.title, onBack) {
        Text(type.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionCard("Stato", type.icon()) {
            SettingRow(
                type.icon(), if (config.enabled) "Attivo" else "Disattivato",
                if (config.enabled) "Per disattivarlo serve lo sblocco del telefono" else null,
                trailing = { Switch(checked = config.enabled, onCheckedChange = actions.onToggle) },
            )
        }

        SectionCard("Quando suona", Icons.Outlined.Tune) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Soglia", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(config.valueText(mmol, value.toInt()), style = MaterialTheme.typography.titleMedium, color = LocalRangeColors.current.inRange)
            }
            val r = type.range
            Slider(
                value = value,
                onValueChange = { value = (Math.round(it / type.step) * type.step).toFloat() },
                onValueChangeFinished = { actions.onChange(config.copy(value = value.toInt())) },
                valueRange = r.first.toFloat()..r.last.toFloat(),
                steps = (r.last - r.first) / type.step - 1,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Snooze, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("\"Posticipa\" silenzia per", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val snoozes = listOf(5, 10, 15, 30, 60)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                snoozes.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = config.snoozeMin == m,
                        onClick = { actions.onChange(config.copy(snoozeMin = m)) },
                        shape = SegmentedButtonDefaults.itemShape(i, snoozes.size),
                        icon = {},
                    ) { Text("$m'") }
                }
            }
            Text(
                "Se nessuno reagisce, l'allarme risuona a ogni nuova lettura finché la condizione resta vera.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard("Come avvisa", Icons.AutoMirrored.Outlined.VolumeUp) {
            AlarmOutput.entries.forEach { o ->
                Row(
                    Modifier.fillMaxWidth().clickable { actions.onChange(config.copy(output = o)) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = config.output == o, onClick = { actions.onChange(config.copy(output = o)) })
                    Icon(
                        if (o == AlarmOutput.VibrationOnly) Icons.Outlined.Vibration else Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(o.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }

        if (config.output != AlarmOutput.VibrationOnly) {
            SectionCard("Suono", Icons.Outlined.MusicNote) {
                BuiltInSound.entries.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.onChange(config.copy(sound = s.name)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = config.sound == s.name, onClick = { actions.onChange(config.copy(sound = s.name)) })
                        Text(s.label + if (s == type.sound) " (consigliato)" else "", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                val custom = config.builtInSound == null
                Row(Modifier.fillMaxWidth().clickable(onClick = actions.onPickSystemSound), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = custom, onClick = actions.onPickSystemSound)
                    Column(Modifier.weight(1f)) {
                        Text("Suono del telefono…", style = MaterialTheme.typography.bodyLarge)
                        if (custom) {
                            val title = remember(config.sound) {
                                runCatching { RingtoneManager.getRingtone(context, Uri.parse(config.sound))?.getTitle(context) }.getOrNull()
                            }
                            Text(title ?: "Suono scelto", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("${volume.toInt()}%", style = MaterialTheme.typography.titleSmall)
                }
                Slider(
                    value = volume,
                    onValueChange = { volume = (Math.round(it / 10f) * 10).toFloat() },
                    onValueChangeFinished = { actions.onChange(config.copy(volume = volume.toInt())) },
                    valueRange = 10f..100f,
                    steps = 8,
                )
                Text(
                    "Rispetto al volume della sveglia del telefono.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!previewing) {
                FilledTonalButton(onClick = { previewing = true; actions.onPreview(config) }) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Prova")
                }
            } else {
                OutlinedButton(onClick = { previewing = false; actions.onStopPreview() }) {
                    Icon(Icons.Outlined.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Ferma")
                }
            }
        }
    }
}

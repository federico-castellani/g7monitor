package it.federico.g7monitor.ui.meter

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.Fingerstick
import it.federico.g7monitor.ui.Glucose
import it.federico.g7monitor.ui.MainViewModel.FoundMeter
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import it.federico.g7monitor.ui.theme.LocalRangeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import it.federico.g7monitor.R

class MeterActions(
    /** @return an error to show, or null */
    val onScan: () -> String?,
    val onStopScan: () -> Unit,
    val onPair: (FoundMeter, onResult: (String?) -> Unit) -> Unit,
    val onForget: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterScreen(
    settings: AppSettings,
    status: String,
    serviceRunning: Boolean,
    scanning: Boolean,
    found: List<FoundMeter>,
    recent: List<Fingerstick>,
    actions: MeterActions,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.meter_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (settings.meterMac != null) Paired(settings, status, serviceRunning, recent, actions)
            else Pairing(scanning, found, actions)
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.meter_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Pairing(scanning: Boolean, found: List<FoundMeter>, actions: MeterActions) {
    var error by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf<String?>(null) }

    SectionCard(stringResource(R.string.meter_pair_title), Icons.AutoMirrored.Outlined.BluetoothSearching) {
        Text(
            stringResource(R.string.meter_pair_steps),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (scanning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.meter_searching), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.onStopScan) { Text(stringResource(R.string.meter_stop)) }
            }
        } else {
            Button(onClick = { error = actions.onScan() }) { Text(stringResource(R.string.meter_search)) }
        }
        found.forEach { m ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WaterDrop, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.name ?: stringResource(R.string.meter_title), style = MaterialTheme.typography.bodyLarge)
                    Text(m.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pairing == m.address) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Button(onClick = {
                        pairing = m.address
                        error = null
                        actions.onPair(m) { e ->
                            pairing = null
                            error = e
                        }
                    }, enabled = pairing == null) { Text(stringResource(R.string.meter_pair)) }
                }
            }
        }
        if (!scanning && found.isEmpty()) {
            Text(
                stringResource(R.string.meter_not_found),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun Paired(settings: AppSettings, status: String, serviceRunning: Boolean, recent: List<Fingerstick>, actions: MeterActions) {
    var confirmForget by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }

    SectionCard(settings.meterName ?: stringResource(R.string.meter_title), Icons.Outlined.WaterDrop) {
        SettingRow(
            Icons.Outlined.CheckCircle, stringResource(R.string.meter_paired), settings.meterMac,
            iconTint = LocalRangeColors.current.inRange,
        )
        SettingRow(
            Icons.Outlined.Schedule,
            if (serviceRunning) status else stringResource(R.string.meter_service_off),
            settings.meterLastSync?.let { stringResource(R.string.meter_last_sync, fmt.format(Date(it))) },
        )
        Text(
            stringResource(R.string.meter_auto),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { confirmForget = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.meter_forget))
        }
    }

    SectionCard(stringResource(R.string.meter_recent), Icons.Outlined.Schedule) {
        if (recent.isEmpty()) {
            Text(stringResource(R.string.meter_none_7d), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        recent.take(15).forEach { f ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmt.format(Date(f.timestamp)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "${Glucose.format(f.mgdl, settings.useMmol)} ${Glucose.unit(settings.useMmol)}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.meter_forget_title)) },
            text = { Text(stringResource(R.string.meter_forget_text)) },
            confirmButton = { TextButton(onClick = { confirmForget = false; actions.onForget() }) { Text(stringResource(R.string.sensor_forget_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

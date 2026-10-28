package it.federico.g7monitor.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.protocol.G7Certificates
import it.federico.g7monitor.ui.Glucose
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import it.federico.g7monitor.ui.theme.LocalRangeColors

class SettingsActions(
    val onPairingCode: (String) -> Unit,
    val onCollector: (Boolean) -> Unit,
    val onMmol: (Boolean) -> Unit,
    val onRange: (Int, Int) -> Unit,
    val onCertificates: (String, String, String) -> Unit,
    val onForget: () -> Unit,
    val onDataSource: (DataSource) -> Unit,
    /** Opens the server pages (setup or status) for the current mode. */
    val onOpenServer: () -> Unit,
    val onOpenAlarms: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, actions: SettingsActions, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Impostazioni") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
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
            DataSourceSection(settings, actions)
            // Each mode shows only its own settings.
            if (settings.dataSource == DataSource.Server) {
                ServerLinkSection(settings, actions)
                AlarmsSection(actions)
                DisplaySection(settings, actions)
                BatterySection()
            } else {
                SensorSection(settings, actions)
                ServerLinkSection(settings, actions)
                AlarmsSection(actions)
                DisplaySection(settings, actions)
                BatterySection()
                CertificateSection(settings, actions)
            }
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Outlined.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Protocollo G7 basato su xDrip+ (GPLv3). Questa app non è un dispositivo medico: " +
                        "non usarla per decisioni terapeutiche senza verificare con un dispositivo approvato.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SensorSection(settings: AppSettings, actions: SettingsActions) {
    var code by rememberSaveable(settings.pairingCode) { mutableStateOf(settings.pairingCode) }
    var confirmForget by remember { mutableStateOf(false) }
    val valid = code.matches(Regex("\\d{4}|\\d{6}"))

    SectionCard("Sensore", Icons.Outlined.Sensors) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
                label = { Text("Codice di abbinamento") },
                supportingText = { Text("Stampato sull'applicatore del G7") },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                isError = code.isNotEmpty() && !valid,
                modifier = Modifier.weight(1f),
            )
        }
        if (code != settings.pairingCode) {
            Button(onClick = { actions.onPairingCode(code) }, enabled = valid) { Text("Salva codice") }
        }
        HorizontalDivider()
        SettingRow(
            Icons.Outlined.Bluetooth, "Raccolta dati", "Collegamento Bluetooth in background",
            trailing = { Switch(checked = settings.collectorEnabled, onCheckedChange = actions.onCollector) },
        )
        settings.sensorMac?.let { SettingRow(Icons.Outlined.Link, "Sensore abbinato", it) }
        TextButton(
            onClick = { confirmForget = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Dimentica sensore")
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Dimenticare il sensore?") },
            text = { Text("Verranno rimossi la chiave di autenticazione e l'abbinamento Bluetooth. Usalo quando cambi sensore o se il collegamento non funziona più.") },
            confirmButton = { TextButton(onClick = { confirmForget = false; actions.onForget() }) { Text("Dimentica") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun DisplaySection(settings: AppSettings, actions: SettingsActions) {
    val mmol = settings.useMmol
    var low by remember(settings.lowMgdl) { mutableFloatStateOf(settings.lowMgdl.toFloat()) }
    var high by remember(settings.highMgdl) { mutableFloatStateOf(settings.highMgdl.toFloat()) }

    SectionCard("Visualizzazione", Icons.Outlined.Tune) {
        Text("Unità di misura", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(false to "mg/dL", true to "mmol/L").forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = mmol == v,
                    onClick = { actions.onMmol(v) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                ) { Text(label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Intervallo target", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(
                "${Glucose.format(low.toInt(), mmol)} – ${Glucose.format(high.toInt(), mmol)} ${Glucose.unit(mmol)}",
                style = MaterialTheme.typography.titleMedium,
                color = LocalRangeColors.current.inRange,
            )
        }
        RangeSlider(
            value = low..high,
            onValueChange = { r -> low = r.start; high = r.endInclusive },
            onValueChangeFinished = { actions.onRange(low.toInt(), high.toInt()) },
            valueRange = 55f..250f,
        )
    }
}

@Composable
private fun DataSourceSection(settings: AppSettings, actions: SettingsActions) {
    var pending by remember { mutableStateOf<DataSource?>(null) }
    SectionCard("Origine dei dati", Icons.Outlined.SwapHoriz) {
        val options = listOf(DataSource.Sensor to "Sensore G7", DataSource.Server to "Server")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (source, label) ->
                SegmentedButton(
                    selected = settings.dataSource == source,
                    onClick = { if (settings.dataSource != source) pending = source },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label) }
            }
        }
        Text(
            if (settings.dataSource == DataSource.Server) "Le glicemie arrivano da un server G7 Monitor: il telefono non si collega al sensore."
            else "Il telefono si collega al sensore via Bluetooth. Il server è facoltativo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    pending?.let { source ->
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
            title = { Text(if (source == DataSource.Server) "Ricevere dal server?" else "Usare il sensore?") },
            text = {
                Text(
                    if (source == DataSource.Server) "Il collegamento al sensore si interrompe e le glicemie arriveranno dal server. Le impostazioni del sensore restano salvate."
                    else "La ricezione dal server si interrompe e il telefono userà il sensore G7. Il collegamento al server resta salvato."
                )
            },
            confirmButton = { TextButton(onClick = { pending = null; actions.onDataSource(source) }) { Text("Cambia") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun AlarmsSection(actions: SettingsActions) {
    SectionCard("Allarmi", Icons.Outlined.NotificationsActive) {
        Text(
            "Glicemia bassa e alta, molto bassa e molto alta, variazioni rapide e mancanza di segnale: " +
                "soglie, suoni e vibrazione per ciascuno.",
            style = MaterialTheme.typography.bodyMedium,
        )
        FilledTonalButton(onClick = actions.onOpenAlarms) { Text("Gestisci allarmi") }
    }
}

/** Entry to the server pages: address and credentials when not linked, status when linked. */
@Composable
private fun ServerLinkSection(settings: AppSettings, actions: SettingsActions) {
    val follow = settings.dataSource == DataSource.Server
    val connected = if (follow) settings.followConnected else settings.serverConnected
    val error = if (follow) settings.followError else settings.uploadError
    SectionCard(if (follow) "Server (ricezione)" else "Server (caricamento)", Icons.Outlined.Cloud) {
        if (connected) {
            SettingRow(
                Icons.Outlined.CloudDone,
                "Collegato come ${if (follow) settings.followUser else settings.serverUser}",
                (if (follow) settings.followUrl else settings.serverUrl).removePrefix("https://"),
                iconTint = LocalRangeColors.current.inRange,
            )
        } else {
            Text(
                if (follow) "Collega il server da cui ricevere le glicemie."
                else "Facoltativo: carica le glicemie su un server G7 Monitor per vederle dal browser e condividerle. " +
                    "Senza server l'app funziona normalmente.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        FilledTonalButton(onClick = actions.onOpenServer) { Text(if (connected) "Gestisci" else "Configura server") }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun BatterySection() {
    val context = LocalContext.current
    val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    SectionCard("Affidabilità", Icons.Outlined.BatteryChargingFull) {
        if (exempt) {
            SettingRow(
                Icons.Outlined.CheckCircle, "Nessuna restrizione di batteria",
                "L'app può aggiornarsi anche a schermo spento",
                iconTint = LocalRangeColors.current.inRange,
            )
        } else {
            Text(
                "Per non perdere letture a schermo spento, consenti all'app di funzionare senza restrizioni di batteria.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }) { Text("Consenti") }
        }
    }
}

@Composable
private fun CertificateSection(settings: AppSettings, actions: SettingsActions) {
    val context = LocalContext.current
    var manual by rememberSaveable { mutableStateOf(false) }
    var qrText by rememberSaveable { mutableStateOf("") }
    var p1 by rememberSaveable(settings.certP1) { mutableStateOf(settings.certP1) }
    var p2 by rememberSaveable(settings.certP2) { mutableStateOf(settings.certP2) }
    var p3 by rememberSaveable(settings.certP3) { mutableStateOf(settings.certP3) }
    var error by remember { mutableStateOf<String?>(null) }

    fun importQr(text: String) {
        val certs = G7Certificates.fromXdripQr(text)
        if (certs == null) {
            error = "QR non riconosciuto: serve il QR \"G Key\" di xDrip (testo che inizia con xdp2:)"
        } else {
            val (a, b, c) = certs.toHex()
            actions.onCertificates(a, b, c)
            error = null
            qrText = ""
            Toast.makeText(context, "Certificato importato", Toast.LENGTH_SHORT).show()
        }
    }

    SectionCard("Certificato G7", Icons.Outlined.QrCodeScanner) {
        if (settings.hasCertificates) {
            SettingRow(
                Icons.Outlined.CheckCircle, "Certificato configurato",
                "Serve solo per il primo abbinamento di un nuovo sensore",
                iconTint = LocalRangeColors.current.inRange,
            )
        } else {
            Text(
                "Al primo abbinamento il G7 chiede un certificato applicativo (in xDrip: \"G Key\"). " +
                    "Importalo dal QR code di xDrip.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        FilledTonalButton(onClick = {
            GmsBarcodeScanning.getClient(
                context,
                GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            ).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let(::importQr) ?: run { error = "QR vuoto" } }
                .addOnFailureListener { e -> error = "Scanner non disponibile: ${e.message}" }
        }) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (settings.hasCertificates) "Scansiona di nuovo" else "Scansiona QR di xDrip")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        TextButton(onClick = { manual = !manual }) {
            Text(if (manual) "Nascondi inserimento manuale" else "Incolla testo o valori manualmente")
        }
        if (manual) {
            OutlinedTextField(
                value = qrText, onValueChange = { qrText = it },
                label = { Text("Testo del QR (xdp2:…)") },
                maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            if (qrText.isNotBlank()) Button(onClick = { importQr(qrText) }) { Text("Importa testo") }
            listOf(Triple("keks_p1", p1) { v: String -> p1 = v }, Triple("keks_p2", p2) { v: String -> p2 = v }, Triple("keks_p3", p3) { v: String -> p3 = v })
                .forEach { (label, value, set) ->
                    OutlinedTextField(
                        value = value, onValueChange = set, label = { Text(label) },
                        maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                }
            val valid = (p1.isBlank() && p2.isBlank() && p3.isBlank()) || G7Certificates.fromHex(p1, p2, p3) != null
            if (!valid) Text("Valori non validi", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { actions.onCertificates(p1, p2, p3); manual = false }, enabled = valid) { Text("Salva valori") }
        }
    }
}

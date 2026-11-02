package it.federico.g7monitor.ui.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import it.federico.g7monitor.ui.components.IconBadge
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import it.federico.g7monitor.ui.update.UpdateActions
import it.federico.g7monitor.ui.update.UpdatesPanel
import it.federico.g7monitor.ui.update.updateSummary
import it.federico.g7monitor.update.UpdateState
import androidx.compose.ui.res.stringResource
import it.federico.g7monitor.R
import androidx.annotation.StringRes
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.runtime.rememberCoroutineScope
import it.federico.g7monitor.G7App
import kotlinx.coroutines.launch

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
    val onOpenMeter: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onOpenPage: (SettingsPage) -> Unit,
    val update: UpdateActions,
)

/** App version and update state, shown in the menu and on the Aggiornamenti page. */
class UpdateInfo(val state: UpdateState, val installed: String)

/** Pages opened from the settings menu. */
enum class SettingsPage(@param:StringRes val title: Int) {
    Sensor(R.string.page_sensor),
    Service(R.string.page_service),
    Display(R.string.page_display),
    Source(R.string.page_source),
    Updates(R.string.page_updates),
    Watch(R.string.page_watch),
    Chart(R.string.page_chart),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                // No back arrow on a tab of the bottom bar.
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
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
            content()
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Settings menu: one line per topic with what is set now; each opens its own page.
 * Each mode (sensor / server) lists only its own topics.
 */
@Composable
fun SettingsScreen(settings: AppSettings, serviceRunning: Boolean, update: UpdateInfo, actions: SettingsActions) {
    val sensorMode = settings.dataSource != DataSource.Server
    val ok = LocalRangeColors.current.inRange
    val warn = MaterialTheme.colorScheme.error
    SettingsScaffold(stringResource(R.string.settings_title), onBack = null) {
        MenuGroup(stringResource(R.string.menu_links)) {
            if (sensorMode) {
                val (summary, tint) = when {
                    settings.pairingCode.isBlank() -> stringResource(R.string.menu_sensor_no_code) to warn
                    !settings.hasCertificates && settings.sensorMac == null -> stringResource(R.string.menu_sensor_no_cert) to warn
                    settings.sensorMac != null -> stringResource(R.string.menu_sensor_paired, settings.pairingCode) to ok
                    else -> stringResource(R.string.menu_sensor_waiting, settings.pairingCode) to null
                }
                MenuRow(Icons.Outlined.Sensors, stringResource(R.string.page_sensor), summary, tint) { actions.onOpenPage(SettingsPage.Sensor) }
                MenuRow(
                    Icons.Outlined.WaterDrop, stringResource(R.string.menu_meter),
                    settings.meterName
                        ?: stringResource(if (settings.meterMac != null) R.string.menu_meter_paired else R.string.menu_meter_optional),
                    if (settings.meterMac != null) ok else null,
                    onClick = actions.onOpenMeter,
                )
            }
            val follow = !sensorMode
            val connected = if (follow) settings.followConnected else settings.serverConnected
            val error = if (follow) settings.followError else settings.uploadError
            MenuRow(
                Icons.Outlined.Cloud, stringResource(if (follow) R.string.menu_server_follow else R.string.menu_server_upload),
                when {
                    !follow && connected && settings.uploadPaused -> stringResource(R.string.menu_server_paused, settings.serverUser)
                    error != null -> error
                    connected -> stringResource(R.string.menu_server_connected, if (follow) settings.followUser else settings.serverUser)
                    follow -> stringResource(R.string.menu_server_to_setup)
                    else -> stringResource(R.string.menu_server_optional)
                },
                when {
                    !follow && connected && settings.uploadPaused -> null
                    error != null || (follow && !connected) -> warn
                    connected -> ok
                    else -> null
                },
                onClick = actions.onOpenServer,
            )
        }
        MenuGroup(stringResource(R.string.menu_app)) {
            val on = settings.collectorEnabled
            MenuRow(
                Icons.Outlined.PowerSettingsNew, stringResource(R.string.page_service),
                stringResource(
                    when {
                        on && serviceRunning -> R.string.menu_service_on
                        on -> R.string.menu_service_starting
                        else -> R.string.menu_service_off
                    }
                ),
                if (on && serviceRunning) ok else warn,
            ) { actions.onOpenPage(SettingsPage.Service) }
            MenuRow(
                Icons.Outlined.Tune, stringResource(R.string.page_display),
                stringResource(
                    R.string.menu_display_summary, Glucose.unit(settings.useMmol),
                    Glucose.format(settings.lowMgdl, settings.useMmol), Glucose.format(settings.highMgdl, settings.useMmol),
                ),
            ) { actions.onOpenPage(SettingsPage.Display) }
            MenuRow(
                Icons.Outlined.SwapHoriz, stringResource(R.string.page_source),
                stringResource(if (sensorMode) R.string.menu_source_sensor else R.string.menu_source_server),
            ) { actions.onOpenPage(SettingsPage.Source) }
            MenuRow(Icons.AutoMirrored.Outlined.ShowChart, stringResource(R.string.page_chart), chartSummary(settings)) { actions.onOpenPage(SettingsPage.Chart) }
            MenuRow(Icons.Outlined.Watch, stringResource(R.string.page_watch), stringResource(R.string.menu_watch_summary)) { actions.onOpenPage(SettingsPage.Watch) }
            val available = update.state is UpdateState.Available || update.state is UpdateState.NeedsPermission
            MenuRow(
                Icons.Outlined.SystemUpdate, stringResource(R.string.page_updates), updateSummary(update.state, update.installed),
                when {
                    available -> MaterialTheme.colorScheme.tertiary
                    update.state is UpdateState.Failed -> warn
                    else -> null
                },
            ) { actions.onOpenPage(SettingsPage.Updates) }
            if (sensorMode) {
                MenuRow(
                    Icons.Outlined.MonitorHeart, stringResource(R.string.menu_diagnostics),
                    stringResource(R.string.menu_diagnostics_summary), onClick = actions.onOpenDiagnostics)
            }
        }
        Disclaimer()
    }
}

/** A page of the settings menu. */
@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    settings: AppSettings,
    serviceRunning: Boolean,
    update: UpdateInfo,
    actions: SettingsActions,
    onBack: () -> Unit,
) {
    SettingsScaffold(stringResource(page.title), onBack) {
        when (page) {
            SettingsPage.Sensor -> {
                SensorSection(settings, actions)
                CertificateSection(settings, actions)
            }
            SettingsPage.Service -> ServiceSection(settings, serviceRunning, actions)
            SettingsPage.Display -> DisplaySection(settings, actions)
            SettingsPage.Source -> DataSourceSection(settings, actions)
            SettingsPage.Updates -> Panel { UpdatesPanel(update.state, update.installed, actions.update) }
            SettingsPage.Watch -> WatchSections()
            SettingsPage.Chart -> ChartSections(settings)
        }
    }
}

@Composable
private fun MenuGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(vertical = 6.dp), content = content)
        }
    }
}

/** Menu line: icon, title, current state (tinted when it needs attention or is fine) and a chevron. */
@Composable
private fun MenuRow(icon: ImageVector, title: String, summary: String, summaryTint: Color? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                summary, style = MaterialTheme.typography.bodySmall,
                color = summaryTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Card without a header, for pages that hold a single topic (the page title says what it is). */
@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun Disclaimer() {
    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SensorSection(settings: AppSettings, actions: SettingsActions) {
    var code by rememberSaveable(settings.pairingCode) { mutableStateOf(settings.pairingCode) }
    var confirmForget by remember { mutableStateOf(false) }
    val valid = code.matches(Regex("\\d{4}|\\d{6}"))

    SectionCard(stringResource(R.string.sensor_pairing), Icons.Outlined.Key) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = code,
                onValueChange = { v -> code = v.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.sensor_code)) },
                supportingText = { Text(stringResource(R.string.sensor_code_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                isError = code.isNotEmpty() && !valid,
                modifier = Modifier.weight(1f),
            )
        }
        if (code != settings.pairingCode) {
            Button(onClick = { actions.onPairingCode(code) }, enabled = valid) { Text(stringResource(R.string.sensor_save_code)) }
        }
        settings.sensorMac?.let { SettingRow(Icons.Outlined.Link, stringResource(R.string.sensor_paired), it) }
        TextButton(
            onClick = { confirmForget = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sensor_forget))
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.sensor_forget_title)) },
            text = { Text(stringResource(R.string.sensor_forget_text)) },
            confirmButton = { TextButton(onClick = { confirmForget = false; actions.onForget() }) { Text(stringResource(R.string.sensor_forget_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun DisplaySection(settings: AppSettings, actions: SettingsActions) {
    val mmol = settings.useMmol
    var low by remember(settings.lowMgdl) { mutableFloatStateOf(settings.lowMgdl.toFloat()) }
    var high by remember(settings.highMgdl) { mutableFloatStateOf(settings.highMgdl.toFloat()) }

    Panel {
        Text(stringResource(R.string.display_unit), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(stringResource(R.string.display_range), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
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
    val scope = rememberCoroutineScope()
    Panel {
        SettingRow(
            Icons.Outlined.Notifications, stringResource(R.string.display_status_number),
            stringResource(R.string.display_status_number_text),
            trailing = {
                Switch(
                    checked = settings.statusBarNumber,
                    onCheckedChange = { v -> scope.launch { G7App.instance.settings.setStatusBarNumber(v) } },
                )
            },
        )
    }
}

@Composable
private fun DataSourceSection(settings: AppSettings, actions: SettingsActions) {
    var pending by remember { mutableStateOf<DataSource?>(null) }
    Panel {
        val options = listOf(DataSource.Sensor to R.string.source_sensor, DataSource.Server to R.string.source_server)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (source, label) ->
                SegmentedButton(
                    selected = settings.dataSource == source,
                    onClick = { if (settings.dataSource != source) pending = source },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(stringResource(label)) }
            }
        }
        Text(
            stringResource(if (settings.dataSource == DataSource.Server) R.string.source_server_text else R.string.source_sensor_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    pending?.let { source ->
        AlertDialog(
            onDismissRequest = { pending = null },
            icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
            title = { Text(stringResource(if (source == DataSource.Server) R.string.source_to_server_title else R.string.source_to_sensor_title)) },
            text = {
                Text(stringResource(if (source == DataSource.Server) R.string.source_to_server_text else R.string.source_to_sensor_text))
            },
            confirmButton = { TextButton(onClick = { pending = null; actions.onDataSource(source) }) { Text(stringResource(R.string.action_change)) } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * One button to run the app as a service: started at boot, brought back if Android kills it
 * (see KeepAlive), with what the phone needs so that it is not stopped in the first place.
 */
@SuppressLint("BatteryLife")
@Composable
private fun ServiceSection(settings: AppSettings, running: Boolean, actions: SettingsActions) {
    val context = LocalContext.current
    val exempt = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    val on = settings.collectorEnabled
    val ok = LocalRangeColors.current.inRange
    Panel {
        SettingRow(
            if (on && running) Icons.Outlined.CheckCircle else Icons.Outlined.PauseCircle,
            stringResource(
                when {
                    on && running -> R.string.service_on
                    on -> R.string.service_starting
                    else -> R.string.service_off
                }
            ),
            stringResource(if (settings.dataSource == DataSource.Server) R.string.service_what_server else R.string.service_what_sensor),
            iconTint = if (on && running) ok else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.service_explain),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (on) {
            OutlinedButton(onClick = { actions.onCollector(false) }) { Text(stringResource(R.string.service_stop)) }
        } else {
            Button(onClick = { actions.onCollector(true) }) { Text(stringResource(R.string.service_start)) }
        }
        HorizontalDivider()
        if (exempt) {
            SettingRow(
                Icons.Outlined.CheckCircle, stringResource(R.string.service_battery_ok),
                stringResource(R.string.service_battery_ok_text),
                iconTint = LocalRangeColors.current.inRange,
            )
        } else {
            Text(
                stringResource(R.string.service_battery_ask),
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                )
            }) { Text(stringResource(R.string.action_allow)) }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Text(
                stringResource(R.string.service_samsung),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }) { Text(stringResource(R.string.service_app_settings)) }
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
            error = context.getString(R.string.cert_bad_qr)
        } else {
            val (a, b, c) = certs.toHex()
            actions.onCertificates(a, b, c)
            error = null
            qrText = ""
            Toast.makeText(context, R.string.cert_imported, Toast.LENGTH_SHORT).show()
        }
    }

    SectionCard(stringResource(R.string.cert_title), Icons.Outlined.QrCodeScanner) {
        if (settings.hasCertificates) {
            SettingRow(
                Icons.Outlined.CheckCircle, stringResource(R.string.cert_configured),
                stringResource(R.string.cert_configured_text),
                iconTint = LocalRangeColors.current.inRange,
            )
        } else {
            Text(
                stringResource(R.string.cert_explain),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        FilledTonalButton(onClick = {
            GmsBarcodeScanning.getClient(
                context,
                GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            ).startScan()
                .addOnSuccessListener { code -> code.rawValue?.let(::importQr) ?: run { error = context.getString(R.string.cert_empty_qr) } }
                .addOnFailureListener { e -> error = context.getString(R.string.cert_no_scanner, e.message) }
        }) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (settings.hasCertificates) R.string.cert_scan_again else R.string.cert_scan))
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        TextButton(onClick = { manual = !manual }) {
            Text(stringResource(if (manual) R.string.cert_hide_manual else R.string.cert_show_manual))
        }
        if (manual) {
            OutlinedTextField(
                value = qrText, onValueChange = { qrText = it },
                label = { Text(stringResource(R.string.cert_qr_text)) },
                maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            if (qrText.isNotBlank()) Button(onClick = { importQr(qrText) }) { Text(stringResource(R.string.cert_import_text)) }
            listOf(Triple("keks_p1", p1) { v: String -> p1 = v }, Triple("keks_p2", p2) { v: String -> p2 = v }, Triple("keks_p3", p3) { v: String -> p3 = v })
                .forEach { (label, value, set) ->
                    OutlinedTextField(
                        value = value, onValueChange = set, label = { Text(label) },
                        maxLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                }
            val valid = (p1.isBlank() && p2.isBlank() && p3.isBlank()) || G7Certificates.fromHex(p1, p2, p3) != null
            if (!valid) Text(stringResource(R.string.cert_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { actions.onCertificates(p1, p2, p3); manual = false }, enabled = valid) { Text(stringResource(R.string.cert_save)) }
        }
    }
}

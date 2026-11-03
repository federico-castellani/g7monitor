package it.federico.g7monitor.ui.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.DebugLog
import it.federico.g7monitor.data.Link
import it.federico.g7monitor.data.SensorStatus
import it.federico.g7monitor.protocol.G7Gatt
import it.federico.g7monitor.ui.components.InfoLine
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.theme.LocalRangeColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import it.federico.g7monitor.R
import it.federico.g7monitor.str

private data class Nearby(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeen: Long,
    val count: Int,
    val hasG7Service: Boolean,
) {
    val isG7 get() = hasG7Service || G7Gatt.isG7Name(name)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(settings: AppSettings, status: SensorStatus, onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by DebugLog.entries.collectAsState()
    val scan by DebugLog.scan.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }

    var showAll by remember { mutableStateOf(false) }
    var showLog by rememberSaveable { mutableStateOf(false) }
    val nearby = remember { mutableStateMapOf<String, Nearby>() }
    val permitted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    if (permitted) NearbyScanner { r -> nearby[r.address] = r.copy(count = (nearby[r.address]?.count ?: 0) + 1) }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val error = MaterialTheme.colorScheme.error

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diag_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionCard(stringResource(R.string.diag_status), Icons.Outlined.MonitorHeart) {
                    InfoLine(stringResource(R.string.diag_link), status.message ?: status.link.label, if (status.link == Link.Error) error else MaterialTheme.colorScheme.onSurface)
                    InfoLine(stringResource(R.string.diag_collection), stringResource(if (settings.collectorEnabled) R.string.diag_on else R.string.diag_off), if (settings.collectorEnabled) MaterialTheme.colorScheme.onSurface else error)
                    InfoLine(
                        stringResource(R.string.diag_code),
                        if (settings.pairingCode.isBlank()) stringResource(R.string.diag_code_unset)
                        else stringResource(R.string.diag_code_set, settings.pairingCode.length),
                    )
                    InfoLine(stringResource(R.string.diag_saved_sensor), settings.sensorMac ?: stringResource(R.string.diag_none))
                    InfoLine(
                        stringResource(R.string.diag_certificate),
                        stringResource(if (settings.hasCertificates) R.string.diag_cert_own else R.string.diag_cert_builtin),
                    )
                    InfoLine(stringResource(R.string.diag_permissions), stringResource(if (permitted) R.string.diag_granted else R.string.diag_missing), if (permitted) MaterialTheme.colorScheme.onSurface else error)
                    BluetoothAdapterInfo(context)
                }
            }
            if (status.link == Link.Scanning || scan.active) item {
                SectionCard(stringResource(R.string.diag_scanning), Icons.AutoMirrored.Outlined.BluetoothSearching) {
                    val since = scan.startedAt?.let { (now - it) / 1000 } ?: 0
                    InfoLine(stringResource(R.string.diag_since), stringResource(R.string.diag_duration, since / 60, since % 60))
                    InfoLine(stringResource(R.string.diag_filtered), scan.filteredResults.toString())
                    scan.lastResult?.let {
                        InfoLine(stringResource(R.string.diag_last), stringResource(R.string.diag_last_value, it, timeFmt.format(Date(scan.lastResultAt ?: 0))))
                    }
                }
            }
            item {
                SectionCard(stringResource(R.string.diag_nearby), Icons.Outlined.Radar) {
                    if (!permitted) {
                        Text(stringResource(R.string.diag_grant), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            stringResource(R.string.diag_g7_advert),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text(stringResource(R.string.diag_only_dexcom)) })
                            FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text(stringResource(R.string.diag_all, nearby.size)) })
                        }
                        val list = nearby.values
                            .filter { showAll || it.isG7 }
                            .sortedWith(compareByDescending<Nearby> { it.isG7 }.thenByDescending { it.rssi })
                            .take(40)
                        if (list.isEmpty()) {
                            Text(
                                if (showAll) stringResource(R.string.diag_no_devices) else stringResource(R.string.diag_no_dexcom, nearby.size),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        list.forEach { d -> DeviceRow(d, now) }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showLog = !showLog }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (showLog) stringResource(R.string.diag_hide_log) else stringResource(R.string.diag_show_log, entries.size))
                }
            }
            if (showLog) {
                item {
                    SectionCard(
                        stringResource(R.string.diag_log), Icons.AutoMirrored.Outlined.Article,
                        trailing = {
                            IconButton(onClick = {
                                context.getSystemService(ClipboardManager::class.java)
                                    .setPrimaryClip(
                                        ClipData.newPlainText("G7 log", DebugLog.asText()).apply {
                                            // Health data: Android hides it from the clipboard preview.
                                            description.extras = android.os.PersistableBundle().apply {
                                                putBoolean("android.content.extra.IS_SENSITIVE", true)
                                            }
                                        },
                                    )
                                Toast.makeText(context, R.string.diag_log_copied, Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Outlined.ContentCopy, stringResource(R.string.diag_copy_log)) }
                            IconButton(onClick = { DebugLog.clear() }) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.diag_clear_log)) }
                        },
                    ) {
                        if (entries.isEmpty()) Text(stringResource(R.string.diag_no_events), style = MaterialTheme.typography.bodyMedium)
                        else Text(stringResource(R.string.diag_newest_first), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(entries.asReversed()) { e ->
                    Text(
                        "${timeFmt.format(Date(e.time))}  ${e.message}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothAdapterInfo(context: Context) {
    val adapter = remember { context.getSystemService(BluetoothManager::class.java).adapter }
    InfoLine("Bluetooth", stringResource(if (adapter?.isEnabled == true) R.string.diag_bt_on else R.string.diag_bt_off))
    val bondedDexcom = remember {
        runCatching { adapter?.bondedDevices?.filter { G7Gatt.isG7Name(it.name) }?.map { "${it.name} ${it.address}" } }
            .getOrNull().orEmpty()
    }
    InfoLine(
        stringResource(R.string.diag_bonded_dexcom),
        if (bondedDexcom.isEmpty()) stringResource(R.string.diag_none) else bondedDexcom.joinToString("\n"),
    )
}

@Composable
private fun DeviceRow(d: Nearby, now: Long) {
    val dot = if (d.isG7) LocalRangeColors.current.inRange else MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                (d.name ?: stringResource(R.string.diag_no_name)).let { if (d.hasG7Service) stringResource(R.string.diag_dexcom_service, it) else it },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (d.isG7) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(d.address, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.diag_seen_ago, (now - d.lastSeen) / 1000, d.count), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Unfiltered scan that runs only while the Diagnostics screen is visible. */
@SuppressLint("MissingPermission")
@Composable
private fun NearbyScanner(onResult: (Nearby) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val scanner = context.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        val main = Handler(Looper.getMainLooper())
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val uuids = result.scanRecord?.serviceUuids.orEmpty()
                val item =
                    Nearby(
                        address = result.device.address,
                        name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull(),
                        rssi = result.rssi,
                        lastSeen = System.currentTimeMillis(),
                        count = 0,
                        hasG7Service = uuids.any { it.uuid == G7Gatt.ADVERTISEMENT },
                    )
                main.post { onResult(item) }
            }

            override fun onScanFailed(errorCode: Int) {
                DebugLog.log(str(R.string.diag_scan_failed, errorCode))
            }
        }
        runCatching {
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        }
        onDispose { runCatching { scanner?.stopScan(cb) } }
    }
}

package it.federico.g7monitor

import android.Manifest
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import it.federico.g7monitor.alarm.AlarmConfig
import it.federico.g7monitor.alarm.AlarmType
import it.federico.g7monitor.ble.G7Service
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.ui.DeviceAuth
import it.federico.g7monitor.ui.MainViewModel
import it.federico.g7monitor.ui.alarm.AlarmDetailActions
import it.federico.g7monitor.ui.alarm.AlarmDetailScreen
import it.federico.g7monitor.ui.alarm.AlarmsScreen
import it.federico.g7monitor.ui.ServerPurpose
import it.federico.g7monitor.ui.diagnostics.DiagnosticsScreen
import it.federico.g7monitor.ui.home.HomeScreen
import it.federico.g7monitor.ui.meter.MeterActions
import it.federico.g7monitor.ui.meter.MeterScreen
import it.federico.g7monitor.ui.server.ServerAddressScreen
import it.federico.g7monitor.ui.server.ServerLoginScreen
import it.federico.g7monitor.ui.server.ServerStatusActions
import it.federico.g7monitor.ui.server.ServerStatusScreen
import it.federico.g7monitor.ui.server.WelcomeScreen
import it.federico.g7monitor.ui.settings.SettingsActions
import it.federico.g7monitor.ui.settings.SettingsPage
import it.federico.g7monitor.ui.settings.SettingsPageScreen
import it.federico.g7monitor.ui.settings.SettingsScreen
import it.federico.g7monitor.ui.settings.UpdateInfo
import it.federico.g7monitor.ui.update.UpdateActions
import it.federico.g7monitor.ui.theme.G7Theme
import kotlinx.coroutines.launch

private enum class Screen(val page: SettingsPage? = null) {
    Home, Alarms, Settings,
    SettingsSensor(SettingsPage.Sensor), SettingsService(SettingsPage.Service),
    SettingsDisplay(SettingsPage.Display), SettingsSource(SettingsPage.Source), SettingsUpdates(SettingsPage.Updates),
    Diagnostics, ServerAddress, ServerLogin, ServerStatus, AlarmDetail, Meter;

    companion object {
        fun of(page: SettingsPage) = entries.first { it.page == page }
    }
}

/** Bottom bar destinations: everything else opens on top of them, with a back arrow. */
private val TABS = listOf(Screen.Home, Screen.Alarms, Screen.Settings)

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasBluetoothPermissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun settingsActions(onCollector: (Boolean) -> Unit, openServer: () -> Unit, open: (Screen) -> Unit) = SettingsActions(
        onPairingCode = vm::setPairingCode,
        onCollector = onCollector,
        onMmol = vm::setUseMmol,
        onRange = vm::setRange,
        onCertificates = vm::setCertificates,
        onForget = vm::forgetSensor,
        onDataSource = vm::setDataSource,
        onOpenServer = openServer,
        onOpenMeter = { open(Screen.Meter) },
        onOpenDiagnostics = { open(Screen.Diagnostics) },
        onOpenPage = { page -> open(Screen.of(page)) },
        update = updateActions,
    )

    private val updateActions by lazy {
        UpdateActions(onCheck = { vm.checkForUpdates(true) }, onInstall = { vm.installUpdate(it) }, onReset = vm::resetUpdate)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Make sure the service runs if it was enabled (e.g. after the app was force-stopped).
        lifecycleScope.launch {
            val s = G7App.instance.settings.current()
            if (s.collectorEnabled && (s.dataSource == DataSource.Server || hasBluetoothPermissions())) G7Service.start(this@MainActivity)
        }
        // Follower mode: fresh data whenever the app is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.followWhileVisible() }
        }
        // New app version on GitHub: checked when the app is opened, at most every few hours.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.checkForUpdates(force = false) }
        }
        setContent {
            G7Theme {
                val state by vm.state.collectAsStateWithLifecycle()
                val serviceRunning by G7Service.running.collectAsStateWithLifecycle()
                val activeAlarm by G7App.instance.alarms.active.collectAsStateWithLifecycle()
                val alarmConfigs by vm.alarmConfigs.collectAsStateWithLifecycle()
                val alarmsPausedUntil by vm.alarmsPausedUntil.collectAsStateWithLifecycle()
                val updateState by vm.updateState.collectAsStateWithLifecycle()
                val update = UpdateInfo(updateState, vm.installedVersion)
                var alarmType by rememberSaveable { mutableStateOf(AlarmType.UrgentLow) }
                /** Pages opened, the current one last: Back returns to the previous one. */
                var stack by rememberSaveable { mutableStateOf(listOf(Screen.Home)) }
                val screen = stack.last()
                val open: (Screen) -> Unit = { stack = stack + it }
                val back: () -> Unit = {
                    if (screen == Screen.Meter) vm.stopMeterScan()
                    if (stack.size > 1) stack = stack.dropLast(1)
                }
                /** A tab starts a fresh path; Back from Allarmi or Impostazioni goes to the glucose. */
                val selectTab: (Screen) -> Unit = { tab -> stack = if (tab == Screen.Home) listOf(Screen.Home) else listOf(Screen.Home, tab) }
                /** Address confirmed in step 1, used by step 2. */
                var pendingUrl by rememberSaveable { mutableStateOf("") }
                val settings = state.settings
                val purpose = if (settings.dataSource == DataSource.Server) ServerPurpose.Follow else ServerPurpose.Upload
                val linked = if (purpose == ServerPurpose.Follow) settings.followConnected else settings.serverConnected

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    if (hasBluetoothPermissions()) vm.setCollector(true)
                    else Toast.makeText(this, "Servono i permessi Bluetooth per collegarsi al sensore", Toast.LENGTH_LONG).show()
                }
                // Server mode needs no Bluetooth: only the (optional) notification permission.
                val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    vm.setCollector(true)
                }
                val enableCollector: (Boolean) -> Unit = { on ->
                    when {
                        // Stopping the service also silences the alarms: same protection as turning one off.
                        !on && alarmConfigs.values.any { it.enabled } -> DeviceAuth.confirm(
                            this, "Fermare il servizio?", "Anche gli allarmi smetteranno di funzionare",
                        ) { ok, error ->
                            if (ok) vm.setCollector(false)
                            else if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        }
                        !on -> vm.setCollector(false)
                        settings.dataSource == DataSource.Server ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else vm.setCollector(true)
                        hasBluetoothPermissions() -> vm.setCollector(true)
                        else -> permissionLauncher.launch(requiredPermissions)
                    }
                }
                /** Turning an alarm on is free; turning it off needs the phone's screen lock or biometrics. */
                /** Pausing all alarms needs the screen lock, like turning one off; resuming is free. */
                val pauseAlarms: (Long?) -> Unit = { until ->
                    if (until == null) vm.pauseAlarms(null)
                    else DeviceAuth.confirm(this, "Sospendere tutti gli allarmi?", "Conferma con lo sblocco del telefono") { ok, error ->
                        if (ok) vm.pauseAlarms(until)
                        else if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    }
                }
                val toggleAlarm: (AlarmConfig, Boolean) -> Unit = { config, on ->
                    if (on) {
                        vm.saveAlarm(config.copy(enabled = true))
                    } else {
                        DeviceAuth.confirm(
                            this, "Disattivare l'allarme?", "${config.type.title}: conferma con lo sblocco del telefono",
                        ) { ok, error ->
                            if (ok) vm.saveAlarm(config.copy(enabled = false))
                            else if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                    val uri = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
                    if (uri != null) vm.saveAlarm(alarmConfigs.getValue(alarmType).copy(sound = uri.toString()))
                }
                val openServer: () -> Unit = { open(if (linked) Screen.ServerStatus else Screen.ServerAddress) }

                if (!state.ready) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    return@G7Theme
                }
                if (settings.dataSource == null) {
                    WelcomeScreen(onChoose = { source ->
                        vm.setDataSource(source)
                        stack = if (source == DataSource.Server) listOf(Screen.Home, Screen.ServerAddress) else listOf(Screen.Home)
                    })
                    return@G7Theme
                }

                BackHandler(enabled = stack.size > 1, onBack = back)
                val tab = screen.takeIf { it in TABS }
                Scaffold(
                    bottomBar = {
                        if (tab != null) NavigationBar {
                            NavigationBarItem(
                                selected = tab == Screen.Home, onClick = { selectTab(Screen.Home) },
                                icon = { Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null) },
                                label = { Text("Glicemia") },
                            )
                            NavigationBarItem(
                                selected = tab == Screen.Alarms, onClick = { selectTab(Screen.Alarms) },
                                icon = {
                                    BadgedBox(badge = { if (activeAlarm != null) Badge() }) {
                                        Icon(
                                            if (alarmsPausedUntil != null) Icons.Outlined.NotificationsOff else Icons.Outlined.NotificationsActive,
                                            contentDescription = null,
                                        )
                                    }
                                },
                                label = { Text("Allarmi") },
                            )
                            NavigationBarItem(
                                selected = tab == Screen.Settings, onClick = { selectTab(Screen.Settings) },
                                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                label = { Text("Impostazioni") },
                            )
                        }
                    },
                    // Each page handles the system bars itself; here only the bottom bar's height.
                    contentWindowInsets = WindowInsets(0),
                ) { bars ->
                    Box(Modifier.fillMaxSize().padding(bars).consumeWindowInsets(bars)) {
                        when (screen) {
                            Screen.Settings -> SettingsScreen(settings, serviceRunning, update, settingsActions(enableCollector, openServer, open))
                            Screen.SettingsSensor, Screen.SettingsService, Screen.SettingsDisplay, Screen.SettingsSource, Screen.SettingsUpdates -> SettingsPageScreen(
                                page = screen.page!!,
                                settings = settings,
                                serviceRunning = serviceRunning,
                                update = update,
                                actions = settingsActions(enableCollector, openServer, open),
                                onBack = back,
                            )
                            Screen.Meter -> {
                                val meterStatus by G7App.instance.meterStatus.collectAsStateWithLifecycle()
                                val scanning by vm.meterScanning.collectAsStateWithLifecycle()
                                val found by vm.meters.collectAsStateWithLifecycle()
                                val recent by vm.recentFingersticks.collectAsStateWithLifecycle()
                                MeterScreen(
                                    settings = settings,
                                    status = meterStatus,
                                    serviceRunning = serviceRunning,
                                    scanning = scanning,
                                    found = found,
                                    recent = recent,
                                    actions = MeterActions(
                                        onScan = vm::startMeterScan,
                                        onStopScan = vm::stopMeterScan,
                                        onPair = { meter, onResult -> vm.pairMeter(meter, onResult) },
                                        onForget = { vm.forgetMeter() },
                                    ),
                                    onBack = back,
                                )
                            }
                            Screen.Alarms -> AlarmsScreen(
                                configs = alarmConfigs,
                                mmol = settings.useMmol,
                                receiving = settings.collectorEnabled,
                                onOpen = { type -> alarmType = type; open(Screen.AlarmDetail) },
                                onToggle = { type, on -> toggleAlarm(alarmConfigs.getValue(type), on) },
                                onTest = { delayed -> vm.testAlarm(alarmConfigs.getValue(AlarmType.UrgentLow), delayed) },
                                pausedUntil = alarmsPausedUntil,
                                onPause = pauseAlarms,
                            )
                            Screen.AlarmDetail -> {
                                val config = alarmConfigs.getValue(alarmType)
                                AlarmDetailScreen(
                                    config = config,
                                    mmol = settings.useMmol,
                                    actions = AlarmDetailActions(
                                        onChange = vm::saveAlarm,
                                        onToggle = { on -> toggleAlarm(config, on) },
                                        onPreview = vm::previewAlarm,
                                        onStopPreview = vm::stopAlarmPreview,
                                        onTest = { delayed -> vm.testAlarm(config, delayed) },
                                        onPickSystemSound = {
                                            soundPicker.launch(
                                                Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION or RingtoneManager.TYPE_RINGTONE)
                                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Suono per \"${config.type.title}\"")
                                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                                                    .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, if (config.builtInSound == null) Uri.parse(config.sound) else null),
                                            )
                                        },
                                    ),
                                    onBack = back,
                                )
                            }
                            Screen.Diagnostics -> DiagnosticsScreen(
                                settings = settings,
                                status = state.status,
                                onBack = back,
                            )
                            Screen.ServerAddress -> ServerAddressScreen(
                                purpose = purpose,
                                initialUrl = (if (purpose == ServerPurpose.Follow) settings.followUrl else settings.serverUrl).removePrefix("https://"),
                                onCheck = vm::checkServer,
                                onFound = { url ->
                                    pendingUrl = url
                                    open(Screen.ServerLogin)
                                },
                                onBack = back,
                            )
                            Screen.ServerLogin -> ServerLoginScreen(
                                purpose = purpose,
                                url = pendingUrl,
                                initialUser = if (purpose == ServerPurpose.Follow) settings.followUser else settings.serverUser,
                                onLogin = { user, password, totp, onResult ->
                                    vm.connectServer(purpose, pendingUrl, user, password, totp, onResult)
                                },
                                onDone = {
                                    if (purpose == ServerPurpose.Follow) {
                                        // The background service starts on its own: ask once to show its notification.
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                        ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        stack = listOf(Screen.Home)
                                    } else {
                                        // Setup done: Back from the status returns to where it started.
                                        stack = stack.filter { it != Screen.ServerAddress && it != Screen.ServerLogin } + Screen.ServerStatus
                                    }
                                },
                                onBack = back,
                            )
                            Screen.ServerStatus -> ServerStatusScreen(
                                purpose = purpose,
                                settings = settings,
                                actions = ServerStatusActions(
                                    onNow = { if (purpose == ServerPurpose.Follow) vm.refreshFromServer() else vm.uploadNow() },
                                    onDisconnect = {
                                        vm.disconnectServer(purpose)
                                        back()
                                    },
                                    onUploadInterval = vm::setUploadInterval,
                                    onUploadWifiOnly = vm::setUploadWifiOnly,
                                    onBackground = enableCollector,
                                ),
                                onBack = back,
                            )
                            Screen.Home -> HomeScreen(
                                state = state,
                                onWindow = vm::setWindow,
                                onOpenSensorSettings = { open(Screen.SettingsSensor) },
                                onOpenDiagnostics = { open(Screen.Diagnostics) },
                                onOpenServer = openServer,
                                onStart = { enableCollector(true) },
                                activeAlarm = activeAlarm,
                                onSnoozeAlarm = { type -> G7App.instance.alarms.snooze(type) },
                                alarmsPausedUntil = alarmsPausedUntil,
                                onResumeAlarms = { vm.pauseAlarms(null) },
                                update = updateState,
                                updateActions = updateActions,
                            )
                        }
                    }
                }
            }
        }
    }
}

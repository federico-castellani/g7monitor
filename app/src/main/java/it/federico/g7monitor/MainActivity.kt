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
import androidx.compose.foundation.layout.fillMaxSize
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
import it.federico.g7monitor.ui.server.ServerAddressScreen
import it.federico.g7monitor.ui.server.ServerLoginScreen
import it.federico.g7monitor.ui.server.ServerStatusActions
import it.federico.g7monitor.ui.server.ServerStatusScreen
import it.federico.g7monitor.ui.server.WelcomeScreen
import it.federico.g7monitor.ui.settings.SettingsActions
import it.federico.g7monitor.ui.settings.SettingsScreen
import it.federico.g7monitor.ui.theme.G7Theme
import kotlinx.coroutines.launch

private enum class Screen { Home, Settings, Diagnostics, ServerAddress, ServerLogin, ServerStatus, Alarms, AlarmDetail }

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val requiredPermissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private fun hasBluetoothPermissions() = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

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
        setContent {
            G7Theme {
                val state by vm.state.collectAsStateWithLifecycle()
                val serviceRunning by G7Service.running.collectAsStateWithLifecycle()
                val alarmConfigs by vm.alarmConfigs.collectAsStateWithLifecycle()
                var alarmType by rememberSaveable { mutableStateOf(AlarmType.UrgentLow) }
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }
                /** Where the server pages return to (Home or Settings). */
                var serverReturn by rememberSaveable { mutableStateOf(Screen.Settings) }
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
                val openServer: (from: Screen) -> Unit = { from ->
                    serverReturn = from
                    screen = if (linked) Screen.ServerStatus else Screen.ServerAddress
                }

                if (!state.ready) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    return@G7Theme
                }
                if (settings.dataSource == null) {
                    WelcomeScreen(onChoose = { source ->
                        vm.setDataSource(source)
                        if (source == DataSource.Server) {
                            serverReturn = Screen.Home
                            screen = Screen.ServerAddress
                        } else {
                            screen = Screen.Home
                        }
                    })
                    return@G7Theme
                }

                BackHandler(enabled = screen != Screen.Home) {
                    screen = when (screen) {
                        Screen.ServerLogin -> Screen.ServerAddress
                        Screen.ServerAddress, Screen.ServerStatus -> serverReturn
                        Screen.AlarmDetail -> Screen.Alarms
                        Screen.Alarms -> Screen.Settings
                        else -> Screen.Home
                    }
                }
                when (screen) {
                    Screen.Settings -> SettingsScreen(
                        settings = settings,
                        serviceRunning = serviceRunning,
                        actions = SettingsActions(
                            onPairingCode = vm::setPairingCode,
                            onCollector = enableCollector,
                            onMmol = vm::setUseMmol,
                            onRange = vm::setRange,
                            onCertificates = vm::setCertificates,
                            onForget = vm::forgetSensor,
                            onDataSource = { source -> vm.setDataSource(source) },
                            onOpenServer = { openServer(Screen.Settings) },
                            onOpenAlarms = { screen = Screen.Alarms },
                        ),
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Alarms -> AlarmsScreen(
                        configs = alarmConfigs,
                        mmol = settings.useMmol,
                        receiving = settings.collectorEnabled,
                        onOpen = { type -> alarmType = type; screen = Screen.AlarmDetail },
                        onToggle = { type, on -> toggleAlarm(alarmConfigs.getValue(type), on) },
                        onBack = { screen = Screen.Settings },
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
                            onBack = { screen = Screen.Alarms },
                        )
                    }
                    Screen.Diagnostics -> DiagnosticsScreen(
                        settings = settings,
                        status = state.status,
                        onBack = { screen = Screen.Home },
                    )
                    Screen.ServerAddress -> ServerAddressScreen(
                        purpose = purpose,
                        initialUrl = (if (purpose == ServerPurpose.Follow) settings.followUrl else settings.serverUrl).removePrefix("https://"),
                        onCheck = vm::checkServer,
                        onFound = { url ->
                            pendingUrl = url
                            screen = Screen.ServerLogin
                        },
                        onBack = { screen = serverReturn },
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
                                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                screen = Screen.Home
                            } else {
                                screen = Screen.ServerStatus
                            }
                        },
                        onBack = { screen = Screen.ServerAddress },
                    )
                    Screen.ServerStatus -> ServerStatusScreen(
                        purpose = purpose,
                        settings = settings,
                        actions = ServerStatusActions(
                            onNow = { if (purpose == ServerPurpose.Follow) vm.refreshFromServer() else vm.uploadNow() },
                            onDisconnect = {
                                vm.disconnectServer(purpose)
                                screen = serverReturn
                            },
                            onUploadInterval = vm::setUploadInterval,
                            onUploadWifiOnly = vm::setUploadWifiOnly,
                            onBackground = enableCollector,
                        ),
                        onBack = { screen = serverReturn },
                    )
                    Screen.Home -> HomeScreen(
                        state = state,
                        onWindow = vm::setWindow,
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenDiagnostics = { screen = Screen.Diagnostics },
                        onOpenServer = { openServer(Screen.Home) },
                        onStart = { enableCollector(true) },
                    )
                }
            }
        }
    }
}

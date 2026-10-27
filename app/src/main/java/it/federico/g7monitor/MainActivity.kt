package it.federico.g7monitor

import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import it.federico.g7monitor.ble.G7Service
import kotlinx.coroutines.launch
import it.federico.g7monitor.ui.MainViewModel
import it.federico.g7monitor.ui.diagnostics.DiagnosticsScreen
import it.federico.g7monitor.ui.home.HomeScreen
import it.federico.g7monitor.ui.settings.SettingsActions
import it.federico.g7monitor.ui.settings.SettingsScreen
import it.federico.g7monitor.ui.theme.G7Theme

private enum class Screen { Home, Settings, Diagnostics }

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
        // Make sure the collector runs if it was enabled (e.g. after the app was force-stopped).
        lifecycleScope.launch {
            if (G7App.instance.settings.current().collectorEnabled && hasBluetoothPermissions()) G7Service.start(this@MainActivity)
        }
        setContent {
            G7Theme {
                val state by vm.state.collectAsStateWithLifecycle()
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    if (hasBluetoothPermissions()) vm.setCollector(true)
                    else Toast.makeText(this, "Servono i permessi Bluetooth per collegarsi al sensore", Toast.LENGTH_LONG).show()
                }
                val enableCollector: (Boolean) -> Unit = { on ->
                    if (!on) vm.setCollector(false)
                    else if (hasBluetoothPermissions()) vm.setCollector(true)
                    else permissionLauncher.launch(requiredPermissions)
                }

                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                when (screen) {
                    Screen.Settings -> SettingsScreen(
                        settings = state.settings,
                        actions = SettingsActions(
                            onPairingCode = vm::setPairingCode,
                            onCollector = enableCollector,
                            onMmol = vm::setUseMmol,
                            onRange = vm::setRange,
                            onCertificates = vm::setCertificates,
                            onForget = vm::forgetSensor,
                        ),
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Diagnostics -> DiagnosticsScreen(
                        settings = state.settings,
                        status = state.status,
                        onBack = { screen = Screen.Home },
                    )
                    Screen.Home -> HomeScreen(
                        state = state,
                        onWindow = vm::setWindow,
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenDiagnostics = { screen = Screen.Diagnostics },
                        onStart = { enableCollector(true) },
                    )
                }
            }
        }
    }
}

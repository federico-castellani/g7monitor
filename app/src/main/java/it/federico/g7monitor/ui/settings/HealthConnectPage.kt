package it.federico.g7monitor.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import kotlinx.coroutines.launch

/** Health Connect: one switch, the permission it needs, and a way into Health Connect itself. */
@Composable
fun HealthConnectSections(settings: AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hc = G7App.instance.health
    val status = remember { hc.status() }
    var granted by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { granted = hc.hasPermission() }
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        val ok = result.containsAll(hc.permissions)
        granted = ok
        if (ok) scope.launch { hc.enable() }
    }

    SectionCard(stringResource(R.string.page_health), Icons.Outlined.HealthAndSafety) {
        Text(
            stringResource(R.string.hc_explain),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (status) {
            HealthConnectClient.SDK_UNAVAILABLE ->
                Text(stringResource(R.string.hc_unavailable), color = MaterialTheme.colorScheme.error)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Text(stringResource(R.string.hc_update_needed), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                                .setPackage("com.android.vending")
                        )
                    }
                }) { Text(stringResource(R.string.hc_install)) }
            }
            else -> {
                val on = settings.healthConnect && granted == true
                SettingRow(
                    Icons.Outlined.HealthAndSafety, stringResource(R.string.hc_write), stringResource(R.string.hc_write_text),
                    trailing = {
                        Switch(checked = on, onCheckedChange = { v ->
                            when {
                                !v -> scope.launch { G7App.instance.settings.setHealthConnect(false) }
                                granted == true -> scope.launch { hc.enable() }
                                else -> request.launch(hc.permissions)
                            }
                        })
                    },
                )
                if (settings.healthConnect && granted == false) {
                    Text(stringResource(R.string.hc_permission_missing), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { request.launch(hc.permissions) }) { Text(stringResource(R.string.action_allow)) }
                }
                TextButton(onClick = {
                    runCatching { context.startActivity(HealthConnectClient.getHealthConnectManageDataIntent(context)) }
                }) { Text(stringResource(R.string.hc_open)) }
            }
        }
    }
}

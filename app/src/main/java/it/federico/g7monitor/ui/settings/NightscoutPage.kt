package it.federico.g7monitor.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import it.federico.g7monitor.ui.theme.LocalRangeColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Nightscout upload (sensor mode): address and secret, then its status, pause and "Invia ora". */
@Composable
fun NightscoutSections(settings: AppSettings) {
    if (settings.nightscoutConnected) Connected(settings) else Setup(settings)
}

/** One line for the settings menu. */
@Composable
fun nightscoutSummary(s: AppSettings): String = when {
    !s.nightscoutConnected -> stringResource(R.string.menu_server_optional)
    s.nightscoutPaused -> stringResource(R.string.srv_paused)
    s.nightscoutError != null -> s.nightscoutError
    else -> stringResource(R.string.ns_menu_sending, s.nightscoutUrl.removePrefix("https://"))
}

@Composable
private fun Setup(settings: AppSettings) {
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(settings.nightscoutUrl.removePrefix("https://")) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SectionCard(stringResource(R.string.page_nightscout), Icons.Outlined.CloudUpload) {
        Text(
            stringResource(R.string.ns_explain),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim(); error = null },
            label = { Text(stringResource(R.string.ns_address)) },
            placeholder = { Text(stringResource(R.string.ns_address_example)) },
            leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
            supportingText = { Text(stringResource(R.string.srv_https_only)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret, onValueChange = { secret = it; error = null },
            label = { Text(stringResource(R.string.ns_secret)) },
            supportingText = { Text(stringResource(R.string.ns_secret_hint)) },
            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Button(
            onClick = {
                busy = true
                scope.launch {
                    error = G7App.instance.nightscout.connect(url, secret)
                    if (error == null) secret = ""
                    busy = false
                }
            },
            enabled = !busy && url.isNotBlank() && secret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text(stringResource(R.string.home_connect))
        }
        Text(
            stringResource(R.string.ns_first_upload),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Connected(settings: AppSettings) {
    val app = G7App.instance
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.getDefault()) }
    val paused = settings.nightscoutPaused

    SectionCard(stringResource(R.string.srv_connection), Icons.Outlined.CloudUpload) {
        SettingRow(Icons.Outlined.Dns, settings.nightscoutUrl.removePrefix("https://"), stringResource(R.string.page_nightscout))
        val error = settings.nightscoutError
        when {
            paused -> SettingRow(Icons.Outlined.PauseCircle, stringResource(R.string.srv_paused), stringResource(R.string.srv_paused_text))
            error != null -> SettingRow(Icons.Outlined.ErrorOutline, error, null, iconTint = MaterialTheme.colorScheme.error)
            else -> SettingRow(
                Icons.Outlined.Schedule,
                settings.lastNightscoutAt?.let { stringResource(R.string.srv_last_upload, timeFmt.format(Date(it))) }
                    ?: stringResource(R.string.srv_waiting_next),
                null, iconTint = LocalRangeColors.current.inRange,
            )
        }
        FilledTonalButton(onClick = { scope.launch { app.nightscout.maybeUpload(force = true) } }, enabled = !paused) {
            Text(stringResource(R.string.srv_upload_now))
        }
        HorizontalDivider()
        SettingRow(
            if (paused) Icons.Outlined.PauseCircle else Icons.Outlined.CloudUpload,
            stringResource(R.string.ns_upload),
            stringResource(if (paused) R.string.srv_upload_paused else R.string.ns_upload_active),
            trailing = {
                Switch(checked = !paused, onCheckedChange = { on ->
                    scope.launch {
                        app.settings.setNightscoutPaused(!on)
                        if (on) app.nightscout.maybeUpload(force = true)
                    }
                })
            },
        )
    }

    TextButton(
        onClick = { confirm = true },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) {
        Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.ns_disconnect))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
            title = { Text(stringResource(R.string.ns_disconnect_title)) },
            text = { Text(stringResource(R.string.ns_disconnect_text)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; scope.launch { app.settings.disconnectNightscout() } }) {
                    Text(stringResource(R.string.action_disconnect))
                }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

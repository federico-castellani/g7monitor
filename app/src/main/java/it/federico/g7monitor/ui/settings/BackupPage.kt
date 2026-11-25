package it.federico.g7monitor.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.G7App
import it.federico.g7monitor.R
import it.federico.g7monitor.data.SettingsBackup
import it.federico.g7monitor.str
import it.federico.g7monitor.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings and alarms to a file of the user's choice, and back (after a confirmation). */
@Composable
fun BackupSections() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = G7App.instance
    var message by remember { mutableStateOf<String?>(null) }
    /** File picked for a restore, waiting for the confirmation. */
    var chosen by remember { mutableStateOf<Uri?>(null) }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = runCatching {
                val text = SettingsBackup.export(app)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
            }.fold({ str(R.string.backup_saved) }, { str(R.string.backup_save_failed) })
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> chosen = uri }

    chosen?.let { uri ->
        AlertDialog(
            onDismissRequest = { chosen = null },
            title = { Text(stringResource(R.string.backup_import_title)) },
            text = { Text(stringResource(R.string.backup_import_text)) },
            confirmButton = {
                TextButton(onClick = {
                    chosen = null
                    scope.launch {
                        message = runCatching {
                            val text = withContext(Dispatchers.IO) {
                                context.contentResolver.openInputStream(uri)!!.use { input ->
                                    val buf = ByteArray(MAX_BYTES)
                                    var n = 0
                                    while (n < buf.size) {
                                        val r = input.read(buf, n, buf.size - n)
                                        if (r < 0) break
                                        n += r
                                    }
                                    String(buf, 0, n)
                                }
                            }
                            SettingsBackup.import(app, text)
                        }.fold({ str(R.string.backup_restored, it) }, { str(R.string.backup_not_valid) })
                    }
                }) { Text(stringResource(R.string.backup_import_confirm)) }
            },
            dismissButton = { TextButton(onClick = { chosen = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    SectionCard(stringResource(R.string.backup_title), Icons.Outlined.SettingsBackupRestore) {
        Text(
            stringResource(R.string.backup_explain),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { exporter.launch(SettingsBackup.fileName()) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.FileDownload, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.backup_export))
        }
        OutlinedButton(
            onClick = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.FileUpload, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.backup_import))
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** A backup is a few kilobytes: anything much larger is not one. */
private const val MAX_BYTES = 256 * 1024

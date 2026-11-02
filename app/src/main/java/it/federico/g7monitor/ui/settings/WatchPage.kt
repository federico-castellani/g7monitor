package it.federico.g7monitor.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.G7App
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import it.federico.g7monitor.ui.theme.LocalRangeColors
import it.federico.g7monitor.update.AppUpdater
import it.federico.g7monitor.watch.WatchSync
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import it.federico.g7monitor.R

/**
 * The Wear OS watch: whether G7 Monitor is on it and up to date, the update in one tap, and the
 * one-time first install (the only step that needs a PC).
 */
@Composable
fun WatchSections() {
    val sync = G7App.instance.watch
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var watches by remember { mutableStateOf<List<WatchSync.Watch>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh) { watches = sync.watches() }
    val phone = sync.phoneVersion
    val ok = LocalRangeColors.current.inRange
    val code = { v: String? -> v?.let(AppUpdater::versionCode) ?: 0 }

    SectionCard(stringResource(R.string.watch_yours), Icons.Outlined.Watch) {
        val list = watches
        when {
            list == null -> Text(stringResource(R.string.watch_searching), style = MaterialTheme.typography.bodyMedium)
            list.isEmpty() -> Text(
                stringResource(R.string.watch_none),
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> for (w in list) {
                when {
                    w.appVersion == null -> SettingRow(
                        Icons.Outlined.ErrorOutline, w.name,
                        stringResource(R.string.watch_not_installed),
                        iconTint = MaterialTheme.colorScheme.error,
                    )
                    code(w.appVersion) < code(phone) -> SettingRow(
                        Icons.Outlined.SystemUpdate, w.name,
                        stringResource(R.string.watch_outdated, w.appVersion, phone),
                        iconTint = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> SettingRow(
                        Icons.Outlined.CheckCircle, w.name, stringResource(R.string.watch_up_to_date, w.appVersion), iconTint = ok,
                    )
                }
            }
        }
        val outdated = list.orEmpty().any { it.appVersion != null && code(it.appVersion) < code(phone) }
        if (outdated) {
            FilledTonalButton(onClick = {
                scope.launch {
                    sync.announceVersion(force = true)
                    Toast.makeText(context, R.string.watch_update_sent, Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.watch_update)) }
        }
        TextButton(onClick = { watches = null; refresh++ }) { Text(stringResource(R.string.watch_check_again)) }
        Text(
            stringResource(R.string.watch_updates_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard(stringResource(R.string.watch_first_install), Icons.Outlined.SystemUpdate) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val steps = listOf(R.string.watch_step1, R.string.watch_step2, R.string.watch_step3, R.string.watch_step4)
            steps.forEachIndexed { i, s ->
                Text(stringResource(R.string.step_numbered, i + 1, stringResource(s)), style = MaterialTheme.typography.bodyMedium)
            }
        }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(INSTALL_GUIDE)))
        }) { Text(stringResource(R.string.watch_open_guide)) }
    }
}

private const val INSTALL_GUIDE = "https://github.com/federico-castellani/g7monitor#smartwatch-wear-os-complicazioni"

package it.federico.g7monitor.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.update.AppRelease
import it.federico.g7monitor.update.AppUpdater
import it.federico.g7monitor.update.UpdateState
import it.federico.g7monitor.ui.components.IconBadge

class UpdateActions(
    val onCheck: () -> Unit,
    val onInstall: (AppRelease) -> Unit,
    /** Back to "available" (after allowing installs, or after an error). */
    val onReset: () -> Unit,
)

/** The release the state is about, if any. */
private val UpdateState.release: AppRelease?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.NeedsPermission -> release
        is UpdateState.Downloading -> release
        is UpdateState.Installing -> release
        is UpdateState.Failed -> release
        else -> null
    }

/** Short state for the settings menu. */
fun updateSummary(state: UpdateState, installed: String): String = when (state) {
    is UpdateState.Checking -> "Versione $installed · controllo in corso…"
    is UpdateState.UpToDate -> "Versione $installed · aggiornata"
    is UpdateState.Downloading -> "Download della versione ${state.release.version}…"
    is UpdateState.Failed -> state.message
    else -> state.release?.let { "Disponibile la versione ${it.version}" } ?: "Versione $installed"
}

/** Home card: shown only while there is something to do with a new version. */
@Composable
fun UpdateCard(state: UpdateState, actions: UpdateActions) {
    val release = state.release ?: return
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    Icons.Outlined.SystemUpdate, tint = MaterialTheme.colorScheme.onTertiary,
                    container = MaterialTheme.colorScheme.tertiary, size = 44.dp,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Aggiornamento disponibile", style = MaterialTheme.typography.titleMedium)
                    Text("Versione ${release.version}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            UpdateControls(state, actions)
        }
    }
}

/** Settings page: version, "Controlla ora", what is new and the install controls. */
@Composable
fun UpdatesPanel(state: UpdateState, installed: String, actions: UpdateActions) {
    val context = LocalContext.current
    Text("Versione installata: $installed", style = MaterialTheme.typography.bodyLarge)
    when (state) {
        is UpdateState.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
        is UpdateState.UpToDate -> Text("È l'ultima versione.", style = MaterialTheme.typography.bodyMedium)
        else -> Unit
    }
    state.release?.let { release ->
        Text("Nuova versione: ${release.version}", style = MaterialTheme.typography.titleMedium)
        release.notes.takeIf { it.isNotBlank() }?.let { notes ->
            Text(notes.take(1_500), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UpdateControls(state, actions)
    }
    if (state.release == null && state is UpdateState.Failed) {
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    if (state.release == null) {
        Button(onClick = actions.onCheck, enabled = state !is UpdateState.Checking) { Text("Controlla ora") }
    }
    Text(
        "L'app controlla da sola ogni qualche ora, quando la apri. Le nuove versioni arrivano dalla pagina " +
            "GitHub del progetto; impostazioni, abbinamenti e letture restano.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdater.RELEASES_PAGE)))
    }) { Text("Apri la pagina delle versioni") }
}

@Composable
private fun UpdateControls(state: UpdateState, actions: UpdateActions) {
    val context = LocalContext.current
    val release = state.release ?: return
    when (state) {
        is UpdateState.Downloading -> {
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text("Download in corso… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        is UpdateState.Installing -> Text(
            "Conferma l'installazione nella finestra di Android. L'app si riavvia da sola.",
            style = MaterialTheme.typography.bodySmall,
        )
        is UpdateState.NeedsPermission -> {
            Text(
                "Solo la prima volta: Android deve consentire a G7 Monitor di installare aggiornamenti. " +
                    "Attiva l'opzione, torna qui e tocca di nuovo Aggiorna.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                actions.onReset()
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                )
            }) { Text("Consenti") }
        }
        is UpdateState.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { actions.onInstall(release) }) { Text("Riprova") }
        }
        else -> Button(onClick = { actions.onInstall(release) }) { Text("Aggiorna") }
    }
}

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

    SectionCard("Il tuo orologio", Icons.Outlined.Watch) {
        val list = watches
        when {
            list == null -> Text("Cerco l'orologio…", style = MaterialTheme.typography.bodyMedium)
            list.isEmpty() -> Text(
                "Nessun orologio collegato al telefono. Controlla che sia acceso, vicino e abbinato nell'app " +
                    "Galaxy Wearable (o Wear OS).",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> for (w in list) {
                when {
                    w.appVersion == null -> SettingRow(
                        Icons.Outlined.ErrorOutline, w.name,
                        "G7 Monitor non è installato sull'orologio, oppure non è mai stato aperto: segui l'installazione qui sotto",
                        iconTint = MaterialTheme.colorScheme.error,
                    )
                    code(w.appVersion) < code(phone) -> SettingRow(
                        Icons.Outlined.SystemUpdate, w.name,
                        "Versione ${w.appVersion}, sul telefono c'è la $phone: aggiornalo",
                        iconTint = MaterialTheme.colorScheme.tertiary,
                    )
                    else -> SettingRow(Icons.Outlined.CheckCircle, w.name, "G7 Monitor ${w.appVersion}: aggiornato", iconTint = ok)
                }
            }
        }
        val outdated = list.orEmpty().any { it.appVersion != null && code(it.appVersion) < code(phone) }
        if (outdated) {
            FilledTonalButton(onClick = {
                scope.launch {
                    sync.announceVersion(force = true)
                    Toast.makeText(context, "Sull'orologio è arrivata una notifica: toccala e conferma l'aggiornamento", Toast.LENGTH_LONG).show()
                }
            }) { Text("Aggiorna l'orologio") }
        }
        TextButton(onClick = { watches = null; refresh++ }) { Text("Controlla di nuovo") }
        Text(
            "Gli aggiornamenti dell'orologio arrivano da soli: quando aggiorni l'app sul telefono, sull'orologio compare " +
                "una notifica; toccala e conferma. L'orologio scarica la nuova versione da internet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SectionCard("Prima installazione (una volta sola)", Icons.Outlined.SystemUpdate) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val steps = listOf(
                "Sull'orologio: Impostazioni → Info sull'orologio → Informazioni sul software, tocca 5 volte \"Versione software\": compaiono le Opzioni sviluppatore.",
                "Opzioni sviluppatore: attiva \"Debug ADB\" e \"Debug wireless\" (orologio e computer sulla stessa rete Wi-Fi), poi tocca \"Abbina nuovo dispositivo\".",
                "Sul computer scarica ed esegui lo script di installazione (pulsante qui sotto): ti chiede l'indirizzo e il codice mostrati dall'orologio, scarica l'ultima versione e la installa.",
                "Apri G7 Monitor sull'orologio una volta e consenti le notifiche: da lì in poi si aggiorna da solo. Puoi spegnere il Debug wireless.",
            )
            steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodyMedium) }
        }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(INSTALL_GUIDE)))
        }) { Text("Apri lo script e la guida") }
    }
}

private const val INSTALL_GUIDE = "https://github.com/federico-castellani/g7monitor#smartwatch-wear-os-complicazioni"

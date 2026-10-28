package it.federico.g7monitor.ui.server

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import it.federico.g7monitor.data.AppSettings
import it.federico.g7monitor.data.DataSource
import it.federico.g7monitor.ui.MainViewModel.ServerResult
import it.federico.g7monitor.ui.ServerPurpose
import it.federico.g7monitor.ui.components.IconBadge
import it.federico.g7monitor.ui.components.SectionCard
import it.federico.g7monitor.ui.components.SettingRow
import it.federico.g7monitor.ui.theme.LocalRangeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ shared scaffold

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            content()
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Intro(icon: ImageVector, title: String, text: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        IconBadge(icon, tint = MaterialTheme.colorScheme.onPrimaryContainer, container = MaterialTheme.colorScheme.primaryContainer, size = 64.dp)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ServerPurpose.title() = if (this == ServerPurpose.Follow) "Server da cui ricevere" else "Server dove caricare"

// ------------------------------------------------------------------ 0. data source

/** First launch (and Settings → Origine dei dati): where does this phone get the readings? */
@Composable
fun WelcomeScreen(onChoose: (DataSource) -> Unit, onBack: (() -> Unit)? = null) {
    Page("G7 Monitor", onBack) {
        Intro(
            Icons.Outlined.Schedule, "Da dove arrivano le glicemie?",
            "Puoi cambiare scelta in qualsiasi momento dalle impostazioni.",
        )
        ChoiceCard(
            Icons.Outlined.Bluetooth, "Dal sensore G7",
            "Questo telefono si collega al sensore via Bluetooth. Funziona anche senza server; " +
                "se vuoi puoi caricare le glicemie su un server per condividerle.",
        ) { onChoose(DataSource.Sensor) }
        ChoiceCard(
            Icons.Outlined.CloudDownload, "Dal server",
            "Ricevi le glicemie di un'altra persona da un server G7 Monitor, senza sensore.",
        ) { onChoose(DataSource.Server) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceCard(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint = MaterialTheme.colorScheme.primary, container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), size = 48.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ 1. address

/** Step 1: only the address. The credentials page opens only if a G7 Monitor server answers. */
@Composable
fun ServerAddressScreen(
    purpose: ServerPurpose,
    initialUrl: String,
    onCheck: (url: String, onResult: (ServerResult) -> Unit) -> Unit,
    onFound: (url: String) -> Unit,
    onBack: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val check = {
        busy = true
        error = null
        onCheck(url) { r ->
            busy = false
            when (r) {
                is ServerResult.Ok -> onFound(r.url)
                is ServerResult.Error -> error = r.message
                ServerResult.NeedsTotp -> Unit
            }
        }
    }

    Page(purpose.title(), onBack) {
        Intro(
            Icons.Outlined.Dns, "Indirizzo del server",
            if (purpose == ServerPurpose.Follow) "L'indirizzo del server G7 Monitor da cui ricevere le glicemie. Lo trovi nella barra del browser quando apri il sito."
            else "L'indirizzo del server G7 Monitor su cui caricare le glicemie del sensore.",
        )
        OutlinedTextField(
            value = url, onValueChange = { url = it.trim(); error = null },
            label = { Text("Indirizzo") }, placeholder = { Text("glicemie.esempio.it") },
            leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
            supportingText = { Text("Solo connessioni sicure (https)") },
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (!busy && url.isNotBlank()) check() }),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        error?.let { ErrorText(it) }
        Button(onClick = check, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (busy) Progress() else Text("Continua")
        }
    }
}

// ------------------------------------------------------------------ 2. credentials

@Composable
fun ServerLoginScreen(
    purpose: ServerPurpose,
    url: String,
    initialUser: String,
    onLogin: (user: String, password: String, totp: String?, onResult: (ServerResult) -> Unit) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    var user by rememberSaveable { mutableStateOf(initialUser) }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    var needsTotp by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val canSubmit = !busy && user.isNotBlank() && password.isNotEmpty() && (!needsTotp || totp.length == 6)
    val login = {
        busy = true
        error = null
        onLogin(user, password, totp.ifBlank { null }) { r ->
            busy = false
            when (r) {
                is ServerResult.Ok -> { password = ""; onDone() }
                ServerResult.NeedsTotp -> { needsTotp = true; error = "Inserisci il codice di verifica" }
                is ServerResult.Error -> { error = r.message; totp = "" }
            }
        }
    }

    Page(purpose.title(), onBack) {
        Intro(
            Icons.Outlined.Lock, "Accedi",
            "Server trovato: ${url.removePrefix("https://")}\n" +
                if (purpose == ServerPurpose.Follow) "Usa il tuo account del sito. Basta un account in sola lettura."
                else "Serve un account con il permesso di caricare le glicemie.",
        )
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("Nome utente") },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (needsTotp) ImeAction.Next else ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) login() }),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (needsTotp) {
            OutlinedTextField(
                value = totp, onValueChange = { v -> totp = v.filter(Char::isDigit).take(6) },
                label = { Text("Codice di verifica") },
                supportingText = { Text("Dall'app di autenticazione") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSubmit) login() }),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { ErrorText(it) }
        Button(onClick = login, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (busy) Progress() else Text("Accedi")
        }
        Text(
            "La password serve solo ora: il telefono conserva un codice di accesso che puoi revocare dal sito.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ------------------------------------------------------------------ 3. connected

class ServerStatusActions(
    val onNow: () -> Unit,
    val onDisconnect: () -> Unit,
    val onUploadInterval: (Int) -> Unit,
    val onUploadWifiOnly: (Boolean) -> Unit,
    val onBackground: (Boolean) -> Unit,
)

@Composable
fun ServerStatusScreen(purpose: ServerPurpose, settings: AppSettings, actions: ServerStatusActions, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    val timeFmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.ITALY) }
    val follow = purpose == ServerPurpose.Follow
    val url = if (follow) settings.followUrl else settings.serverUrl
    val user = if (follow) settings.followUser else settings.serverUser
    val last = if (follow) settings.lastFollowAt else settings.lastUploadAt
    val error = if (follow) settings.followError else settings.uploadError

    Page(purpose.title(), onBack) {
        SectionCard("Collegamento", Icons.Outlined.CloudDone) {
            SettingRow(Icons.Outlined.Dns, url.removePrefix("https://"), "Server G7 Monitor")
            SettingRow(Icons.Outlined.Person, user, if (follow) "Account (ricezione)" else "Account (caricamento)")
            if (error != null) {
                SettingRow(Icons.Outlined.ErrorOutline, error, null, iconTint = MaterialTheme.colorScheme.error)
            } else {
                SettingRow(
                    Icons.Outlined.Schedule,
                    last?.let { (if (follow) "Ultimo aggiornamento: " else "Ultimo invio: ") + timeFmt.format(Date(it)) }
                        ?: if (follow) "In attesa del primo aggiornamento" else "In attesa della prossima lettura",
                    null, iconTint = LocalRangeColors.current.inRange,
                )
            }
            FilledTonalButton(onClick = actions.onNow) { Text(if (follow) "Aggiorna ora" else "Invia ora") }
        }

        if (follow) {
            SectionCard("Ricezione", Icons.Outlined.CloudDownload) {
                SettingRow(
                    Icons.Outlined.Notifications, "In background",
                    "Notifica con l'ultima glicemia, anche ad app chiusa",
                    trailing = { Switch(checked = settings.collectorEnabled, onCheckedChange = actions.onBackground) },
                )
                Text(
                    "Il telefono chiede i dati al server solo quando è attesa una nuova lettura (circa ogni 5 minuti), " +
                        "senza tenere una connessione sempre aperta.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SectionCard("Invio", Icons.Outlined.CloudUpload) {
                Text("Frequenza di invio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val intervals = listOf(5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 ora")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    intervals.forEachIndexed { i, (minutes, label) ->
                        SegmentedButton(
                            selected = settings.uploadIntervalMin == minutes,
                            onClick = { actions.onUploadInterval(minutes) },
                            shape = SegmentedButtonDefaults.itemShape(i, intervals.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    "Con 5 minuti chi ti segue vede ogni lettura subito. Intervalli più lunghi raggruppano le letture " +
                        "in un solo invio: meno batteria e meno dati.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                SettingRow(
                    Icons.Outlined.Wifi, "Solo con Wi-Fi", "Con la rete mobile le letture restano sul telefono",
                    trailing = { Switch(checked = settings.uploadWifiOnly, onCheckedChange = actions.onUploadWifiOnly) },
                )
            }
        }

        TextButton(
            onClick = { confirm = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Scollega dal server")
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
            title = { Text("Scollegare il server?") },
            text = {
                Text(
                    if (follow) "Il telefono smette di ricevere le glicemie e il suo codice di accesso viene revocato."
                    else "Il telefono smette di caricare le glicemie e il suo codice di accesso viene revocato. " +
                        "Il collegamento al sensore continua e le letture restano sul telefono."
                )
            },
            confirmButton = { TextButton(onClick = { confirm = false; actions.onDisconnect() }) { Text("Scollega") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun ErrorText(text: String) =
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Progress() =
    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)

package it.federico.g7monitor.wear

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.collectAsState
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource

private val LOW = Color(0xFFFF6B6F)
private val IN_RANGE = Color(0xFF4CD597)
private val HIGH = Color(0xFFFFC145)
private val STALE = Color(0xFF8A9099)

/** Single screen: the latest reading as received from the phone. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Data the phone sent before this app was installed or opened.
        lifecycleScope.launch(Dispatchers.IO) { DataLayerListenerService.fetchLatest(this@MainActivity) }
        // Our version, for the phone's "Orologio" page.
        DataLayerListenerService.publishVersion(this)
        // Wear OS 4+: notifications (the "update available" one) need the user's consent.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val fromNotification = intent.getBooleanExtra(WatchUpdater.EXTRA_UPDATE, false)
        if (fromNotification) WatchUpdater.cancelNotification(this)
        lifecycleScope.launch { WatchUpdater.check(this@MainActivity, force = fromNotification) }
        setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    GlucoseScreen()
                    UpdateChip(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)) { release ->
                        lifecycleScope.launch { WatchUpdater.install(this@MainActivity, release) }
                    }
                }
            }
        }
    }
}

/** Shown only when there is something to say about an update: new version, download, error. */
@Composable
private fun UpdateChip(modifier: Modifier, onInstall: (WatchUpdater.Release) -> Unit) {
    val state by WatchUpdater.state.collectAsState()
    when (val s = state) {
        is WatchUpdater.State.Available -> CompactChip(
            onClick = { onInstall(s.release) },
            label = { Text(stringResource(R.string.w_update_to, s.release.version), fontSize = 12.sp) },
            colors = ChipDefaults.primaryChipColors(),
            modifier = modifier,
        )
        is WatchUpdater.State.Downloading -> Text(stringResource(R.string.w_downloading, (s.progress * 100).toInt()), fontSize = 12.sp, color = STALE, modifier = modifier)
        is WatchUpdater.State.Installing -> Text(stringResource(R.string.w_confirm_install), fontSize = 12.sp, color = STALE, modifier = modifier)
        is WatchUpdater.State.Failed -> Text(s.message, fontSize = 11.sp, color = LOW, textAlign = TextAlign.Center, modifier = modifier.padding(horizontal = 24.dp))
        else -> Unit
    }
}

@Composable
private fun GlucoseScreen() {
    val context = LocalContext.current
    var g by remember { mutableStateOf(GlucoseStore.load(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    DisposableEffect(Unit) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> g = GlucoseStore.load(context) }
        val prefs = GlucoseStore.prefs(context)
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Ages the reading on screen only while the screen is visible: no wake-ups when the app is left open.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(30_000)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).padding(12.dp), contentAlignment = Alignment.Center) {
        val reading = g
        if (reading == null) {
            Text(
                stringResource(R.string.w_waiting),
                textAlign = TextAlign.Center, color = STALE, fontSize = 14.sp,
            )
            return@Box
        }
        val stale = reading.isStale(now)
        val color = when {
            stale -> STALE
            reading.mgdl < reading.low -> LOW
            reading.mgdl > reading.high -> HIGH
            else -> IN_RANGE
        }
        val minutes = ((now - reading.time) / 60_000L).coerceAtLeast(0)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reading.value, color = color, fontSize = 52.sp, fontWeight = FontWeight.Bold)
                if (!stale && reading.arrow.isNotEmpty()) Text(" ${reading.arrow}", color = color, fontSize = 36.sp)
            }
            Text(listOfNotNull(reading.unit, reading.deltaText?.takeIf { !stale }).joinToString("  ·  "), color = Color.LightGray, fontSize = 14.sp)
            Text(
                when {
                    minutes < 1 -> stringResource(R.string.w_now)
                    stale -> stringResource(R.string.w_no_reading_for, minutes.toInt())
                    else -> stringResource(R.string.w_min_ago, minutes.toInt())
                },
                color = if (stale) LOW else STALE, fontSize = 13.sp,
            )
        }
    }
}

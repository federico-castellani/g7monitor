package it.federico.g7monitor.wear

import android.content.SharedPreferences
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
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        setContent { MaterialTheme { GlucoseScreen() } }
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
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).padding(12.dp), contentAlignment = Alignment.Center) {
        val reading = g
        if (reading == null) {
            Text(
                "In attesa delle glicemie dal telefono.\nApri G7 Monitor sul telefono.",
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
                    minutes < 1 -> "adesso"
                    stale -> "nessuna lettura da $minutes min"
                    else -> "$minutes min fa"
                },
                color = if (stale) LOW else STALE, fontSize = 13.sp,
            )
        }
    }
}

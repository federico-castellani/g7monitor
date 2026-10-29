package it.federico.g7monitor.ui.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.federico.g7monitor.G7App
import it.federico.g7monitor.alarm.AlarmType
import it.federico.g7monitor.ui.theme.G7Theme
import it.federico.g7monitor.ui.theme.LocalRangeColors

/** Shown on top of the lock screen while an alarm rings; closes by itself when the alarm ends. */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        val engine = (application as G7App).alarms
        setContent {
            G7Theme {
                val alarm by engine.active.collectAsStateWithLifecycle()
                LaunchedEffect(alarm) { if (alarm == null) finish() }
                val a = alarm ?: return@G7Theme
                val range = LocalRangeColors.current
                val color = when (a.type) {
                    AlarmType.UrgentLow, AlarmType.Low, AlarmType.FallingFast -> range.low
                    AlarmType.UrgentHigh, AlarmType.High, AlarmType.RisingFast -> range.high
                    AlarmType.SignalLoss -> MaterialTheme.colorScheme.primary
                }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(color.copy(alpha = 0.16f).compositeOver(MaterialTheme.colorScheme.background))
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = color, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        a.type.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    // A value ("52 mg/dL ↓") stays big; longer texts (signal loss, test) get a size that wraps cleanly.
                    val size = if (a.text.length <= 14) 44.sp else 28.sp
                    Text(
                        a.text, fontSize = size, lineHeight = size * 1.25f, fontWeight = FontWeight.Bold,
                        color = color, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(48.dp))
                    Button(
                        onClick = { engine.snooze(a.type); finish() },
                        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                    ) { Text("Posticipa ${a.snoozeMin} minuti", fontSize = 18.sp) }
                }
            }
        }
    }
}

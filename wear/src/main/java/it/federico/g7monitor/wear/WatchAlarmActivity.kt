package it.federico.g7monitor.wear

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch

/**
 * Full-screen alarm on the watch, like the phone's: on top of the watch face (also with the screen
 * off), vibrating until "Posticipa", for at most [VIBRATE_MS]. It closes by itself when the alarm
 * stops on the phone (snoozed there, or the glucose is back in range).
 */
class WatchAlarmActivity : ComponentActivity() {
    private val vibrator by lazy {
        if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    }
    private val stopVibration = Runnable { vibrator.cancel() }
    private val handler = Handler(Looper.getMainLooper())
    private var vibratingFor: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            WatchAlarm.current.collect { alarm ->
                if (alarm == null) {
                    finish()
                } else if (alarm.since != vibratingFor) {
                    // A new alarm, or the same one again at the next reading: vibrate again.
                    vibratingFor = alarm.since
                    vibrate()
                }
            }
        }
        setContent {
            MaterialTheme {
                val alarm by WatchAlarm.current.collectAsState()
                alarm?.let { a -> AlarmScreen(a) { WatchAlarm.snooze(this, a.type) } }
            }
        }
    }

    private fun vibrate() {
        handler.removeCallbacks(stopVibration)
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        handler.postDelayed(stopVibration, VIBRATE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopVibration)
        vibrator.cancel()
        super.onDestroy()
    }

    private companion object {
        /** As long as the phone rings. */
        const val VIBRATE_MS = 2 * 60_000L
    }
}

@Composable
private fun AlarmScreen(alarm: WatchAlarm.Alarm, onSnooze: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color(0xFF3A0D0F)).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            alarm.title, color = Color(0xFFFF6B6F), fontSize = 16.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 2,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            alarm.text, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 2,
        )
        Spacer(Modifier.height(12.dp))
        Chip(
            onClick = onSnooze,
            label = { Text(stringResource(R.string.w_snooze, alarm.snoozeMin), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFFFF6B6F), contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

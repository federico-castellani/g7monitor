package it.federico.g7monitor.wear

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Full-screen alarm page on the watch, like the phone's: large value and "Posticipa", vibrating like
 * an alarm clock for about a minute. Opened as the notification's full-screen page (see
 * [WatchAlarm]) or by tapping the notification; it closes by itself when the alarm stops on the
 * phone (snoozed there, or the glucose is back in range).
 */
class WatchAlarmActivity : ComponentActivity() {
    /** Closes an alarm the phone stopped repeating (see [WatchAlarm.EXPIRE_MS]). */
    private val expire = Runnable { if (WatchAlarm.current.value?.since == vibratingFor) WatchAlarm.clear(this) }
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
                    WatchAlarm.vibrate(this@WatchAlarmActivity)
                    handler.removeCallbacks(expire)
                    handler.postDelayed(expire, WatchAlarm.EXPIRE_MS)
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

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        WatchAlarm.stopVibration(this)
        super.onDestroy()
    }

    companion object {
        /** The page is on screen: a repeated alarm updates it instead of posting a new notification. */
        @Volatile
        var visible = false
            private set
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

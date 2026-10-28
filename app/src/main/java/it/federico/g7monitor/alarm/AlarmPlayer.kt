package it.federico.g7monitor.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Plays an alarm on the ALARM stream (not affected by silent mode or the ringer volume)
 * and vibrates, until [stop] or [maxMs]. One alarm at a time.
 */
class AlarmPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val stopLater = Runnable { stop() }

    val isPlaying get() = player != null

    fun start(config: AlarmConfig, maxMs: Long) = handler.post {
        stop()
        if (config.output != AlarmOutput.VibrationOnly) playSound(config)
        if (config.output != AlarmOutput.SoundOnly) vibrate(config.type)
        handler.postDelayed(stopLater, maxMs)
    }

    fun stop() {
        handler.removeCallbacks(stopLater)
        player?.runCatching { stop(); release() }
        player = null
        vibrator.cancel()
    }

    private fun playSound(config: AlarmConfig) {
        val builtIn = config.builtInSound
        val uri = if (builtIn != null) rawUri(builtIn) else Uri.parse(config.sound)
        // A system sound may have been deleted: fall back to the alarm's own sound, never silence.
        player = create(uri, config.volume) ?: create(rawUri(config.type.sound), config.volume)
    }

    private fun create(uri: Uri, volume: Int): MediaPlayer? = runCatching {
        MediaPlayer().apply {
            setAudioAttributes(ATTRIBUTES)
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setDataSource(context, uri)
            isLooping = true
            val v = volume / 100f
            setVolume(v, v)
            prepare()
            start()
        }
    }.getOrNull()

    private fun rawUri(sound: BuiltInSound) = Uri.parse("android.resource://${context.packageName}/${sound.res}")

    private fun vibrate(type: AlarmType) {
        // Urgent: long insistent pulses; normal: two short pulses; signal loss: one long pulse. Repeats from index 0.
        val pattern = when {
            type.urgent -> longArrayOf(0, 600, 200, 600, 200, 600, 900)
            type == AlarmType.SignalLoss -> longArrayOf(0, 900, 2000)
            else -> longArrayOf(0, 350, 250, 350, 1800)
        }
        val effect = VibrationEffect.createWaveform(pattern, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, ATTRIBUTES)
        }
    }

    companion object {
        private val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

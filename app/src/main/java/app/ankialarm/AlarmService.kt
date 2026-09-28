package app.ankialarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** Keeps the alarm sounding until AlarmActivity says the cards are done. */
class AlarmService : Service() {
    data class Ringing(val alarm: Alarm, val snoozeCount: Int, val test: Boolean)

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var startedAt = 0L
    private var gentleStart = false
    private val volumeTicker = object : Runnable {
        override fun run() {
            player?.let { p -> targetVolume().let { v -> runCatching { p.setVolume(v, v) } } }
            handler.postDelayed(this, VOLUME_TICK_MS)
        }
    }

    /**
     * Gentle start rises from quiet to full over the first 30 s. While you're answering it drops
     * to 30% so you can think; stop touching the screen for 30 s and it's back at full volume.
     */
    private fun targetVolume(): Float {
        val now = SystemClock.elapsedRealtime()
        val ramp = if (gentleStart) (MIN_VOLUME + (now - startedAt).toFloat() / RAMP_MS).coerceAtMost(1f) else 1f
        val answering = now - lastActivity.value < IDLE_BEFORE_LOUD_MS
        return if (answering) minOf(ramp, ANSWERING_VOLUME) else ramp
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarm = intent?.let { AlarmStore.get(this, it.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)) }
        // Must happen right away for a service started with startForegroundService.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(alarm),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        if (intent == null || alarm == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        _ringing.value = Ringing(
            alarm = alarm,
            snoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0),
            test = intent.getBooleanExtra(EXTRA_TEST, false),
        )
        stopRinging()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnkiAlarm:ringing")
            .apply { acquire(60 * 60 * 1000L) }
        startedAt = SystemClock.elapsedRealtime()
        gentleStart = alarm.gentleStart
        lastActivity.value = 0L
        startSound(alarm)
        handler.post(volumeTicker)
        startVibration()
        // Works when the phone is in use; otherwise the full-screen notification opens the cards.
        runCatching { startActivity(alarmActivityIntent(this)) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRinging()
        _ringing.value = null
        super.onDestroy()
    }

    private fun startSound(alarm: Alarm) {
        raiseAlarmVolumeIfMuted()
        // The alarm's own sound first; if it's gone or unplayable, fall back to the phone's sounds.
        val candidates = listOfNotNull(
            alarm.soundUri?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
        )
        for (uri in candidates) {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(Sounds.ALARM_AUDIO)
                p.setDataSource(this, uri)
                p.isLooping = true
                targetVolume().let { v -> p.setVolume(v, v) }
                p.prepare()
                p.start()
                player = p
                return
            } catch (e: Exception) {
                p.release()
            }
        }
    }

    /** An alarm you can't hear is no alarm: if the alarm volume is at zero, bring it up to 60%. */
    private fun raiseAlarmVolumeIfMuted() {
        runCatching {
            val audio = getSystemService(AudioManager::class.java)
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, (max * 0.6f).roundToInt().coerceAtLeast(1), 0)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (v == null || !v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 700), 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        vibrator = v
    }

    private fun stopRinging() {
        handler.removeCallbacks(volumeTicker)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(alarm: Alarm?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Ringing alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                    // The service plays the sound and vibration itself.
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setBypassDnd(true)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, alarmActivityIntent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(alarm?.label?.ifBlank { null } ?: "Wake up!")
            .setContentText("Review ${cardsText(alarm?.cardsToReview ?: 1)} to stop the alarm")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .build()
    }

    companion object {
        const val EXTRA_TEST = "test"
        private const val CHANNEL_ID = "ringing"
        private const val NOTIFICATION_ID = 42

        private const val VOLUME_TICK_MS = 250L
        private const val RAMP_MS = 30_000f
        private const val MIN_VOLUME = 0.05f
        private const val ANSWERING_VOLUME = 0.3f
        private const val IDLE_BEFORE_LOUD_MS = 30_000L

        private val _ringing = MutableStateFlow<Ringing?>(null)

        /** When the user last touched the alarm screen or shook the phone (elapsedRealtime). */
        private val lastActivity = MutableStateFlow(0L)

        /** Tell the ringing alarm the user is busy answering, so it can quieten down for a while. */
        fun noteActivity() {
            lastActivity.value = SystemClock.elapsedRealtime()
        }

        /** The alarm that is sounding right now, or null. */
        val ringing: StateFlow<Ringing?> = _ringing.asStateFlow()

        fun start(context: Context, alarmId: Int, snoozeCount: Int, test: Boolean) {
            val intent = Intent(context, AlarmService::class.java)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
                .putExtra(EXTRA_TEST, test)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AlarmService::class.java))
        }

        private fun alarmActivityIntent(c: Context) = Intent(c, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

package app.ankialarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.ZonedDateTime

object AlarmScheduler {
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_SNOOZE_COUNT = "snooze_count"
    private const val ACTION_FIRE = "app.ankialarm.FIRE"
    private const val SNOOZE_REQUEST_OFFSET = 1_000_000

    fun nextTrigger(alarm: Alarm, after: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime {
        var candidate = after.toLocalDate().atTime(alarm.hour, alarm.minute).atZone(after.zone)
        if (!candidate.isAfter(after)) candidate = candidate.plusDays(1)
        if (alarm.isRepeating) {
            while (candidate.dayOfWeek !in alarm.days) candidate = candidate.plusDays(1)
        }
        return candidate
    }

    fun schedule(c: Context, alarm: Alarm, after: ZonedDateTime = ZonedDateTime.now()) {
        if (!alarm.enabled) {
            cancel(c, alarm.id)
            return
        }
        val at = nextTrigger(alarm, after).toInstant().toEpochMilli()
        set(c, at, fireIntent(c, alarm.id, 0, alarm.id))
    }

    fun scheduleSnooze(c: Context, alarmId: Int, minutes: Int, snoozeCount: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        set(c, at, fireIntent(c, alarmId, snoozeCount, alarmId + SNOOZE_REQUEST_OFFSET))
    }

    fun cancel(c: Context, alarmId: Int) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(fireIntent(c, alarmId, 0, alarmId))
        am.cancel(fireIntent(c, alarmId, 0, alarmId + SNOOZE_REQUEST_OFFSET))
    }

    fun rescheduleAll(c: Context) = AlarmStore.all(c).forEach { schedule(c, it) }

    fun canScheduleExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun set(c: Context, at: Long, operation: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            // setAlarmClock is exact, wakes the phone from Doze, and shows the alarm icon in the status bar.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        } catch (e: SecurityException) {
            // Exact alarms were revoked in system settings; ring as close to the time as Android allows.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun fireIntent(c: Context, alarmId: Int, snoozeCount: Int, requestCode: Int): PendingIntent {
        val intent = Intent(c, AlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_ALARM_ID, alarmId)
            .putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
        return PendingIntent.getBroadcast(
            c, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

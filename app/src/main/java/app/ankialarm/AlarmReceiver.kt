package app.ankialarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.ZonedDateTime

/** Fired by AlarmManager at alarm time. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarm = AlarmStore.get(context, intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)) ?: return
        val snoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        if (snoozeCount == 0) {
            if (alarm.isRepeating) {
                // Look a minute ahead so an alarm delivered a hair early doesn't reschedule itself for today.
                AlarmScheduler.schedule(context, alarm, ZonedDateTime.now().plusMinutes(1))
            } else {
                AlarmStore.save(context, alarm.copy(enabled = false))
            }
        }
        AlarmService.start(context, alarm.id, snoozeCount, test = false)
    }
}

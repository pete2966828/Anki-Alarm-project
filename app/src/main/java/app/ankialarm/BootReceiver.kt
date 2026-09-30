package app.ankialarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android forgets scheduled alarms on reboot and on clock changes, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
        // Strict mode: an alarm that was still ringing when the phone was switched off (or the
        // app updated) rings again. Starting it via AlarmManager, since Android doesn't allow
        // starting the ringing service straight from a boot broadcast.
        val unfinished = AlarmStore.ringingAlarm(context)
        val restarted = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restarted && unfinished >= 0 && AlarmStore.strictMode(context)) {
            AlarmScheduler.scheduleRing(context, unfinished, System.currentTimeMillis() + 5_000, snoozeCount = 1)
        }
    }
}

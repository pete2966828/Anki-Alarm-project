package app.ankialarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android forgets scheduled alarms on reboot and on clock changes, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}

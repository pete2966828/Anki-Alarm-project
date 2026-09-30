package app.ankialarm

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

/**
 * Strict mode's enforcer, active only while an alarm rings: closes the power-off menu and
 * brings the alarm back if you switch away. Being an accessibility service is what lets it
 * see those windows and start the alarm screen from the background.
 * It reads no screen content and does nothing when no alarm is ringing.
 */
class StrictModeService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val ringing = AlarmService.ringing.value ?: return
        if (ringing.test || !AlarmStore.strictMode(this)) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in alwaysAllowed()) return
        if (pkg == SYSTEM_UI) {
            // The power-off menu and notification shade are System UI windows; Back closes them.
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ bringAlarmBack() }, 150)
    }

    private fun bringAlarmBack() {
        if (AlarmService.ringing.value == null) return
        runCatching {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    /** Calls (including emergency calls) and the keyboard must always keep working. */
    private fun alwaysAllowed(): Set<String> {
        val allowed = mutableSetOf(
            "com.android.phone", "com.android.server.telecom", "com.android.emergency",
            "com.android.incallui", "com.samsung.android.incallui", "com.google.android.dialer",
        )
        runCatching { getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let { allowed += it }
        runCatching {
            getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { allowed += it.packageName }
        }
        return allowed
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"

        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, StrictModeService::class.java)
            val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

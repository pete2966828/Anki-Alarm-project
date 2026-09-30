package app.ankialarm

import android.content.Context
import org.json.JSONArray

object AlarmStore {
    private const val PREFS = "anki_alarm"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_SYNC_AFTER = "sync_after_alarm"
    private const val KEY_WAKE_CODE = "wake_code"
    private const val KEY_STRICT = "strict_mode"
    private const val KEY_RINGING = "ringing_alarm"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context): List<Alarm> = runCatching {
        val array = JSONArray(prefs(c).getString(KEY_ALARMS, "[]"))
        (0 until array.length()).map { Alarm.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList()).sortedWith(compareBy({ it.hour }, { it.minute }, { it.id }))

    fun get(c: Context, id: Int): Alarm? = all(c).find { it.id == id }

    fun save(c: Context, alarm: Alarm) = write(c, all(c).filter { it.id != alarm.id } + alarm)

    fun delete(c: Context, id: Int) = write(c, all(c).filter { it.id != id })

    fun newId(c: Context): Int = (all(c).maxOfOrNull { it.id } ?: 0) + 1

    fun syncAfterAlarm(c: Context): Boolean = prefs(c).getBoolean(KEY_SYNC_AFTER, true)

    fun setSyncAfterAlarm(c: Context, value: Boolean) = prefs(c).edit().putBoolean(KEY_SYNC_AFTER, value).apply()

    /** The barcode/QR contents you scan to unlock the cards, or null if none is saved. */
    fun wakeCode(c: Context): String? = prefs(c).getString(KEY_WAKE_CODE, null)

    fun setWakeCode(c: Context, value: String?) = prefs(c).edit().putString(KEY_WAKE_CODE, value).apply()

    fun strictMode(c: Context): Boolean = prefs(c).getBoolean(KEY_STRICT, false)

    fun setStrictMode(c: Context, value: Boolean) = prefs(c).edit().putBoolean(KEY_STRICT, value).apply()

    /** The alarm that is ringing and not yet finished, so it can ring again after a restart. -1 if none. */
    fun ringingAlarm(c: Context): Int = prefs(c).getInt(KEY_RINGING, -1)

    fun setRingingAlarm(c: Context, id: Int) = prefs(c).edit().putInt(KEY_RINGING, id).commit()

    private fun write(c: Context, alarms: List<Alarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        // commit, not apply: the receiver that calls this may be killed right after.
        prefs(c).edit().putString(KEY_ALARMS, array.toString()).commit()
    }
}

package app.ankialarm

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    /** Empty means the alarm rings once and then turns itself off. */
    val days: Set<DayOfWeek> = emptySet(),
    val enabled: Boolean = true,
    val label: String = "",
    /** Null means "whatever deck is currently selected in AnkiDroid". */
    val deckId: Long? = null,
    val deckName: String? = null,
    val cardsToReview: Int = 3,
    /** 0 turns snooze off. */
    val snoozeMinutes: Int = 0,
    /** Null means the phone's default alarm sound. */
    val soundUri: String? = null,
    val soundName: String? = null,
) {
    val isRepeating: Boolean get() = days.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("days", JSONArray(days.map { it.value }))
        put("enabled", enabled)
        put("label", label)
        if (deckId != null) put("deckId", deckId)
        if (deckName != null) put("deckName", deckName)
        put("cardsToReview", cardsToReview)
        put("snoozeMinutes", snoozeMinutes)
        if (soundUri != null) put("soundUri", soundUri)
        if (soundName != null) put("soundName", soundName)
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val days = o.optJSONArray("days")
            return Alarm(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = if (days == null) emptySet() else (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                enabled = o.optBoolean("enabled", true),
                label = o.optString("label"),
                deckId = if (o.has("deckId")) o.getLong("deckId") else null,
                deckName = o.optString("deckName").ifEmpty { null },
                cardsToReview = o.optInt("cardsToReview", 3),
                snoozeMinutes = o.optInt("snoozeMinutes", 0),
                soundUri = o.optString("soundUri").ifEmpty { null },
                soundName = o.optString("soundName").ifEmpty { null },
            )
        }
    }
}

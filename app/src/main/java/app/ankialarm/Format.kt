package app.ankialarm

import android.content.Context
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val WEEK: List<DayOfWeek> = DayOfWeek.values().toList()
private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

fun formatTime(c: Context, hour: Int, minute: Int): String {
    val pattern = if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a"
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern))
}

fun describeDays(days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == WEEKDAYS -> "Weekdays"
    days == WEEKEND -> "Weekends"
    else -> WEEK.filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
}

fun cardsText(n: Int): String = if (n == 1) "1 card" else "$n cards"

fun formatUntil(time: ZonedDateTime): String {
    val minutes = ((Duration.between(ZonedDateTime.now(), time).seconds + 59) / 60).coerceAtLeast(1)
    val days = minutes / (24 * 60)
    val hours = minutes / 60 % 24
    val mins = minutes % 60
    return when {
        days > 0 -> "$days d $hours h"
        hours > 0 -> "$hours h $mins min"
        else -> "$mins min"
    }
}

package app.ankialarm

import android.content.Context
import android.text.format.DateFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

val WEEK: List<DayOfWeek> = DayOfWeek.values().toList()
private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

fun formatTime(c: Context, hour: Int, minute: Int): String {
    val pattern = if (DateFormat.is24HourFormat(c)) "HH:mm" else "h:mm a"
    return LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern(pattern, c.locale()))
}

fun describeDays(c: Context, days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> c.getString(R.string.days_once)
    days.size == 7 -> c.getString(R.string.days_every)
    days == WEEKDAYS -> c.getString(R.string.days_weekdays)
    days == WEEKEND -> c.getString(R.string.days_weekends)
    else -> WEEK.filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, c.locale()) }
}

fun cardsText(c: Context, n: Int): String = c.plural(R.plurals.cards_count, n)

fun formatUntil(c: Context, time: ZonedDateTime): String {
    val minutes = ((Duration.between(ZonedDateTime.now(), time).seconds + 59) / 60).coerceAtLeast(1)
    val days = (minutes / (24 * 60)).toInt()
    val hours = (minutes / 60 % 24).toInt()
    val mins = (minutes % 60).toInt()
    return when {
        days > 0 -> c.getString(R.string.until_days, days, hours)
        hours > 0 -> c.getString(R.string.until_hours, hours, mins)
        else -> c.getString(R.string.until_minutes, mins)
    }
}

package app.ankialarm

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's own language setting ("" follows the phone). Each activity and the alarm service
 * wrap their context with [wrap], so every string, date and day name uses the chosen language.
 */
object Lang {
    const val SYSTEM = ""
    val CHOICES = listOf(SYSTEM, "en", "th")

    private fun prefs(c: Context) = c.getSharedPreferences("anki_alarm", Context.MODE_PRIVATE)

    fun get(c: Context): String = prefs(c).getString("language", SYSTEM) ?: SYSTEM

    fun set(c: Context, tag: String) = prefs(c).edit().putString("language", tag).apply()

    fun wrap(base: Context): Context {
        val tag = get(base)
        if (tag == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return base.createConfigurationContext(config)
    }
}

/** The locale this context's resources are in (follows the app's language setting). */
fun Context.locale(): Locale = resources.configuration.locales[0]

fun Context.plural(id: Int, n: Int): String = resources.getQuantityString(id, n, n)

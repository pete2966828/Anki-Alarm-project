package app.ankialarm

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * Talks to AnkiDroid through its public content provider (the "AnkiDroid API").
 * Answers are written straight into your AnkiDroid collection, so they reach
 * AnkiWeb and desktop Anki the next time AnkiDroid syncs.
 */
object AnkiDroid {
    const val PACKAGE = "com.ichi2.anki"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private val SCHEDULE_URI: Uri = Uri.parse("content://$AUTHORITY/schedule")
    private val DECKS_URI: Uri = Uri.parse("content://$AUTHORITY/decks")

    data class Deck(val id: Long, val name: String)

    data class DueCard(
        val noteId: Long,
        val ord: Int,
        val buttonCount: Int,
        val nextReviewTimes: List<String>,
        val question: String,
        val answer: String,
    )

    @Suppress("DEPRECATION")
    fun isInstalled(c: Context): Boolean = c.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    fun hasPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun isAvailable(c: Context): Boolean = isInstalled(c) && hasPermission(c)

    fun decks(c: Context): List<Deck> =
        c.contentResolver.query(DECKS_URI, null, null, null, null)?.use { cur ->
            val id = cur.getColumnIndexOrThrow("deck_id")
            val name = cur.getColumnIndexOrThrow("deck_name")
            buildList { while (cur.moveToNext()) add(Deck(cur.getLong(id), cur.getString(name))) }
        }.orEmpty().sortedBy { it.name.lowercase() }

    /** The next card AnkiDroid would show you, or null when nothing is due. */
    /** A card is identified by its note and its position within the note. */
    data class CardKey(val noteId: Long, val ord: Int)

    /** [card] is null when nothing is due; [allSeen] says whether that's because every due card was in [skip]. */
    data class Next(val card: DueCard?, val allSeen: Boolean)

    /**
     * The next due card that isn't in [skip]. Anki brings a card back within minutes after
     * "Again" (or during its first learning steps), so without skipping, an alarm with few due
     * cards would show the same card over and over.
     */
    fun nextDueCard(c: Context, deckId: Long?, skip: Set<CardKey> = emptySet()): Next {
        val limit = (skip.size + LOOKAHEAD).toString()
        val selection = if (deckId == null) "limit=?" else "limit=?,deckID=?"
        val args = if (deckId == null) arrayOf(limit) else arrayOf(limit, deckId.toString())
        var anyDue = false
        val due = c.contentResolver.query(SCHEDULE_URI, null, selection, args, null)?.use { cur ->
            var found: DueCard? = null
            while (found == null && cur.moveToNext()) {
                anyDue = true
                val key = CardKey(cur.getLong(cur.getColumnIndexOrThrow("note_id")), cur.getInt(cur.getColumnIndexOrThrow("ord")))
                if (key in skip) continue
                found = DueCard(
                    noteId = key.noteId,
                    ord = key.ord,
                    buttonCount = cur.getInt(cur.getColumnIndexOrThrow("button_count")),
                    nextReviewTimes = parseTimes(cur.stringOrNull("next_review_times")),
                    question = "",
                    answer = "",
                )
            }
            found
        } ?: return Next(null, allSeen = anyDue)
        val (question, answer) = cardContent(c, due.noteId, due.ord) ?: return Next(null, allSeen = false)
        return Next(due.copy(question = question, answer = answer), allSeen = false)
    }

    private const val LOOKAHEAD = 25

    /** Records the review in AnkiDroid. ease: 1 = Again, 2 = Hard, 3 = Good, 4 = Easy (fewer with 2–3 buttons). */
    fun answer(c: Context, card: DueCard, ease: Int, timeTakenMs: Long) {
        val values = ContentValues().apply {
            put("note_id", card.noteId)
            put("ord", card.ord)
            put("answer_ease", ease)
            put("time_taken", timeTakenMs.coerceIn(0, 60_000))
        }
        c.contentResolver.update(SCHEDULE_URI, values, null, null)
    }

    /** Asks AnkiDroid to sync with AnkiWeb. AnkiDroid ignores this if it synced in the last few minutes. */
    fun requestSync(c: Context): Boolean = runCatching {
        c.startActivity(Intent("com.ichi2.anki.DO_SYNC").setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    fun openStore(c: Context) {
        runCatching {
            c.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            c.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun cardContent(c: Context, noteId: Long, ord: Int): Pair<String, String>? {
        val uri = Uri.parse("content://$AUTHORITY/notes/$noteId/cards/$ord")
        // The "simple" columns are the card without its note type's CSS, which reads better in our own layout.
        val simple = runCatching {
            c.contentResolver.query(uri, arrayOf("question_simple", "answer_simple", "answer_pure"), null, null, null)
                ?.use { cur ->
                    if (!cur.moveToFirst()) return@use null
                    val question = cur.stringOrNull("question_simple").orEmpty()
                    val pure = cur.stringOrNull("answer_pure")
                    val answer = if (!pure.isNullOrBlank()) {
                        "$question<hr id=answer>$pure"
                    } else {
                        cur.stringOrNull("answer_simple").orEmpty()
                    }
                    question to answer
                }
        }.getOrNull()
        if (simple != null) return simple
        // Older AnkiDroid versions only know the styled columns.
        return c.contentResolver.query(uri, arrayOf("question", "answer"), null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) null else cur.stringOrNull("question").orEmpty() to cur.stringOrNull("answer").orEmpty()
        }
    }

    private fun parseTimes(json: String?): List<String> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun Cursor.stringOrNull(column: String): String? {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) null else getString(i)
    }
}

package app.ankialarm

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Card pictures copied out of an Anki export. AnkiDroid doesn't let other apps read its media
 * folder, so the pictures have to come from a deck file the user exports and adds here.
 */
object CardMedia {
    fun dir(c: Context) = File(c.filesDir, "card_media").apply { mkdirs() }

    fun count(c: Context): Int = dir(c).list()?.size ?: 0

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }

    /** Base URL for card HTML, so `<img src="cat.jpg">` resolves to the saved picture. */
    fun baseUrl(c: Context): String = Uri.fromFile(dir(c)).toString() + "/"

    /** Reads an .apkg/.colpkg the user picked and saves its pictures. Returns how many were saved. */
    fun addFromPackage(c: Context, source: Uri): Int {
        // Zip reading needs random access, so copy the picked file to a temporary one first.
        val temp = File.createTempFile("import", ".apkg", c.cacheDir)
        try {
            val input = c.contentResolver.openInputStream(source) ?: return 0
            input.use { inp -> temp.outputStream().use { inp.copyTo(it) } }
            return AnkiPackageMedia.extractImages(temp, dir(c))
        } finally {
            temp.delete()
        }
    }
}

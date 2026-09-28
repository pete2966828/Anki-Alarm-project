package app.ankialarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException

/** Alarm sounds: the phone's own sounds, plus audio files the user adds (copied into app storage). */
object Sounds {
    data class Sound(val uri: String, val name: String)

    private const val MAX_BYTES = 30L * 1024 * 1024
    private const val SEPARATOR = "__"

    val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun dir(c: Context) = File(c.filesDir, "sounds").apply { mkdirs() }

    /** Files the user has added, oldest first. */
    fun imported(c: Context): List<Sound> =
        dir(c).listFiles().orEmpty().sortedBy { it.name }.map { Sound(Uri.fromFile(it).toString(), it.name.substringAfter(SEPARATOR)) }

    fun isImported(c: Context, uri: String?): Boolean =
        uri != null && Uri.parse(uri).let { it.scheme == "file" && File(it.path.orEmpty()).parentFile == dir(c) }

    /**
     * Copies a picked audio file into app storage, so the alarm keeps working
     * even if the original is moved or deleted. Null if it can't be played.
     */
    fun add(c: Context, source: Uri): Sound? {
        val name = displayName(c, source) ?: "Sound"
        val safe = name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").take(80)
        val file = File(dir(c), "${System.currentTimeMillis()}$SEPARATOR$safe")
        try {
            val input = c.contentResolver.openInputStream(source) ?: return null
            input.use { inp ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = inp.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("File too large")
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            file.delete()
            return null
        }
        val uri = Uri.fromFile(file)
        if (!isPlayable(c, uri)) {
            file.delete()
            return null
        }
        return Sound(uri.toString(), safe)
    }

    fun delete(c: Context, sound: Sound) {
        if (isImported(c, sound.uri)) File(Uri.parse(sound.uri).path.orEmpty()).delete()
    }

    fun ringtoneTitle(c: Context, uri: Uri): String? =
        runCatching { RingtoneManager.getRingtone(c, uri)?.getTitle(c) }.getOrNull()

    private fun isPlayable(c: Context, uri: Uri): Boolean {
        val p = MediaPlayer()
        return try {
            p.setDataSource(c, uri)
            p.prepare()
            true
        } catch (e: Exception) {
            false
        } finally {
            p.release()
        }
    }

    private fun displayName(c: Context, uri: Uri): String? = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    }.getOrNull()
}

/** Plays a sound on the alarm volume so you can hear it before saving. */
class SoundPreview(private val context: Context) {
    private var player: MediaPlayer? = null
    var playing by mutableStateOf(false)
        private set

    fun play(uri: String?) {
        stop()
        val target = uri?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(Sounds.ALARM_AUDIO)
            p.setDataSource(context, target)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            playing = true
        } catch (e: Exception) {
            p.release()
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        playing = false
    }
}

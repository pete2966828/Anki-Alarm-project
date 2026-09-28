package app.ankialarm

import com.github.luben.zstd.ZstdInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Pulls the pictures out of an Anki export (.apkg deck or .colpkg collection).
 *
 * Both formats are zip files holding a "media" index plus numbered files ("0", "1", ...).
 * Older exports store the index as JSON ({"0": "cat.jpg"}) and the files as-is. Newer ones
 * (Anki 2.1.50+, and AnkiDroid unless "Support older Anki versions" is ticked) store it as
 * zstd-compressed protobuf and compress every file with zstd too.
 */
object AnkiPackageMedia {
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif", "tif", "tiff", "ico")
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    /** Copies every picture from [pkg] into [target]. Returns how many were saved. */
    fun extractImages(pkg: File, target: File): Int {
        target.mkdirs()
        ZipFile(pkg).use { zip ->
            val index = zip.getEntry("media") ?: return 0
            val names = parseIndex(zip.getInputStream(index).use { it.readBytes() })
            var saved = 0
            for ((zipName, realName) in names) {
                // Keep only the file name, so an archive can't write outside the folder.
                val name = File(realName).name
                if (name.isBlank() || name == "." || name == "..") continue
                if (name.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) continue
                val entry = zip.getEntry(zipName) ?: continue
                zip.getInputStream(entry).use { raw ->
                    decompressIfNeeded(BufferedInputStream(raw)).use { input ->
                        File(target, name).outputStream().use { input.copyTo(it) }
                    }
                }
                saved++
            }
            return saved
        }
    }

    /** Maps the numbered file inside the zip to the real media file name. */
    internal fun parseIndex(bytes: ByteArray): Map<String, String> {
        if (startsWithZstd(bytes)) {
            val proto = ZstdInputStream(bytes.inputStream()).use { it.readBytes() }
            return parseMediaEntries(proto)
        }
        val text = bytes.toString(Charsets.UTF_8).trim()
        if (text.isEmpty()) return emptyMap()
        val json = JSONObject(text)
        return json.keys().asSequence().associateWith { json.getString(it) }
    }

    /**
     * Protobuf `MediaEntries { repeated MediaEntry entries = 1; }` where
     * `MediaEntry { string name = 1; uint32 size = 2; bytes sha1 = 3; optional uint32 legacy_zip_filename = 255; }`.
     * An entry's file in the zip is named after its position, unless legacy_zip_filename is set.
     */
    private fun parseMediaEntries(bytes: ByteArray): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val reader = ProtoReader(bytes, 0, bytes.size)
        var position = 0
        while (reader.hasMore()) {
            val (field, wireType) = reader.tag()
            if (field == 1 && wireType == 2) {
                val entry = reader.lengthDelimited()
                var name: String? = null
                var legacyName: Long? = null
                while (entry.hasMore()) {
                    val (f, w) = entry.tag()
                    when {
                        f == 1 && w == 2 -> name = entry.lengthDelimited().string()
                        f == 255 && w == 0 -> legacyName = entry.varint()
                        else -> entry.skip(w)
                    }
                }
                if (name != null) result[(legacyName ?: position.toLong()).toString()] = name
                position++
            } else {
                reader.skip(wireType)
            }
        }
        return result
    }

    private fun startsWithZstd(bytes: ByteArray) =
        bytes.size >= 4 && (0 until 4).all { bytes[it] == ZSTD_MAGIC[it] }

    private fun decompressIfNeeded(input: BufferedInputStream): InputStream {
        input.mark(4)
        val head = ByteArray(4)
        val read = input.readNBytesCompat(head)
        input.reset()
        return if (read == 4 && startsWithZstd(head)) ZstdInputStream(input) else input
    }

    private fun InputStream.readNBytesCompat(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private class ProtoReader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun tag(): Pair<Int, Int> {
            val key = varint()
            return (key ushr 3).toInt() to (key and 7).toInt()
        }

        fun lengthDelimited(): ProtoReader {
            val length = varint().toInt()
            val sub = ProtoReader(bytes, pos, pos + length)
            pos += length
            return sub
        }

        fun string() = String(bytes, pos, end - pos, Charsets.UTF_8)

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> lengthDelimited()
                5 -> pos += 4
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType")
            }
        }
    }
}

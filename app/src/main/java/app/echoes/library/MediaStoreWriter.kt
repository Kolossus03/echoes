package app.echoes.library

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * The one way Echoes adds music: through MediaStore, so no storage permission is needed and
 * the file shows up in the library by itself. Existing lists keep their folder; a new name
 * becomes Music/Echoes/<name>/.
 */
object MediaStoreWriter {
    fun targetFolder(lib: Library, target: String): String {
        val existing = lib.allFolders.firstOrNull { it.relPath == target || fold(it.name) == fold(target) }
        return existing?.relPath ?: "Music/Echoes/${safeName(target)}/"
    }

    fun exists(lib: Library, relPath: String, fileName: String): Boolean =
        lib.allTracks.any { it.folder == relPath && File(it.path).name.equals(fileName, ignoreCase = true) }

    fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)

    fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "mp3").lowercase()) {
        "m4a", "aac", "mp4" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    /** Writes the file and returns its absolute path. A failed write leaves nothing behind. */
    fun write(context: Context, relPath: String, fileName: String, body: (OutputStream) -> Unit): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(fileName))
            put(MediaStore.Audio.Media.RELATIVE_PATH, relPath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore rejected $fileName")
        try {
            resolver.openOutputStream(uri)!!.use(body)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            return resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: error("no path for $uri")
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}

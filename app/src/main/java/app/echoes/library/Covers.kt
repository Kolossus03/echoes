package app.echoes.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * Covers chosen by the user or taken from YouTube, stored as square JPEGs in app storage.
 * They win over embedded artwork and survive re-scans because they are keyed by path.
 */
class Covers(context: Context) {
    private val dir = File(context.filesDir, "covers").apply { mkdirs() }

    /** File name → last-modified; any change bumps art caches and recomposes art. */
    private val state = MutableStateFlow(dir.listFiles().orEmpty().associate { it.name to it.lastModified() })
    val versions: StateFlow<Map<String, Long>> = state.asStateFlow()

    fun version(key: String): Long? = state.value[name(key)]

    fun file(key: String): File? = File(dir, name(key)).takeIf { version(key) != null }

    /** `trimBars` removes YouTube letterboxing; a photo the user picked is only centre-cropped. */
    suspend fun set(key: String, image: Bitmap, trimBars: Boolean = false) = withContext(Dispatchers.IO) {
        val f = File(dir, name(key))
        f.outputStream().use { squareCrop(image, 1000, trimBars).compress(Bitmap.CompressFormat.JPEG, 90, it) }
        state.value = state.value + (f.name to f.lastModified())
    }

    suspend fun set(key: String, bytes: ByteArray) {
        val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } ?: error("not an image")
        set(key, bmp, trimBars = true)
    }

    fun remove(key: String) {
        File(dir, name(key)).delete()
        state.value = state.value - name(key)
    }

    private fun name(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"

    companion object {
        fun track(path: String) = "track:$path"
        fun folder(relPath: String) = "folder:$relPath"
        fun playlist(id: Long) = "playlist:$id"

        /** Trims letterbox bars (YouTube thumbnails) and centre-crops to a square. */
        fun squareCrop(src: Bitmap, maxPx: Int, trimBars: Boolean): Bitmap {
            val (l, t, r, b) = if (trimBars) contentBounds(src) else intArrayOf(0, 0, src.width, src.height)
            val w = r - l
            val h = b - t
            val side = min(w, h)
            val x = l + (w - side) / 2
            val y = t + (h - side) / 2
            val sq = Bitmap.createBitmap(src, x, y, side, side)
            return if (side > maxPx) Bitmap.createScaledBitmap(sq, maxPx, maxPx, true) else sq
        }

        private fun contentBounds(bmp: Bitmap): IntArray {
            val w = bmp.width
            val h = bmp.height
            fun dark(x: Int, y: Int): Boolean {
                val p = bmp.getPixel(x, y)
                return ((p shr 16) and 0xFF) < 24 && ((p shr 8) and 0xFF) < 24 && (p and 0xFF) < 24
            }
            fun rowDark(y: Int) = (0 until w step maxOf(1, w / 40)).all { dark(it, y) }
            fun colDark(x: Int) = (0 until h step maxOf(1, h / 40)).all { dark(x, it) }
            var top = 0
            while (top < h / 3 && rowDark(top)) top++
            var bottom = h
            while (bottom > h * 2 / 3 && rowDark(bottom - 1)) bottom--
            var left = 0
            while (left < w / 3 && colDark(left)) left++
            var right = w
            while (right > w * 2 / 3 && colDark(right - 1)) right--
            return intArrayOf(left, top, right, bottom)
        }
    }
}

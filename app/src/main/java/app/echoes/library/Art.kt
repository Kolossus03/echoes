package app.echoes.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Artwork for tracks and collections: a user/YouTube cover if there is one, else the embedded art. */
class ArtLoader(private val context: Context, private val covers: Covers) {
    private object None

    private val cache = object : LruCache<String, Any>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Any) = (value as? Bitmap)?.allocationByteCount ?: 64
    }
    private val gate = Semaphore(4)

    private fun trackKey(t: Track, px: Int) = "${t.uri}@$px#${covers.version(Covers.track(t.path)) ?: 0}"
    private fun coverKey(key: String, px: Int) = "$key@$px#${covers.version(key)}"

    fun cached(t: Track, px: Int): Bitmap? = cache.get(trackKey(t, px)) as? Bitmap

    suspend fun track(t: Track, px: Int): Bitmap? = memo(trackKey(t, px)) {
        covers.file(Covers.track(t.path))?.let { decode(it, px) }
            ?: runCatching { context.contentResolver.loadThumbnail(t.uri, Size(px, px), null) }.getOrNull()
    }

    /** Only the stored cover for a list or playlist; null means "draw the mosaic". */
    suspend fun cover(key: String, px: Int): Bitmap? {
        if (covers.version(key) == null) return null
        return memo(coverKey(key, px)) { covers.file(key)?.let { decode(it, px) } }
    }

    suspend fun jpeg(t: Track, px: Int): ByteArray? = track(t, px)?.let { bmp ->
        withContext(Dispatchers.Default) {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
        }
    }

    private suspend fun memo(key: String, load: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it as? Bitmap }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val bmp = load()
                cache.put(key, bmp ?: None)
                bmp
            }
        }
    }

    private fun decode(f: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        const val SMALL = 192
        const val LARGE = 900
    }
}

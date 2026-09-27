package app.echoes.library

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.echoes.data.EchoesDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MediaStore is the source of truth for files; Android keeps it current for us. */
class LibraryRepository(private val context: Context, dao: EchoesDao, private val scope: CoroutineScope) {
    private val raw = MutableStateFlow<List<Track>?>(null)
    private var pending: Job? = null
    private var observing = false

    val library: StateFlow<Library?> = combine(raw.filterNotNull(), dao.folderMeta(), dao.overrides()) { tracks, meta, ov ->
        Library(tracks, meta.associateBy { it.relPath }, ov.associateBy { it.path })
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun refresh() {
        if (!observing) {
            observing = true
            context.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = refreshSoon()
                },
            )
        }
        scope.launch { raw.value = query() }
    }

    private fun refreshSoon() {
        pending?.cancel()
        pending = scope.launch {
            delay(1500)
            raw.value = query()
        }
    }

    private suspend fun query(): List<Track> = withContext(Dispatchers.IO) {
        val cols = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
        )
        val out = ArrayList<Track>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 20000",
            null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(1) ?: continue
                val fileName = c.getString(7) ?: path.substringAfterLast('/')
                val (title, artist) = cleanTags(c.getString(2) ?: "", c.getString(3), fileName)
                out += Track(
                    id = id,
                    path = path,
                    uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    title = title,
                    artist = artist,
                    album = c.getString(4)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Sin álbum",
                    durationMs = c.getLong(5),
                    folder = c.getString(6) ?: path.substringBeforeLast('/') + "/",
                    dateAdded = c.getLong(8) * 1000,
                    size = c.getLong(9),
                    modified = c.getLong(10),
                    trackNo = c.getInt(11) % 1000,
                    year = c.getInt(12),
                )
            }
        }
        out
    }
}

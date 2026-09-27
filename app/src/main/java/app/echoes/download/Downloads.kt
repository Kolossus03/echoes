package app.echoes.download

import android.content.Context
import android.util.Log
import app.echoes.Graph
import app.echoes.data.Source
import app.echoes.data.TagOverride
import app.echoes.library.Covers
import app.echoes.library.Library
import app.echoes.library.MediaStoreWriter
import app.echoes.library.fold
import app.echoes.library.primaryArtist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

sealed interface DlState {
    data object Waiting : DlState
    data class Working(val fraction: Float?) : DlState
    data object Done : DlState
    data class Skipped(val why: String) : DlState
    data class Failed(val why: String) : DlState
}

data class DlJob(val id: Long, val item: Wanted, val folder: String, val state: DlState) {
    val finished get() = state is DlState.Done || state is DlState.Skipped || state is DlState.Failed
}

/** The download queue shared by the app (share sheet, Descargar screen) and the Portal. */
class Downloads(private val context: Context, private val scope: CoroutineScope) {
    private val state = MutableStateFlow<List<DlJob>>(emptyList())
    val jobs: StateFlow<List<DlJob>> = state.asStateFlow()
    private val ids = AtomicLong()
    private var worker: Job? = null

    suspend fun importCsv(fileName: String, text: String): Resolved = withContext(Dispatchers.Default) { CsvImport.parse(fileName, text) }

    /** Resolves a link (song, playlist or album) without downloading; used for the preview. */
    suspend fun resolve(text: String): Resolved = withContext(Dispatchers.IO) { Links.resolve(text) }

    fun enqueue(items: List<Wanted>, folder: String) {
        state.update { it + items.map { w -> DlJob(ids.incrementAndGet(), w, folder, DlState.Waiting) } }
        DownloadService.start(context)
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /** Remembers a playlist so a later sync fetches only what was added since. */
    fun link(r: Resolved, folder: String) {
        if (r.origin == Origin.FILE) return
        val name = r.playlistTitle ?: return
        val relPath = Graph.library.library.value?.let { MediaStoreWriter.targetFolder(it, folder) } ?: folder
        val entry = LinkedPlaylist(r.link, name, relPath, r.origin.name, System.currentTimeMillis())
        Graph.prefs.linked = Graph.prefs.linked.filterNot { it.link == r.link } + entry
    }

    fun unlink(p: LinkedPlaylist) {
        Graph.prefs.linked = Graph.prefs.linked.filterNot { it.link == p.link }
    }

    suspend fun sync(p: LinkedPlaylist): Int {
        val r = resolve(p.link)
        enqueue(r.items, p.folder)
        Graph.prefs.linked = Graph.prefs.linked.map { if (it.link == p.link) it.copy(lastSync = System.currentTimeMillis(), name = r.playlistTitle ?: it.name) else it }
        return r.items.size
    }

    /** Drops everything not started yet; the song downloading right now finishes. */
    fun cancelPending() = state.update { list -> list.filterNot { it.state == DlState.Waiting } }

    fun clearFinished() = state.update { list -> list.filterNot { it.finished } }

    private fun set(id: Long, s: DlState) = state.update { list -> list.map { if (it.id == id) it.copy(state = s) else it } }

    private suspend fun drain() {
        while (true) {
            val job = state.value.firstOrNull { it.state == DlState.Waiting } ?: break
            set(job.id, DlState.Working(null))
            val result = runCatching { withContext(Dispatchers.IO) { download(job) } }
            set(job.id, result.getOrElse { e ->
                Log.w("Echoes", "download failed: ${job.item}", e)
                DlState.Failed(friendly(e))
            })
        }
    }

    /** A song counts as already there if the list has one with the same title and main artist. */
    private fun alreadyInFolder(lib: Library, relPath: String, title: String, artist: String): Boolean {
        val t = fold(title.substringBefore(" - ").substringBefore(" (").trim())
        val a = fold(primaryArtist(artist))
        return lib.allTracks.any { it.folder == relPath && fold(it.title.substringBefore(" - ").substringBefore(" (").trim()) == t && fold(primaryArtist(it.artist)) == a }
    }

    private suspend fun download(job: DlJob): DlState {
        val lib = Graph.library.library.filterNotNull().first()
        val item = job.item
        Graph.dao.source(item.key)?.let { src ->
            lib.byPath[src.path]?.let { return DlState.Skipped("Ya la tienes en ${it.folder.trimEnd('/').substringAfterLast('/')}") }
        }
        val relPath = MediaStoreWriter.targetFolder(lib, job.folder)
        if (alreadyInFolder(lib, relPath, item.title, item.artist)) return DlState.Skipped("Ya está en esa lista")

        val video = when (item) {
            is Wanted.Video -> item.ref
            is Wanted.SpotifyTrack -> YouTube.match(item.title, item.artist, item.durationSec)
                ?: return DlState.Failed("No la encuentro en YouTube")
        }
        val src = YouTube.audio(video, lookupCover = item is Wanted.Video)
        val (artist, title, cover) = when (item) {
            is Wanted.Video -> Triple(src.artist, src.title, src.coverUrl)
            is Wanted.SpotifyTrack -> Triple(item.artist, item.title, item.thumb ?: item.id?.let(Spotify::cover) ?: src.coverUrl)
        }
        val fileName = MediaStoreWriter.safeName("$artist - $title") + "." + src.extension
        if (MediaStoreWriter.exists(lib, relPath, fileName)) return DlState.Skipped("Ya está en esa lista")

        val raw = File(context.cacheDir, "dl-${job.id}.${src.extension}")
        val plain = File(context.cacheDir, "dl-${job.id}-plain.${src.extension}")
        val path = try {
            raw.outputStream().use { out ->
                YouTube.fetch(src, out) { done -> set(job.id, DlState.Working(src.contentLength?.let { (done.toFloat() / it).coerceIn(0f, 1f) })) }
            }
            val file = if (src.extension == "m4a") plain.also { Remux.toMp4(raw, it) } else raw
            MediaStoreWriter.write(context, relPath, fileName) { out -> file.inputStream().use { it.copyTo(out) } }
        } finally {
            raw.delete()
            plain.delete()
        }
        Graph.dao.upsertOverride(TagOverride(path, title, artist, null))
        cover?.let { url -> runCatching { Graph.covers.set(Covers.track(path), YouTube.bytes(url)) } }
        val now = System.currentTimeMillis()
        Graph.dao.upsertSource(Source(item.key, path, now))
        if (item.key != video.id) Graph.dao.upsertSource(Source(video.id, path, now))
        return DlState.Done
    }

    private fun friendly(e: Throwable): String = when {
        e is IllegalArgumentException -> e.message ?: "Enlace no válido"
        e.javaClass.simpleName.contains("AgeRestricted") -> "Vídeo con restricción de edad"
        e.javaClass.simpleName.contains("GeographicRestriction") -> "No disponible en tu país"
        e.javaClass.simpleName.contains("ContentNotAvailable") || e.javaClass.simpleName.contains("Private") -> "Vídeo no disponible"
        e.javaClass.simpleName.contains("ReCaptcha") -> "YouTube pide captcha; prueba más tarde"
        e is java.io.IOException -> "Sin conexión o YouTube no responde"
        else -> e.message?.take(120) ?: "Error desconocido"
    }
}

package app.echoes.portal

import android.content.Context
import android.util.Log
import app.echoes.Graph
import app.echoes.data.TagOverride
import app.echoes.download.DlState
import app.echoes.download.Resolved
import app.echoes.flow.Insights
import app.echoes.library.ArtLoader
import app.echoes.library.Library
import app.echoes.library.Track
import app.echoes.library.MediaStoreWriter
import app.echoes.playback.PlayerConnection
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class TrackDto(
    val id: Long, val title: String, val artist: String, val album: String, val folder: String, val folderName: String,
    val durationMs: Long, val addedAt: Long, val fav: Boolean, val plays: Int, val bpm: Float?, val energy: Float?, val lufs: Float?,
)

@Serializable data class FolderDto(val relPath: String, val name: String, val count: Int, val durationMs: Long)
@Serializable data class LibraryDto(val tracks: List<TrackDto>, val folders: List<FolderDto>)
@Serializable data class StateDto(
    val current: TrackDto?, val isPlaying: Boolean, val positionMs: Long, val durationMs: Long,
    val queue: List<Long>, val index: Int, val flow: Boolean, val shuffle: Boolean,
)
@Serializable data class ControlReq(val action: String, val value: Long = 0)
@Serializable data class PlayReq(val ids: List<Long>, val start: Int? = null, val shuffle: Boolean = false)
@Serializable data class IdReq(val id: Long, val mode: String = "")
@Serializable data class TagReq(val id: Long, val title: String?, val artist: String?, val album: String?)
@Serializable data class UploadResult(val saved: List<String>, val skipped: List<String>, val failed: List<String>)
@Serializable data class CsvReq(val name: String, val csv: String, val folder: String = "", val go: Boolean = false)
@Serializable data class LinkReq(val url: String, val folder: String = "", val link: Boolean = true)
@Serializable data class VideoDto(val title: String, val uploader: String, val durationSec: Long, val thumb: String?)
@Serializable data class ResolveDto(val playlist: String?, val origin: String, val truncated: Boolean, val videos: List<VideoDto>)
@Serializable data class JobDto(val id: Long, val title: String, val folder: String, val state: String, val progress: Float?, val message: String?)
@Serializable data class RankDto(val label: String, val sub: String, val minutes: Long, val plays: Int)
@Serializable data class InsightsDto(
    val minutes: Long, val plays: Int, val streak: Int, val daily: List<Long>, val hours: List<Long>,
    val topTracks: List<RankDto>, val topArtists: List<RankDto>, val avgBpm: Float?, val avgEnergy: Float?,
)

class PortalServer(private val context: Context, private val player: PlayerConnection) {
    private var server: EmbeddedServer<*, *>? = null

    fun start(port: Int) {
        if (server != null) return
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") { module() }.also { it.start(wait = false) }
    }

    fun stop() {
        server?.stop(200, 1000)
        server = null
    }

    private suspend fun lib(): Library = withTimeout(15_000) { Graph.library.library.filterNotNull().first() }

    private fun dto(t: Track): TrackDto {
        val a = Graph.analysis.current(t)
        return TrackDto(
            t.id, t.title, t.artist, t.album, t.folder, t.folder.trimEnd('/').substringAfterLast('/'), t.durationMs, t.dateAdded,
            t.path in Graph.favorites.value, Graph.taste.value.plays(t.path), a?.bpm, a?.energy, a?.loudnessLufs,
        )
    }

    private val auth = createRouteScopedPlugin("EchoesAuth") {
        onCall { call ->
            val key = call.request.headers["X-Echoes-Key"] ?: call.request.queryParameters["k"]
            if (key != Graph.prefs.portalToken) call.respond(HttpStatusCode.Unauthorized, "clave incorrecta")
        }
    }

    private fun io.ktor.server.application.Application.module() {
        install(ContentNegotiation) { json() }
        install(PartialContent)
        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                call.respondText(cause.message ?: "petición no válida", status = HttpStatusCode.BadRequest)
            }
            exception<Throwable> { call, cause ->
                Log.w("Echoes", "portal error", cause)
                call.respondText(cause.message ?: "error", status = HttpStatusCode.InternalServerError)
            }
        }
        routing {
            get("/") { asset(call, "portal/index.html", ContentType.Text.Html) }
            get("/space_grotesk.ttf") { asset(call, "portal/space_grotesk.ttf", ContentType("font", "ttf")) }

            route("/api") {
                install(auth)

                get("/library") {
                    val l = lib()
                    call.respond(LibraryDto(l.tracks.map(::dto), l.folders.map { FolderDto(it.relPath, it.name, it.tracks.size, it.durationMs) }))
                }

                get("/state") {
                    val s = withContext(Dispatchers.Main) {
                        val ui = player.ui.value
                        val l = Graph.library.library.value
                        StateDto(
                            current = ui.currentId?.let { l?.byId?.get(it) }?.let(::dto),
                            isPlaying = ui.isPlaying, positionMs = player.positionMs, durationMs = ui.durationMs,
                            queue = ui.queue, index = ui.index, flow = Graph.flowMode.value, shuffle = Graph.prefs.shuffle.value,
                        )
                    }
                    call.respond(s)
                }

                post("/control") {
                    val req = call.receive<ControlReq>()
                    withContext(Dispatchers.Main) {
                        when (req.action) {
                            "toggle" -> player.toggle()
                            "next" -> player.next()
                            "prev" -> player.previous()
                            "seek" -> player.seekTo(req.value)
                            "jump" -> player.jumpTo(req.value.toInt())
                            "remove" -> player.remove(req.value.toInt())
                            "shuffle" -> player.toggleShuffle()
                        }
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/play") {
                    val req = call.receive<PlayReq>()
                    val l = lib()
                    val tracks = req.ids.mapNotNull { l.byId[it] }
                    withContext(Dispatchers.Main) {
                        if (req.shuffle) Graph.prefs.shuffle.set(true)
                        player.play(tracks, req.start)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/flow") {
                    val t = lib().byId[call.receive<IdReq>().id] ?: return@post call.respond(HttpStatusCode.NotFound)
                    withContext(Dispatchers.Main) { player.startFlow(t) }
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/queue") {
                    val req = call.receive<IdReq>()
                    val t = lib().byId[req.id] ?: return@post call.respond(HttpStatusCode.NotFound)
                    withContext(Dispatchers.Main) { if (req.mode == "next") player.playNext(t) else player.enqueue(t) }
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/fav") {
                    val t = lib().byId[call.receive<IdReq>().id] ?: return@post call.respond(HttpStatusCode.NotFound)
                    Graph.toggleFavorite(t.path).join()
                    call.respond(HttpStatusCode.NoContent)
                }

                post("/tags") {
                    val req = call.receive<TagReq>()
                    val t = lib().allTracks.firstOrNull { it.id == req.id } ?: return@post call.respond(HttpStatusCode.NotFound)
                    Graph.dao.upsertOverride(TagOverride(t.path, req.title?.ifBlank { null }, req.artist?.ifBlank { null }, req.album?.ifBlank { null }))
                    call.respond(HttpStatusCode.NoContent)
                }

                get("/art/{id}") {
                    val t = lib().byId[call.parameters["id"]!!.toLong()] ?: return@get call.respond(HttpStatusCode.NotFound)
                    val size = if (call.request.queryParameters["big"] != null) ArtLoader.LARGE else ArtLoader.SMALL
                    val bytes = Graph.art.jpeg(t, size) ?: return@get call.respond(HttpStatusCode.NotFound)
                    call.response.header(HttpHeaders.CacheControl, "max-age=86400")
                    call.respondBytes(bytes, ContentType.Image.JPEG)
                }

                get("/audio/{id}") {
                    val t = lib().byId[call.parameters["id"]!!.toLong()] ?: return@get call.respond(HttpStatusCode.NotFound)
                    if (call.request.queryParameters["download"] != null) {
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, File(t.path).name).toString(),
                        )
                    }
                    call.respond(LocalFileContent(File(t.path)))
                }

                get("/insights") {
                    val l = lib()
                    val plays = Graph.dao.recentPlays(50_000).first()
                    val i = Insights(plays, l, Graph.analysis.byPath.value)
                    call.respond(
                        InsightsDto(
                            i.totalMs / 60_000, i.playCount, i.streak, i.daily.map { it.second / 60_000 }, i.hours.map { it / 60_000 },
                            i.topTracks.map { RankDto(it.item.title, it.item.artist, it.ms / 60_000, it.plays) },
                            i.topArtists.map { RankDto(it.item, "", it.ms / 60_000, it.plays) },
                            i.avgBpm, i.avgEnergy,
                        ),
                    )
                }

                post("/resolve") {
                    call.respond(resolveDto(Graph.downloads.resolve(call.receive<LinkReq>().url)))
                }

                post("/download") {
                    val req = call.receive<LinkReq>()
                    val r = Graph.downloads.resolve(req.url)
                    val folder = req.folder.ifBlank { r.playlistTitle ?: r.origin.label }
                    Graph.downloads.enqueue(r.items, folder)
                    if (req.link) Graph.downloads.link(r, folder)
                    call.respond(resolveDto(r))
                }

                post("/csv") {
                    val req = call.receive<CsvReq>()
                    val r = Graph.downloads.importCsv(req.name, req.csv)
                    if (req.go) Graph.downloads.enqueue(r.items, req.folder.ifBlank { r.playlistTitle ?: "Importada" })
                    call.respond(resolveDto(r))
                }

                get("/downloads") {
                    call.respond(Graph.downloads.jobs.value.map { j ->
                        val (state, progress, msg) = when (val s = j.state) {
                            DlState.Waiting -> Triple("waiting", null, null)
                            is DlState.Working -> Triple("working", s.fraction, null)
                            DlState.Done -> Triple("done", 1f, null)
                            is DlState.Skipped -> Triple("skipped", null, s.why)
                            is DlState.Failed -> Triple("failed", null, s.why)
                        }
                        JobDto(j.id, "${j.item.title} · ${j.item.artist}", j.folder, state, progress, msg)
                    })
                }

                post("/upload") {
                    val target = call.request.queryParameters["folder"]?.trim().orEmpty().ifBlank { "Portal" }
                    val saved = mutableListOf<String>(); val skipped = mutableListOf<String>(); val failed = mutableListOf<String>()
                    call.receiveMultipart(formFieldLimit = 2L * 1024 * 1024 * 1024).forEachPart { part ->
                        if (part is PartData.FileItem) {
                            val name = part.originalFileName?.substringAfterLast('/')?.substringAfterLast('\\') ?: "cancion.mp3"
                            val result = runCatching { withContext(Dispatchers.IO) { save(target, name, part) } }
                            when (result.getOrNull()) {
                                true -> saved += name
                                false -> skipped += name
                                null -> { failed += name; Log.w("Echoes", "upload failed", result.exceptionOrNull()) }
                            }
                        }
                        part.dispose()
                    }
                    call.respond(UploadResult(saved, skipped, failed))
                }
            }
        }
    }

    private fun resolveDto(r: Resolved) =
        ResolveDto(r.playlistTitle, r.origin.label, r.truncated, r.items.map { VideoDto(it.title, it.artist, it.durationSec, it.thumb) })

    private suspend fun asset(call: ApplicationCall, path: String, type: ContentType) {
        val bytes = withContext(Dispatchers.IO) { context.assets.open(path).use { it.readBytes() } }
        call.respondBytes(bytes, type)
    }

    private suspend fun save(target: String, fileName: String, part: PartData.FileItem): Boolean {
        val l = lib()
        val relPath = MediaStoreWriter.targetFolder(l, target)
        if (MediaStoreWriter.exists(l, relPath, fileName)) return false
        MediaStoreWriter.write(context, relPath, fileName) { out -> part.provider().toInputStream().use { it.copyTo(out, 256 * 1024) } }
        return true
    }
}

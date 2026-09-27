package app.echoes.library

import android.net.Uri
import app.echoes.data.FolderMeta
import app.echoes.data.TagOverride
import java.text.Normalizer

data class Track(
    val id: Long,
    val path: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val folder: String,
    val dateAdded: Long,
    val size: Long,
    val modified: Long,
    val trackNo: Int,
    val year: Int,
) {
    val fingerprint get() = "a3:$size:$modified"
    val mediaId get() = id.toString()
    val searchKey: String by lazy { fold("$title $artist $album ${folder.trimEnd('/').substringAfterLast('/')}") }
}

data class Folder(val relPath: String, val name: String, val tracks: List<Track>, val hidden: Boolean) {
    val durationMs get() = tracks.sumOf { it.durationMs }
}

data class Album(val key: String, val title: String, val artist: String, val tracks: List<Track>)

data class Artist(val name: String, val tracks: List<Track>) {
    val albums get() = tracks.map { it.album }.distinct().size
}

class Library(rawTracks: List<Track>, meta: Map<String, FolderMeta>, overrides: Map<String, TagOverride>) {
    val allTracks: List<Track> = rawTracks.map { t ->
        overrides[t.path]?.let { o ->
            t.copy(title = o.title ?: t.title, artist = o.artist ?: t.artist, album = o.album ?: t.album)
        } ?: t
    }

    val allFolders: List<Folder> = allTracks.groupBy { it.folder }.map { (rel, tracks) ->
        val m = meta[rel]
        Folder(
            relPath = rel,
            name = m?.name ?: rel.trimEnd('/').substringAfterLast('/').ifBlank { "Música" },
            tracks = tracks.sortedWith(compareBy<Track> { it.trackNo.takeIf { n -> n > 0 } ?: Int.MAX_VALUE }.thenBy { it.dateAdded }),
            hidden = m?.hidden ?: isNoiseFolder(rel),
        )
    }.sortedBy { fold(it.name) }

    val folders = allFolders.filterNot { it.hidden }
    private val hiddenFolders = allFolders.filter { it.hidden }.map { it.relPath }.toSet()

    /** Tracks from visible folders only; this is what every screen and mix works from. */
    val tracks: List<Track> = allTracks.filterNot { it.folder in hiddenFolders }

    val byId: Map<Long, Track> = allTracks.associateBy { it.id }
    val byPath: Map<String, Track> = allTracks.associateBy { it.path }

    val albums: List<Album> = tracks.groupBy { fold(it.album) + "|" + fold(primaryArtist(it.artist)) }
        .map { (key, t) -> Album(key, t.first().album, primaryArtist(t.first().artist), t.sortedBy { it.trackNo }) }
        .sortedBy { fold(it.title) }

    val artists: List<Artist> = tracks.groupBy { primaryArtist(it.artist) }
        .map { (name, t) -> Artist(name, t.sortedBy { fold(it.title) }) }
        .sortedWith(compareByDescending<Artist> { it.tracks.size }.thenBy { fold(it.name) })

    fun search(query: String): List<Track> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return tracks.filter { t -> words.all { it in t.searchKey } }
    }

    companion object {
        private val noise = listOf("whatsapp", "recordings", "ringtones", "notifications", "alarms", "telegram", "call", "voice")
        fun isNoiseFolder(rel: String): Boolean = noise.any { it in rel.lowercase() }
    }
}

fun fold(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

fun primaryArtist(artist: String): String =
    artist.split(Regex("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+|;|/|,\\s")).first().trim().ifBlank { artist }

private val junk = Regex(
    """(?i)\s*[(\[](official[^)\]]*|lyrics?(\s*video)?|audio|video\s*oficial|videoclip\s*oficial|hd|hq|4k|4k remaster(ed)?|visualizer)[)\]]""",
)

private val dashes = Regex("""\s+[-–—]\s+""")

const val UNKNOWN_ARTIST = "Artista desconocido"

/**
 * Rescues rips whose tags carry "Artist - Title (Official Video)" in the title and no artist.
 * Hyphens, en dashes and em dashes all count as the separator.
 */
fun cleanTags(rawTitle: String, rawArtist: String?, fileName: String): Pair<String, String> {
    var title = rawTitle.ifBlank { fileName.substringBeforeLast('.') }
    var artist = rawArtist?.takeUnless { it.isBlank() || it == "<unknown>" }
    title = junk.replace(title, "").trim()
    val dash = dashes.find(title)
    if (dash != null) {
        val before = title.substring(0, dash.range.first).trim()
        if (artist == null || before.equals(artist, ignoreCase = true)) {
            artist = before
            title = title.substring(dash.range.last + 1).trim()
        }
    }
    return title to (artist ?: UNKNOWN_ARTIST)
}

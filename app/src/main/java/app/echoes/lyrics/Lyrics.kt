package app.echoes.lyrics

import android.content.Context
import android.util.Log
import app.echoes.library.Track
import app.echoes.library.primaryArtist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.abs

data class Line(val ms: Long, val text: String)

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data object Offline : LyricsState
    data class Plain(val lines: List<String>) : LyricsState
    data class Synced(val lines: List<Line>) : LyricsState
}

/**
 * Lyrics from LRCLIB (free, no account), time-synced when it has them. Each answer, including
 * "there are none", is cached on disk so a song asks the network once.
 */
class Lyrics(context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(track: Track): LyricsState = withContext(Dispatchers.IO) {
        val file = File(dir, key(track))
        if (file.exists()) return@withContext parse(file.readText())
        val found = runCatching { fetch(track) }.getOrElse {
            Log.i("Echoes", "lyrics lookup failed: ${it.message}")
            return@withContext LyricsState.Offline
        }
        file.writeText(found)
        parse(found)
    }

    /** "synced\n<lrc>", "plain\n<text>" or "none": the cache format. */
    private fun fetch(track: Track): String {
        val artist = primaryArtist(track.artist)
        val title = track.title
        val seconds = track.durationMs / 1000
        val exact = get(
            "get?track_name=${enc(title)}&artist_name=${enc(artist)}&album_name=${enc(track.album)}&duration=$seconds",
        )?.let { json.parseToJsonElement(it).jsonObject }
        exact?.takeIf { it.has("syncedLyrics") || it.has("plainLyrics") }?.let { return entry(it, synced = true) }
        // Titles from YouTube often carry the artist ("Queen - Song (Official Video)") and a channel as artist.
        val results = search("track_name=${enc(bare(title))}&artist_name=${enc(artist)}").ifEmpty { search("q=${enc(bare(title))}") }
        val near = results.filter { r -> r.seconds()?.let { abs(it - seconds) <= 8 } ?: false }
            .minByOrNull { r -> (if (r.has("syncedLyrics")) 0 else 1000) + abs(r.seconds()!! - seconds) }
        if (near != null) return entry(near, synced = true)
        // Another version of the song: its words are right but its timing is not, so show them unsynced.
        return results.firstOrNull()?.let { entry(it, synced = false) } ?: "none"
    }

    private fun entry(r: JsonObject, synced: Boolean): String {
        if (synced) r.string("syncedLyrics")?.let { return "synced\n$it" }
        r.string("plainLyrics")?.let { return "plain\n$it" }
        r.string("syncedLyrics")?.let { lrc -> return "plain\n" + lrc.lines().joinToString("\n") { it.replace(timeTag, "").trim() } }
        return "none"
    }

    private fun search(query: String): List<JsonObject> {
        val body = get("search?$query") ?: return emptyList()
        return (json.parseToJsonElement(body) as JsonArray).map { it.jsonObject }
            .filter { (it.string("syncedLyrics") ?: it.string("plainLyrics")) != null }
    }

    private fun JsonObject.seconds() = this["duration"]?.jsonPrimitive?.doubleOrNull

    private fun get(path: String): String? {
        val c = URL("https://lrclib.net/api/$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Echoes (https://github.com/Kolossus03/echoes)")
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
            null
        } finally {
            c.disconnect()
        }
    }

    private fun parse(cached: String): LyricsState {
        val kind = cached.substringBefore('\n')
        val body = cached.substringAfter('\n', "")
        return when (kind) {
            "synced" -> LyricsState.Synced(
                body.lines().mapNotNull { line ->
                    val m = lrcLine.matchEntire(line.trim()) ?: return@mapNotNull null
                    val (min, sec, frac, text) = m.destructured
                    val ms = min.toLong() * 60_000 + sec.toLong() * 1000 + frac.padEnd(3, '0').take(3).toLong()
                    Line(ms, text.trim())
                },
            )
            "plain" -> LyricsState.Plain(body.lines())
            else -> LyricsState.None
        }
    }

    private fun JsonObject.has(k: String) = string(k) != null
    private fun JsonObject.string(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** "Song (Cover) ft. X" → "Song": what lyric sites file it under. */
    private fun bare(title: String) = title.replace(junk, "").trim()

    private fun key(t: Track): String =
        MessageDigest.getInstance("SHA-1").digest("v2|${t.artist}|${t.title}|${t.durationMs / 1000}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val timeTag = Regex("""\[\d+:\d{2}(?:[.:]\d{1,3})?]""")
        val lrcLine = Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)""")
        val junk = Regex("""\s*(\([^)]*\)|\[[^]]*]|\b(?:ft|feat)\.?\s.*$)""", RegexOption.IGNORE_CASE)
    }
}

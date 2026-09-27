package app.echoes.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads public Spotify playlists, albums and tracks from their embed pages, which need no
 * account or API key. Spotify only serves the first 100 tracks of a playlist this way.
 * Spotify has no downloadable audio; each track is matched on YouTube Music later.
 */
object Spotify {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
    private val linkPattern = Regex("""https?://open\.spotify\.com/(?:intl-[a-z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)""")
    private val nextData = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
    const val EMBED_LIMIT = 100

    fun findLink(text: String): String? = linkPattern.find(text)?.value

    fun resolve(link: String): Resolved {
        val (kind, id) = linkPattern.find(link)!!.destructured
        val entity = entity(kind, id)
        if (kind == "track") {
            return Resolved(listOf(track(id, entity)), null, Origin.SPOTIFY, link, cover = image(entity))
        }
        val items = entity["trackList"]!!.jsonArray.map { it.jsonObject }.mapNotNull { t ->
            val uri = t.str("uri") ?: return@mapNotNull null
            Wanted.SpotifyTrack(
                id = uri.substringAfterLast(':'),
                title = t.str("title") ?: return@mapNotNull null,
                artist = t.str("subtitle").orEmpty(),
                durationSec = (t["duration"]?.jsonPrimitive?.long ?: 0) / 1000,
            )
        }
        val name = entity.str("name") ?: entity.str("title") ?: "Spotify"
        val cover = entity["coverArt"]?.jsonObject?.get("sources")?.jsonArray?.firstOrNull()?.jsonObject?.str("url") ?: image(entity)
        return Resolved(items, name, Origin.SPOTIFY, link, truncated = kind == "playlist" && items.size >= EMBED_LIMIT, cover = cover)
    }

    /** The album cover of one track, at 640 px. */
    fun cover(trackId: String): String? = runCatching { image(entity("track", trackId)) }.getOrNull()

    private fun image(e: JsonObject): String? = e["visualIdentity"]?.jsonObject?.get("image")?.jsonArray
        ?.map { it.jsonObject }
        ?.maxByOrNull { it["maxWidth"]?.jsonPrimitive?.intOrNull ?: 0 }
        ?.str("url")

    private fun track(id: String, e: JsonObject) = Wanted.SpotifyTrack(
        id = id,
        title = e.str("title") ?: e.str("name") ?: "?",
        artist = (e["artists"] as? JsonArray)?.joinToString(", ") { it.jsonObject.str("name").orEmpty() }.orEmpty(),
        durationSec = (e["duration"]?.jsonPrimitive?.long ?: 0) / 1000,
    )

    private fun entity(kind: String, id: String): JsonObject {
        val c = URL("https://open.spotify.com/embed/$kind/$id").openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "es-ES,es;q=0.9")
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        if (c.responseCode == 404) throw IllegalArgumentException("Spotify no encuentra esa lista; ¿es pública?")
        val html = c.inputStream.use { it.readBytes().decodeToString() }
        val json = nextData.find(html)?.groupValues?.get(1) ?: error("Spotify cambió su página; no se pudo leer")
        return Json.parseToJsonElement(json).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject["state"]!!
            .jsonObject["data"]!!.jsonObject["entity"]!!.jsonObject
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

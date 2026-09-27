package app.echoes.download

import app.echoes.library.UNKNOWN_ARTIST
import app.echoes.library.cleanTags
import app.echoes.library.fold
import app.echoes.library.primaryArtist
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** A YouTube video we intend to download; enough to show it before fetching anything big. */
data class VideoRef(val id: String, val url: String, val title: String, val uploader: String, val durationSec: Long, val thumb: String?)

class AudioSource(val stream: AudioStream, val extension: String, val contentLength: Long?, val artist: String, val title: String, val coverUrl: String?)

/**
 * Talks to YouTube through NewPipeExtractor. Everything here blocks; callers run it on IO.
 * When YouTube changes and this breaks, updating the NewPipeExtractor version is the fix.
 */
object YouTube {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    private val yt get() = ServiceList.YouTube
    private val linkPattern = Regex("https?://\\S*(youtube\\.com|youtu\\.be)\\S*")

    init {
        NewPipe.init(Http, Localization("es", "ES"), ContentCountry("ES"))
    }

    /** Pulls the first YouTube link out of pasted or shared text ("Mira esto: https://youtu.be/…"). */
    fun findLink(text: String): String? = linkPattern.find(text)?.value

    /**
     * A `watch?v=…&list=RD…` link is one song, like the old script; only an explicit
     * /playlist link brings the whole list (radio mixes would be endless).
     */
    fun resolve(url: String): Resolved {
        if ("/playlist" in url) {
            val info = PlaylistInfo.getInfo(yt, url)
            val items = info.relatedItems.toMutableList()
            var page = info.nextPage
            while (page != null && items.size < 500) {
                val more = PlaylistInfo.getMoreItems(yt, url, page)
                items += more.items
                page = more.nextPage
            }
            val videos = items.map { VideoRef(yt.streamLHFactory.getId(it.url), it.url, it.name, it.uploaderName ?: "", it.duration, best(it.thumbnails)) }
            return Resolved(videos.map(Wanted::Video), info.name, Origin.YOUTUBE, url)
        }
        val info = StreamInfo.getInfo(yt, url)
        val ref = VideoRef(info.id, info.url, info.name, info.uploaderName ?: "", info.duration, best(info.thumbnails))
        return Resolved(listOf(Wanted.Video(ref)), null, Origin.YOUTUBE, url)
    }

    /** `lookupCover = false` when the caller already has a better cover (Spotify). */
    fun audio(video: VideoRef, lookupCover: Boolean = true): AudioSource {
        val info = StreamInfo.getInfo(yt, video.url)
        val progressive = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
        val stream = progressive.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: progressive.maxByOrNull { it.averageBitrate }
            ?: error("YouTube no ofrece audio descargable para este vídeo")
        val ext = if (stream.format == MediaFormat.M4A) "m4a" else "webm"
        val uploader = info.uploaderName ?: ""
        val (artist, title) = tags(info.name, uploader)
        val cover = when {
            !lookupCover || uploader.endsWith(" - Topic") -> best(info.thumbnails)
            else -> albumCover(artist, title) ?: best(info.thumbnails)
        }
        return AudioSource(stream, ext, stream.itagItem?.contentLength?.takeIf { it > 0 }, artist, title, cover)
    }

    /**
     * Music videos have 16:9 thumbnails; the same song on YouTube Music has the square album
     * cover. Only a result whose title matches is trusted, otherwise the thumbnail is kept.
     */
    private fun albumCover(artist: String, title: String): String? = runCatching {
        val query = yt.searchQHFactory.fromQuery("$artist $title", listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS), "")
        val core = fold(title.substringBefore(" (").substringBefore(" [").trim())
        SearchInfo.getInfo(yt, query).relatedItems.filterIsInstance<StreamInfoItem>().take(5)
            .firstOrNull { fold(it.name).let { n -> core in n || n in core } }
            ?.let { best(it.thumbnails) }
            ?.replace(Regex("=w\\d+-h\\d+"), "=w1200-h1200")
    }.getOrNull()

    /**
     * Finds the YouTube upload of a song known only by its tags (a Spotify track). Songs on
     * YouTube Music are tried first; a candidate must match the title and be within 20 s of
     * the expected length, and the artist and closer durations break ties.
     */
    fun match(title: String, artist: String, durationSec: Long): VideoRef? {
        val core = words(title.substringBefore(" - ").substringBefore(" (").substringBefore(" ["))
        val lead = fold(primaryArtist(artist))
        fun pick(filter: String): VideoRef? {
            val query = yt.searchQHFactory.fromQuery("$artist $title", listOf(filter), "")
            return SearchInfo.getInfo(yt, query).relatedItems.filterIsInstance<StreamInfoItem>().take(8)
                .mapNotNull { item ->
                    val overlap = words(item.name).let { w -> if (core.isEmpty()) 0.0 else core.count { it in w }.toDouble() / core.size }
                    val drift = if (durationSec > 0 && item.duration > 0) kotlin.math.abs(item.duration - durationSec) else 0
                    if (overlap < 0.6 || drift > 20) return@mapNotNull null
                    val byArtist = if (lead.isNotBlank() && lead in fold(item.uploaderName.orEmpty() + " " + item.name)) 1.0 else 0.0
                    val score = 2 * overlap + byArtist + (1 - drift / 20.0)
                    score to item
                }
                .maxByOrNull { it.first }?.second
                ?.let { VideoRef(yt.streamLHFactory.getId(it.url), it.url, it.name, it.uploaderName.orEmpty(), it.duration, best(it.thumbnails)) }
        }
        return runCatching { pick(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS) }.getOrNull()
            ?: runCatching { pick(YoutubeSearchQueryHandlerFactory.VIDEOS) }.getOrNull()
    }

    private fun words(s: String): Set<String> = fold(s).split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 }.toSet()

    /** "Queen - Topic" channels carry the clean artist; otherwise parse "Artist - Title (Official Video)". */
    fun tags(videoTitle: String, uploader: String): Pair<String, String> {
        if (uploader.endsWith(" - Topic")) return uploader.removeSuffix(" - Topic") to videoTitle.trim()
        val (title, artist) = cleanTags(videoTitle, null, videoTitle)
        return (if (artist == UNKNOWN_ARTIST) uploader.removeSuffix("VEVO").removeSuffix(" Official").trim().ifBlank { UNKNOWN_ARTIST } else artist) to title
    }

    private fun best(images: List<Image>): String? = images.maxByOrNull { it.width.toLong() * it.height }?.url

    fun bytes(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", UA)
        return c.inputStream.use { it.readBytes() }
    }

    /**
     * Downloads in 1 MB ranges the way YouTube's own players do (POST, `range` and `rn`
     * parameters, matching user agent); a single long GET gets throttled or cut.
     */
    fun fetch(source: AudioSource, out: OutputStream, onProgress: (Long) -> Unit) {
        val base = source.stream.content
        val chunk = 1L shl 20
        var pos = 0L
        var rn = 1
        val buf = ByteArray(64 * 1024)
        while (true) {
            val url = "$base&range=$pos-${pos + chunk - 1}&rn=${rn++}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("User-Agent", if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else UA)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
                c.setRequestProperty("Origin", "https://www.youtube.com")
                c.setRequestProperty("Referer", "https://www.youtube.com")
            }
            c.setFixedLengthStreamingMode(2)
            c.outputStream.use { it.write(byteArrayOf(0x78, 0)) }
            val code = c.responseCode
            if (code !in 200..299) error("YouTube respondió $code")
            var got = 0L
            c.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                }
            }
            pos += got
            onProgress(pos)
            val total = source.contentLength
            if (got < chunk || (total != null && pos >= total)) break
        }
        if (pos == 0L) error("descarga vacía")
    }

    private object Http : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.requestMethod = request.httpMethod()
            c.setRequestProperty("User-Agent", UA)
            request.headers().forEach { (k, values) ->
                values.forEachIndexed { i, v -> if (i == 0) c.setRequestProperty(k, v) else c.addRequestProperty(k, v) }
            }
            request.dataToSend()?.let { body ->
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().decodeToString() } ?: ""
            return Response(code, c.responseMessage, c.headerFields.filterKeys { it != null }, body, c.url.toString())
        }
    }
}

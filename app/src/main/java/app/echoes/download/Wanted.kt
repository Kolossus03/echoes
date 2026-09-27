package app.echoes.download

import app.echoes.library.fold
import app.echoes.library.primaryArtist
import kotlinx.serialization.Serializable

/** A song the user asked for, before we have its audio. */
sealed interface Wanted {
    /** Key in the `sources` table; the same key is never downloaded twice. */
    val key: String
    val title: String
    val artist: String
    val durationSec: Long
    val thumb: String?

    /** A YouTube video: the audio is right there, the tags come from its title. */
    data class Video(val ref: VideoRef) : Wanted {
        private val tags = YouTube.tags(ref.title, ref.uploader)
        override val key get() = ref.id
        override val title get() = tags.second
        override val artist get() = tags.first
        override val durationSec get() = ref.durationSec
        override val thumb get() = ref.thumb
    }

    /**
     * A song known by its tags (a Spotify link or an exported list): exact tags and cover from
     * Spotify, audio found on YouTube Music. Some exporters leave out the Spotify id.
     */
    data class SpotifyTrack(
        val id: String?,
        override val title: String,
        override val artist: String,
        override val durationSec: Long,
        override val thumb: String? = null,
    ) : Wanted {
        override val key get() = id?.let { "spotify:$it" } ?: "song:${fold(primaryArtist(artist))}|${fold(title)}"
    }
}

enum class Origin(val label: String) { YOUTUBE("YouTube"), SPOTIFY("Spotify"), FILE("Archivo") }

/** What a pasted link turned into. `truncated` means the source showed us only part of it. */
class Resolved(
    val items: List<Wanted>,
    val playlistTitle: String?,
    val origin: Origin,
    val link: String,
    val truncated: Boolean = false,
    val cover: String? = items.firstOrNull()?.thumb,
)

/** A playlist the user linked to a list; syncing downloads whatever is new since last time. */
@Serializable
data class LinkedPlaylist(val link: String, val name: String, val folder: String, val origin: String, val lastSync: Long)

object Links {
    /** Accepts pasted or shared text ("Escucha esto: https://…") and routes it to the right source. */
    fun resolve(text: String): Resolved {
        Spotify.findLink(text)?.let { return Spotify.resolve(it) }
        YouTube.findLink(text)?.let { return YouTube.resolve(it) }
        throw IllegalArgumentException("Pega un enlace de YouTube o de Spotify")
    }

    fun looksLikeLink(text: String) = Spotify.findLink(text) != null || YouTube.findLink(text) != null
}

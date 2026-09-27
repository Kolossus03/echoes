package app.echoes.playback

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import app.echoes.Graph
import app.echoes.data.Analysis
import app.echoes.library.ArtLoader
import app.echoes.library.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.guava.future

const val ART_SCHEME = "echoes-art"

fun artUri(track: Track): Uri = Uri.parse("$ART_SCHEME://${track.id}")

fun Track.toMediaItem(analysis: Analysis?, trimSilence: Boolean): MediaItem {
    val builder = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(artUri(this))
                .setDurationMs(durationMs)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
    if (trimSilence && analysis != null && (analysis.introMs > 0 || analysis.outroMs > 0)) {
        builder.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(analysis.introMs)
                .setEndPositionMs(if (analysis.outroMs > 0) durationMs - analysis.outroMs else C.TIME_END_OF_SOURCE)
                .build(),
        )
    }
    return builder.build()
}

fun browsable(id: String, title: String, subtitle: String? = null): MediaItem =
    MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false).build(),
    ).build()

/** Lets the notification and lock screen draw embedded art through the shared cache. */
class ArtBitmapLoader(private val fallback: BitmapLoader, private val scope: CoroutineScope) : BitmapLoader by fallback {
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != ART_SCHEME) return fallback.loadBitmap(uri)
        return scope.future {
            val id = uri.host!!.toLong()
            val track = Graph.library.library.value?.byId?.get(id) ?: error("unknown track $id")
            Graph.art.track(track, ArtLoader.LARGE) ?: error("no art")
        }
    }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        val uri = metadata.artworkUri
        return if (uri?.scheme == ART_SCHEME) loadBitmap(uri) else fallback.loadBitmapFromMetadata(metadata)
    }
}

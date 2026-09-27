package app.echoes.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette as AndroidPalette
import app.echoes.Graph
import app.echoes.library.ArtLoader
import app.echoes.library.Covers
import app.echoes.library.Track
import app.echoes.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun rememberArt(track: Track?, px: Int): Bitmap? {
    val versions by Graph.covers.versions.collectAsState()
    val version = track?.let { versions.size to Graph.covers.version(Covers.track(it.path)) }
    val bmp by produceState(track?.let { Graph.art.cached(it, px) }, track?.id, px, version) {
        value = track?.let { Graph.art.track(it, px) }
    }
    return bmp
}

/** A list's or playlist's own cover when it has one, otherwise the mosaic of its songs. */
@Composable
fun CollectionArt(coverKey: String?, tracks: List<Track>, modifier: Modifier = Modifier, corner: Dp = 10.dp, large: Boolean = false) {
    val versions by Graph.covers.versions.collectAsState()
    val version = coverKey?.let { Graph.covers.version(it) }
    val px = if (large) ArtLoader.LARGE else ArtLoader.SMALL
    val bmp by produceState<Bitmap?>(null, coverKey, version, px, versions.size) {
        value = if (coverKey != null && version != null) Graph.art.cover(coverKey, px) else null
    }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), null, modifier.clip(RoundedCornerShape(corner)), contentScale = ContentScale.Crop)
    } else {
        Mosaic(tracks, modifier, corner)
    }
}

private val placeholderHues = listOf(
    Color(0xFF2E6B57) to Color(0xFF14231E), Color(0xFF5A3A78) to Color(0xFF1B1426),
    Color(0xFF7A3B52) to Color(0xFF26131B), Color(0xFF2F4F7F) to Color(0xFF121A28),
    Color(0xFF7A5A2E) to Color(0xFF261D11), Color(0xFF30606B) to Color(0xFF111F23),
)

@Composable
fun Artwork(track: Track?, modifier: Modifier = Modifier, corner: Dp = 8.dp, large: Boolean = false) {
    val bmp = rememberArt(track, if (large) ArtLoader.LARGE else ArtLoader.SMALL)
    Box(modifier.clip(RoundedCornerShape(corner))) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            val (a, b) = placeholderHues[abs((track?.album ?: "").hashCode()) % placeholderHues.size]
            Box(
                Modifier.fillMaxSize().background(Brush.linearGradient(listOf(a, b))),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.GraphicEq, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.fillMaxSize(0.42f))
            }
        }
    }
}

/** Four different albums in a 2x2 grid; falls back to a single cover for small collections. */
@Composable
fun Mosaic(tracks: List<Track>, modifier: Modifier = Modifier, corner: Dp = 10.dp) {
    val picks = remember(tracks) { tracks.distinctBy { it.album }.take(4) }
    if (picks.size < 4) {
        Artwork(picks.firstOrNull(), modifier, corner)
        return
    }
    Column(modifier.clip(RoundedCornerShape(corner))) {
        for (row in 0..1) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (col in 0..1) Artwork(picks[row * 2 + col], Modifier.weight(1f).fillMaxSize(), 0.dp)
            }
        }
    }
}

data class ArtColors(val base: Color, val accent: Color)

@Composable
fun rememberArtColors(track: Track?): ArtColors {
    val bmp = rememberArt(track, ArtLoader.LARGE)
    val colors by produceState(ArtColors(Palette.surfaceHigh, Palette.mint), bmp) {
        if (bmp == null) {
            value = ArtColors(Palette.surfaceHigh, Palette.mint)
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val p = AndroidPalette.from(bmp).maximumColorCount(16).generate()
            val base = p.darkVibrantSwatch ?: p.darkMutedSwatch ?: p.dominantSwatch
            val accent = p.lightVibrantSwatch ?: p.vibrantSwatch ?: p.lightMutedSwatch
            ArtColors(
                base?.let { Color(it.rgb) } ?: Palette.surfaceHigh,
                accent?.let { Color(it.rgb) } ?: Palette.mint,
            )
        }
    }
    return colors
}

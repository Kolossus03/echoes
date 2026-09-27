package app.echoes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.library.Track
import app.echoes.library.fold
import app.echoes.library.primaryArtist
import app.echoes.ui.LocalActions
import app.echoes.ui.LocalNav
import app.echoes.ui.LocalPlayer
import app.echoes.ui.Palette
import app.echoes.ui.Route

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun fmtLong(ms: Long): String {
    val min = ms / 60_000
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color = Palette.surfaceHigh, textColor: Color = Palette.text, icon: ImageVector? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.clip(CircleShape).background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = textColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = textColor)
    }
}

@Composable
fun TrackRow(track: Track, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, detail: String? = null) {
    val player = LocalPlayer.current
    val ui by player.ui.collectAsState()
    val active = ui.currentId == track.id
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Artwork(track, Modifier.size(48.dp), corner = 6.dp)
            if (active) EqBadge(ui.isPlaying, Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (active) Palette.mint else Palette.text, fontWeight = FontWeight.Medium,
            )
            Text(
                detail ?: "${track.artist} · ${fmtTime(track.durationMs)}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = Palette.muted,
            )
        }
        trailing?.invoke()
        TrackMenu(track)
    }
}

@Composable
fun TrackMenu(track: Track) {
    var open by remember { mutableStateOf(false) }
    val player = LocalPlayer.current
    val actions = LocalActions.current
    val nav = LocalNav.current
    val favs by Graph.favorites.collectAsState()
    val fav = track.path in favs
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Más", tint = Palette.muted) }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Palette.surfaceHigh) {
            @Composable
            fun item(label: String, icon: ImageVector, action: () -> Unit) =
                DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { open = false; action() })
            item("Flow desde esta canción", Icons.Rounded.Waves) { player.startFlow(track) }
            item("Reproducir a continuación", Icons.Rounded.SkipNext) { player.playNext(track) }
            item("Añadir a la cola", Icons.AutoMirrored.Rounded.QueueMusic) { player.enqueue(track); actions.toast("En la cola") }
            item(if (fav) "Quitar de favoritas" else "Favorita", if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { Graph.toggleFavorite(track.path) }
            item("Añadir a playlist", Icons.AutoMirrored.Rounded.PlaylistAdd) { actions.addingToPlaylist = listOf(track) }
            item("Ir al artista", Icons.Rounded.Person) { nav.go(Route.Artist(primaryArtist(track.artist))) }
            item("Ir al álbum", Icons.Rounded.Album) { nav.go(Route.Album(fold(track.album) + "|" + fold(primaryArtist(track.artist)))) }
            item("Arreglar etiquetas", Icons.Rounded.Edit) { actions.editing = track }
            item("Eliminar del móvil", Icons.Rounded.Delete) { actions.delete(listOf(track)) }
        }
    }
}

/** Three bouncing bars drawn over the artwork of whatever is playing. */
@Composable
fun EqBadge(playing: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        EqBars(playing, Modifier.size(20.dp))
    }
}

val screenPadding = PaddingValues(bottom = 160.dp)

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun HeaderButtons(content: @Composable () -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

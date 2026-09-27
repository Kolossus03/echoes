package app.echoes.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.flow.foldersByRecentPlay
import app.echoes.library.Covers
import app.echoes.library.Library
import app.echoes.library.Track
import app.echoes.library.fold
import app.echoes.ui.LocalActions
import app.echoes.ui.LocalNav
import app.echoes.ui.LocalPlayer
import app.echoes.ui.Palette
import app.echoes.ui.Route
import app.echoes.ui.components.CollectionArt
import app.echoes.ui.components.Pill
import app.echoes.ui.components.TrackRow
import app.echoes.ui.components.screenPadding

private enum class Tab(val label: String) { LISTS("Listas"), PLAYLISTS("Playlists"), ALBUMS("Álbumes"), ARTISTS("Artistas"), SONGS("Canciones") }

@Composable
fun LibraryScreen(lib: Library) {
    var tab by rememberSaveable { mutableStateOf(Tab.LISTS) }
    val nav = LocalNav.current
    val player = LocalPlayer.current
    val actions = LocalActions.current
    val playlists by Graph.dao.playlists().collectAsState(emptyList())
    val items by Graph.dao.playlistItems().collectAsState(emptyList())
    val favs by Graph.favorites.collectAsState()
    val taste by Graph.taste.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding) {
        item {
            Text("Biblioteca", style = MaterialTheme.typography.displaySmall, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 18.dp))
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Tab.entries) { t ->
                    Pill(
                        t.label, color = if (t == tab) Palette.mint else Palette.surfaceHigh,
                        textColor = if (t == tab) Palette.bg else Palette.text, onClick = { tab = t },
                    )
                }
            }
        }
        when (tab) {
            Tab.LISTS -> items(lib.foldersByRecentPlay(taste), key = { it.relPath }) { f ->
                Entry(f.name, "${f.tracks.size} canciones", f.tracks, Covers.folder(f.relPath)) { nav.go(Route.Folder(f.relPath)) }
            }
            Tab.PLAYLISTS -> {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { actions.addingToPlaylist = emptyList() }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Palette.mint, modifier = Modifier.size(56.dp).padding(14.dp))
                        Spacer(Modifier.width(14.dp))
                        Text("Nueva playlist", style = MaterialTheme.typography.titleMedium)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { nav.go(Route.Favorites) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Favorite, null, tint = Palette.pink, modifier = Modifier.size(56.dp).padding(14.dp))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Favoritas", style = MaterialTheme.typography.titleMedium)
                            Text("${favs.size} canciones", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    val tracks = items.filter { it.playlistId == p.id }.mapNotNull { lib.byPath[it.path] }
                    Entry(p.name, "${tracks.size} canciones", tracks, Covers.playlist(p.id)) { nav.go(Route.Playlist(p.id)) }
                }
            }
            Tab.ALBUMS -> items(lib.albums, key = { it.key }) { a ->
                Entry(a.title, "${a.artist} · ${a.tracks.size}", a.tracks) { nav.go(Route.Album(a.key)) }
            }
            Tab.ARTISTS -> items(lib.artists, key = { it.name }) { a ->
                Entry(a.name, "${a.tracks.size} canciones", a.tracks) { nav.go(Route.Artist(a.name)) }
            }
            Tab.SONGS -> {
                val sorted = lib.tracks.sortedBy { fold(it.title) }
                itemsIndexed(sorted, key = { _, t -> t.id }) { i, t -> TrackRow(t, onClick = { player.play(sorted, i) }) }
            }
        }
    }
}

@Composable
private fun Entry(title: String, subtitle: String, tracks: List<Track>, coverKey: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CollectionArt(coverKey, tracks, Modifier.size(56.dp), corner = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Palette.muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

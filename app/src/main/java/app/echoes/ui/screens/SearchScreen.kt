package app.echoes.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.echoes.library.Library
import app.echoes.library.fold
import app.echoes.ui.LocalNav
import app.echoes.ui.LocalPlayer
import app.echoes.ui.Palette
import app.echoes.ui.Route
import app.echoes.ui.components.Pill
import app.echoes.ui.components.TrackRow
import app.echoes.ui.components.screenPadding

@Composable
fun SearchScreen(lib: Library) {
    var q by rememberSaveable { mutableStateOf("") }
    val nav = LocalNav.current
    val player = LocalPlayer.current
    val songs = remember(q, lib) { lib.search(q).take(200) }
    val fq = fold(q.trim())
    val artists = remember(q, lib) { if (fq.length < 2) emptyList() else lib.artists.filter { fq in fold(it.name) }.take(10) }
    val albums = remember(q, lib) { if (fq.length < 2) emptyList() else lib.albums.filter { fq in fold(it.title) }.take(10) }
    val folders = remember(q, lib) { if (fq.length < 2) emptyList() else lib.folders.filter { fq in fold(it.name) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding) {
        item {
            Text("Buscar", style = MaterialTheme.typography.displaySmall, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 18.dp, bottom = 12.dp))
            TextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                placeholder = { Text("Canción, artista, álbum o lista") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Borrar") } },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Palette.surfaceHigh, unfocusedIndicatorColor = Palette.surfaceHigh,
                    focusedContainerColor = Palette.surfaceHigh, unfocusedContainerColor = Palette.surfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
        if (artists.isNotEmpty() || albums.isNotEmpty() || folders.isNotEmpty()) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(folders) { f -> Pill(f.name, icon = Icons.Rounded.FolderOpen) { nav.go(Route.Folder(f.relPath)) } }
                    items(artists) { a -> Pill(a.name, icon = Icons.Rounded.Person) { nav.go(Route.Artist(a.name)) } }
                    items(albums) { a -> Pill(a.title, icon = Icons.Rounded.Album) { nav.go(Route.Album(a.key)) } }
                }
            }
        }
        if (q.isNotBlank() && songs.isEmpty()) {
            item { Text("Nada coincide con «$q»", color = Palette.muted, modifier = Modifier.padding(20.dp)) }
        }
        itemsIndexed(songs, key = { _, t -> t.id }) { i, t -> TrackRow(t, onClick = { player.play(songs, i) }) }
    }
}

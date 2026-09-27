package app.echoes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.library.Covers
import app.echoes.ui.components.Artwork
import kotlinx.coroutines.launch

@Composable
fun ActionDialogs() {
    val actions = LocalActions.current
    actions.editing?.let { t ->
        var title by remember(t) { mutableStateOf(t.title) }
        var artist by remember(t) { mutableStateOf(t.artist) }
        var album by remember(t) { mutableStateOf(t.album) }
        AlertDialog(
            onDismissRequest = { actions.editing = null },
            containerColor = Palette.surfaceHigh,
            title = { Text("Arreglar etiquetas") },
            text = {
                Column {
                    Text("Solo cambia cómo se ve en la app; el archivo no se toca.", color = Palette.muted)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        Artwork(t, Modifier.size(56.dp), corner = 6.dp)
                        TextButton(onClick = { actions.pickCover(Covers.track(t.path)) }) { Text("Cambiar carátula") }
                    }
                    OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(album, { album = it }, label = { Text("Álbum") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { actions.saveTags(t, title, artist, album); actions.editing = null }) { Text("Guardar") } },
            dismissButton = {
                TextButton(onClick = {
                    actions.resetTags(t)
                    actions.editing = null
                }) { Text("Restaurar originales") }
            },
        )
    }

    actions.addingToPlaylist?.let { tracks ->
        val playlists by Graph.dao.playlists().collectAsState(emptyList())
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { actions.addingToPlaylist = null },
            containerColor = Palette.surfaceHigh,
            title = { Text(if (tracks.isEmpty()) "Nueva playlist" else "Añadir a playlist") },
            text = {
                Column {
                    if (tracks.isNotEmpty() && playlists.isNotEmpty()) {
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            items(playlists, key = { it.id }) { p ->
                                Text(
                                    p.name,
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        actions.addToPlaylist(p.id, tracks)
                                        actions.addingToPlaylist = null
                                    }.padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Nombre de la nueva") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { actions.createPlaylist(name.trim(), tracks); actions.addingToPlaylist = null }) { Text("Crear") }
            },
            dismissButton = { TextButton(onClick = { actions.addingToPlaylist = null }) { Text("Cancelar") } },
        )
    }

    actions.renamingFolder?.let { f ->
        var name by remember(f) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { actions.renamingFolder = null },
            containerColor = Palette.surfaceHigh,
            title = { Text("Renombrar lista") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { actions.setFolder(f, name = name.ifBlank { null }); actions.renamingFolder = null }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { actions.renamingFolder = null }) { Text("Cancelar") } },
        )
    }

    actions.renamingPlaylist?.let { p ->
        var name by remember(p) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { actions.renamingPlaylist = null },
            containerColor = Palette.surfaceHigh,
            title = { Text("Renombrar playlist") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    Graph.scope.launch { Graph.dao.renamePlaylist(p.id, name.trim()) }
                    actions.renamingPlaylist = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { actions.renamingPlaylist = null }) { Text("Cancelar") } },
        )
    }
}

package app.echoes.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.data.Playlist
import app.echoes.download.Origin
import app.echoes.flow.buildMixes
import app.echoes.library.Folder
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
import app.echoes.ui.components.TrackRow
import app.echoes.ui.components.fmtLong
import app.echoes.ui.components.rememberArtColors
import app.echoes.ui.components.screenPadding
import kotlinx.coroutines.launch

private class View(
    val title: String,
    val kind: String,
    val tracks: List<Track>,
    val folder: Folder? = null,
    val playlist: Playlist? = null,
) {
    val coverKey: String? = folder?.let { Covers.folder(it.relPath) } ?: playlist?.let { Covers.playlist(it.id) }
}

private enum class Sort(val label: String) {
    ORIGINAL("Orden original"), TITLE("Título"), ARTIST("Artista"), RECENT("Añadidas recientemente"),
    ENERGY_UP("Energía: de calma a subidón"), TEMPO("Tempo (BPM)"),
}

@Composable
private fun resolve(route: Route, lib: Library): View? {
    val favs by Graph.favorites.collectAsState()
    val playlists by Graph.dao.playlists().collectAsState(emptyList())
    val items by Graph.dao.playlistItems().collectAsState(emptyList())
    val taste by Graph.taste.collectAsState()
    return when (route) {
        is Route.Folder -> lib.allFolders.firstOrNull { it.relPath == route.relPath }?.let { View(it.name, "Lista", it.tracks, folder = it) }
        is Route.Album -> lib.albums.firstOrNull { it.key == route.key }?.let { View(it.title, "Álbum · ${it.artist}", it.tracks) }
        is Route.Artist -> lib.artists.firstOrNull { it.name == route.name }?.let { View(it.name, "Artista", it.tracks) }
        is Route.Mix -> remember(lib, taste) { Graph.flowContext()?.let(::buildMixes) }?.firstOrNull { it.id == route.id }
            ?.let { View(it.title, it.subtitle, it.tracks) }
        is Route.Playlist -> playlists.firstOrNull { it.id == route.id }?.let { p ->
            View(p.name, "Playlist", items.filter { it.playlistId == p.id }.mapNotNull { lib.byPath[it.path] }, playlist = p)
        }
        Route.Favorites -> View("Favoritas", "Tus canciones marcadas", favs.mapNotNull { lib.byPath[it] })
        else -> null
    }
}

@Composable
fun CollectionScreen(route: Route, lib: Library) {
    val nav = LocalNav.current
    val player = LocalPlayer.current
    val actions = LocalActions.current
    val view = resolve(route, lib)
    if (view == null) {
        LaunchedEffect(route) { nav.back() }
        return
    }
    val analysis by Graph.analysis.byPath.collectAsState()
    var sort by remember(route) {
        mutableStateOf(Graph.prefs.sortFor(route.toString())?.let { saved -> Sort.entries.firstOrNull { it.name == saved } } ?: Sort.ORIGINAL)
    }
    val tracks = remember(view.tracks, sort, analysis) {
        when (sort) {
            Sort.ORIGINAL -> view.tracks
            Sort.TITLE -> view.tracks.sortedBy { fold(it.title) }
            Sort.ARTIST -> view.tracks.sortedBy { fold(it.artist) }
            Sort.RECENT -> view.tracks.sortedByDescending { it.dateAdded }
            Sort.ENERGY_UP -> view.tracks.sortedBy { analysis[it.path]?.energy ?: 2f }
            Sort.TEMPO -> view.tracks.sortedBy { analysis[it.path]?.bpm?.takeIf { b -> b > 0 } ?: 999f }
        }
    }
    val colors = rememberArtColors(view.tracks.firstOrNull())
    var sortOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    val shuffleOn by Graph.prefs.shuffle.flow.collectAsState()
    val linked by Graph.prefs.linkedFlow.collectAsState()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding) {
        item {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(colors.base.copy(alpha = 0.85f), Palette.bg)))) {
                Column(Modifier.statusBarsPadding().padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                        Spacer(Modifier.weight(1f))
                        if (view.folder != null || view.playlist != null) {
                            Box {
                                IconButton(onClick = { moreOpen = true }) { Icon(Icons.Rounded.MoreVert, "Más") }
                                DropdownMenu(moreOpen, { moreOpen = false }, containerColor = Palette.surfaceHigh) {
                                    view.folder?.let { f ->
                                        linked.filter { it.folder == f.relPath }.forEach { p ->
                                            DropdownMenuItem({ Text("Sincronizar con ${Origin.valueOf(p.origin).label}") }, onClick = {
                                                moreOpen = false
                                                Graph.scope.launch {
                                                    runCatching { Graph.downloads.sync(p) }
                                                        .onSuccess { actions.toast("Buscando novedades en «${p.name}»") }
                                                        .onFailure { actions.toast("No se pudo: ${it.message}") }
                                                }
                                            })
                                        }
                                        DropdownMenuItem({ Text("Borrar lista y sus canciones", color = Palette.pink) }, onClick = {
                                            moreOpen = false
                                            actions.deleteFolder(f)
                                        })
                                        DropdownMenuItem({ Text("Renombrar lista") }, onClick = { moreOpen = false; actions.renamingFolder = f })
                                        DropdownMenuItem({ Text(if (f.hidden) "Mostrar en la biblioteca" else "Ocultar lista") }, onClick = {
                                            moreOpen = false
                                            actions.setFolder(f, hidden = !f.hidden)
                                            if (!f.hidden) nav.back()
                                        })
                                    }
                                    view.playlist?.let { p ->
                                        DropdownMenuItem({ Text("Renombrar playlist") }, onClick = { moreOpen = false; actions.renamingPlaylist = p })
                                        DropdownMenuItem({ Text("Borrar playlist") }, onClick = {
                                            moreOpen = false
                                            Graph.scope.launch { Graph.dao.deletePlaylist(p.id) }
                                            nav.back()
                                        })
                                    }
                                    view.coverKey?.let { key ->
                                        DropdownMenuItem({ Text("Cambiar portada") }, onClick = { moreOpen = false; actions.pickCover(key) })
                                        if (Graph.covers.version(key) != null) {
                                            DropdownMenuItem({ Text("Quitar portada") }, onClick = { moreOpen = false; actions.removeCover(key) })
                                        }
                                    }
                                    DropdownMenuItem({ Text("Añadir todo a una playlist") }, onClick = { moreOpen = false; actions.addingToPlaylist = view.tracks })
                                }
                            }
                        }
                    }
                    CollectionArt(view.coverKey, view.tracks, Modifier.align(Alignment.CenterHorizontally).size(220.dp), corner = 18.dp, large = true)
                    Spacer(Modifier.height(18.dp))
                    Text(
                        view.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    )
                    Text(
                        "${view.kind} · ${view.tracks.size} canciones · ${fmtLong(view.tracks.sumOf { it.durationMs })}",
                        color = Palette.muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            IconButton(onClick = { sortOpen = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Ordenar", tint = Palette.muted) }
                            DropdownMenu(sortOpen, { sortOpen = false }, containerColor = Palette.surfaceHigh) {
                                Sort.entries.forEach { s ->
                                    DropdownMenuItem(
                                        { Text(s.label, color = if (s == sort) Palette.mint else Palette.text) },
                                        onClick = {
                                            sort = s
                                            sortOpen = false
                                            Graph.prefs.setSortFor(route.toString(), s.name)
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { tracks.randomOrNull()?.let(player::startFlow) }) { Icon(Icons.Rounded.Waves, "Flow", tint = Palette.muted) }
                        IconButton(onClick = player::toggleShuffle) {
                            Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (shuffleOn) Palette.mint else Palette.muted)
                        }
                        FilledIconButton(
                            onClick = { player.play(tracks) }, modifier = Modifier.size(58.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Palette.mint, contentColor = Palette.bg),
                        ) { Icon(Icons.Rounded.PlayArrow, "Reproducir", modifier = Modifier.size(32.dp)) }
                    }
                }
            }
        }
        itemsIndexed(tracks, key = { i, t -> "${t.id}-$i" }) { i, t ->
            val a = analysis[t.path]
            val detail = if (sort == Sort.ENERGY_UP || sort == Sort.TEMPO) {
                a?.takeIf { it.bpm > 0 }?.let { "${t.artist} · ${it.bpm.toInt()} BPM · energía ${(it.energy * 100).toInt()}%" }
            } else null
            TrackRow(
                t, onClick = { player.play(tracks, i) }, detail = detail,
                trailing = view.playlist?.let { p ->
                    {
                        IconButton(onClick = {
                            Graph.scope.launch { Graph.dao.setPlaylistPaths(p.id, view.tracks.filterIndexed { j, _ -> j != view.tracks.indexOf(t) }.map { it.path }) }
                        }) { Icon(Icons.Rounded.Close, "Quitar", tint = Palette.muted, modifier = Modifier.clip(CircleShape)) }
                    }
                },
            )
        }
    }
}

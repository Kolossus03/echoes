package app.echoes.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.flow.buildMixes
import app.echoes.flow.foldersByRecentPlay
import app.echoes.library.Covers
import app.echoes.library.Folder
import app.echoes.library.Library
import app.echoes.library.Track
import app.echoes.portal.PortalService
import app.echoes.ui.LocalNav
import app.echoes.ui.LocalPlayer
import app.echoes.ui.MusicHelp
import app.echoes.update.Updates
import app.echoes.ui.Palette
import app.echoes.ui.Route
import app.echoes.ui.components.CollectionArt
import app.echoes.ui.components.Mosaic
import app.echoes.ui.components.SectionTitle
import app.echoes.ui.components.fmtLong
import app.echoes.ui.components.screenPadding
import java.time.LocalTime

@Composable
fun HomeScreen(lib: Library) {
    val nav = LocalNav.current
    val taste by Graph.taste.collectAsState()
    val analysis by Graph.analysis.byPath.collectAsState()
    val favs by Graph.favorites.collectAsState()
    val progress by Graph.analysis.progress.collectAsState()
    val portal by PortalService.address.collectAsState()
    val mixes = remember(lib, taste, analysis.size / 25, favs) { Graph.flowContext()?.let(::buildMixes).orEmpty() }
    val helpSeen by Graph.prefs.helpSeen.flow.collectAsState()
    var help by remember { mutableStateOf(false) }
    val update by Updates.available.collectAsState()
    val context = LocalContext.current

    if (!helpSeen || help) {
        val close = { Graph.prefs.helpSeen.set(true); help = false }
        MusicHelp(onDismiss = close, onDownload = { close(); nav.go(Route.Download) }, onPortal = { close(); nav.go(Route.Settings) })
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.displaySmall)
                    Text("${lib.tracks.size} canciones · ${lib.folders.size} listas", color = Palette.muted, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { nav.go(Route.Download) }) { Icon(Icons.Rounded.Download, "Descargar", tint = Palette.muted) }
                IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Rounded.Settings, "Ajustes", tint = Palette.muted) }
            }
        }
        update?.let { u ->
            item {
                Banner(Icons.Rounded.SystemUpdate, "Nueva versión ${u.version}", "Toca para descargarla; Android la instala encima sin perder nada.") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl)))
                }
            }
        }
        if (lib.tracks.isEmpty()) {
            item { Banner(Icons.Rounded.LibraryMusic, "Aún no hay música", "Mira cómo meterla: YouTube, Spotify o desde el PC.") { help = true } }
        } else {
            item { FlowHero(lib) }
        }
        progress?.let { p ->
            item {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(14.dp)) {
                    Text("Escuchando tu biblioteca… ${p.done}/${p.total}", style = MaterialTheme.typography.titleSmall)
                    Text("Mide volumen, silencios, tempo y energía de cada canción. Solo pasa una vez.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { p.done / p.total.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth(), color = Palette.mint, trackColor = Palette.line)
                }
            }
        }
        if (mixes.isNotEmpty()) {
            item { SectionTitle("Hecho para hoy") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(mixes, key = { it.id }) { mix ->
                        Column(Modifier.width(156.dp).clickable { nav.go(Route.Mix(mix.id)) }) {
                            Box {
                                Mosaic(mix.tracks, Modifier.size(156.dp), corner = 14.dp)
                                Box(
                                    Modifier.matchParentSize().clip(RoundedCornerShape(14.dp))
                                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
                                )
                                Text(
                                    mix.title, style = MaterialTheme.typography.titleLarge, color = Color.White,
                                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(mix.subtitle, color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        }
        if (portal == null) item { PortalTeaser { nav.go(Route.Settings) } }
        item { SectionTitle("Tus listas") }
        val rows = lib.foldersByRecentPlay(taste).chunked(2)
        items(rows.size) { i ->
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                rows[i].forEach { f -> FolderCard(f, Modifier.weight(1f)) { nav.go(Route.Folder(f.relPath)) } }
                if (rows[i].size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 6..13 -> "Buenos días"
    in 14..20 -> "Buenas tardes"
    else -> "Buenas noches"
}

@Composable
private fun Modifier.matchParentSize(): Modifier = this.size(156.dp)

@Composable
private fun FlowHero(lib: Library) {
    val player = LocalPlayer.current
    val flowOn by Graph.flowMode.collectAsState()
    val taste by Graph.taste.collectAsState()
    val seed: Track? = remember(lib, taste) {
        lib.tracks.maxByOrNull { taste.listenedMs[it.path] ?: 0 }?.takeIf { (taste.listenedMs[it.path] ?: 0) > 0 } ?: lib.tracks.randomOrNull()
    }
    Box(
        Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1C4D3A), Color(0xFF1B2440), Color(0xFF3F1D33))))
            .clickable(enabled = seed != null) { seed?.let(player::startFlow) }
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Waves, null, tint = Palette.mint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (flowOn) "FLOW ACTIVO" else "FLOW", style = MaterialTheme.typography.labelMedium, color = Palette.mint)
            }
            Spacer(Modifier.height(10.dp))
            Text("Radio infinita que aprende de ti", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Encadena canciones por lo que sueles poner después, su tempo y su energía. Lo que saltas, deja de sonar.",
                color = Palette.text.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium,
            )
            if (seed != null) {
                Spacer(Modifier.height(14.dp))
                Text(
                    "▶  Empezar desde «${seed.title}»", style = MaterialTheme.typography.labelLarge, color = Color.White,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1C4D3A), Color(0xFF1B2440))))
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.mint, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = Palette.text.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.muted)
    }
}

@Composable
private fun PortalTeaser(onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface)
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Lan, null, tint = Palette.pink, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Portal", style = MaterialTheme.typography.titleMedium)
            Text("Arrastra música desde el navegador del PC. Sin cables, sin scripts.", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.muted)
    }
}

@Composable
fun FolderCard(folder: Folder, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        CollectionArt(Covers.folder(folder.relPath), folder.tracks, Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
        Spacer(Modifier.height(8.dp))
        Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${folder.tracks.size} canciones · ${fmtLong(folder.durationMs)}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}

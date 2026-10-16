package app.echoes.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.res.stringResource
import app.echoes.R
import app.echoes.ui.MusicHelp
import app.echoes.ui.Route
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.download.DlJob
import app.echoes.download.DlState
import app.echoes.download.Resolved
import app.echoes.download.LinkedPlaylist
import app.echoes.download.Links
import app.echoes.download.Origin
import app.echoes.download.Spotify
import app.echoes.download.VideoRef
import app.echoes.download.Wanted
import app.echoes.download.YouTube
import app.echoes.library.Library
import app.echoes.library.fold
import app.echoes.library.primaryArtist
import app.echoes.ui.LocalActions
import app.echoes.ui.LocalNav
import app.echoes.ui.Palette
import app.echoes.ui.components.Pill
import app.echoes.ui.components.SectionTitle
import app.echoes.ui.components.fmtTime
import app.echoes.ui.components.screenPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Preview {
    data object Empty : Preview
    data class Loading(val label: String) : Preview
    data class Ready(val resolved: Resolved) : Preview
    /** Typed text instead of a link: YouTube Music results to pick from. */
    data class Results(val hits: List<VideoRef>) : Preview
    data class Error(val message: String) : Preview
}

@Composable
fun DownloadScreen(lib: Library) {
    val nav = LocalNav.current
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(nav.sharedLink ?: "") }
    var preview by remember { mutableStateOf<Preview>(Preview.Empty) }
    var target by rememberSaveable {
        mutableStateOf(Graph.prefs.lastDownloadFolder?.takeIf { rel -> lib.folders.any { it.relPath == rel } } ?: "")
    }
    var newName by rememberSaveable { mutableStateOf("") }
    val jobs by Graph.downloads.jobs.collectAsState()
    val linked by Graph.prefs.linkedFlow.collectAsState()
    var linkIt by rememberSaveable { mutableStateOf(true) }
    var picked by remember { mutableStateOf(emptyList<VideoRef>()) }
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current

    LaunchedEffect(Unit) { nav.sharedLink = null }
    LaunchedEffect(text) {
        if (text.isBlank()) {
            if ((preview as? Preview.Ready)?.resolved?.origin != Origin.FILE) preview = Preview.Empty
            return@LaunchedEffect
        }
        picked = emptyList()
        if (!Links.looksLikeLink(text)) {
            val query = text.trim()
            if (query.length < 2) {
                preview = Preview.Empty
                return@LaunchedEffect
            }
            delay(500)
            preview = Preview.Loading("Buscando…")
            preview = runCatching { Graph.downloads.search(query) }
                .fold({ if (it.isEmpty()) Preview.Error("Nada en YouTube para «$query»") else Preview.Results(it.take(12)) }, { Preview.Error("No se pudo buscar: ${it.message ?: "error"}") })
            return@LaunchedEffect
        }
        delay(300)
        preview = Preview.Loading("Leyendo enlace…")
        preview = runCatching { Graph.downloads.resolve(text) }
            .fold({ Preview.Ready(it) }, { Preview.Error("No se pudo leer el enlace: ${it.message ?: "error"}") })
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) nav.sharedFile = uri }
    LaunchedEffect(nav.sharedFile) {
        val uri = nav.sharedFile ?: return@LaunchedEffect
        text = ""
        preview = Preview.Loading("Leyendo archivo…")
        preview = runCatching {
            val (name, body) = withContext(Dispatchers.IO) { readFile(context, uri) }
            Graph.downloads.importCsv(name, body)
        }.fold({ Preview.Ready(it) }, { Preview.Error("No se pudo leer el archivo: ${it.message ?: "error"}") })
        // Cleared only now: clearing it first would restart this effect and cancel the import.
        nav.sharedFile = null
    }
    val ready = (preview as? Preview.Ready)?.resolved
        ?: picked.takeIf { it.isNotEmpty() && preview is Preview.Results }?.let { Resolved(it.map(Wanted::Video), null, Origin.YOUTUBE, "") }
    var help by remember { mutableStateOf(false) }
    if (help) MusicHelp(onDismiss = { help = false }, onPortal = { help = false; nav.go(Route.Settings) })
    val newList = target.isEmpty()
    val folder = if (newList) newName.trim().ifBlank { ready?.playlistTitle ?: "" } else target
    val owned = remember(lib) { lib.allTracks.mapTo(HashSet()) { songKey(it.title, it.artist) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding) {
        item {
            Row(Modifier.statusBarsPadding().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                Text("Descargar", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, "Cómo meter música", tint = Palette.muted) }
            }
            Text(
                "Busca una canción en YouTube Music o pega un enlace de YouTube o Spotify. También puedes compartir desde sus apps → ${stringResource(R.string.app_name)}.",
                color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(14.dp))
            TextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("Canción, artista o enlace") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        clip?.getItemAt(0)?.text?.toString()?.let { text = it }
                    }) { Icon(Icons.Rounded.ContentPaste, "Pegar") }
                },
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Palette.surfaceHigh, unfocusedIndicatorColor = Palette.surfaceHigh,
                    focusedContainerColor = Palette.surfaceHigh, unfocusedContainerColor = Palette.surfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    picker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
                }) {
                    Icon(Icons.Rounded.UploadFile, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Importar lista (CSV)")
                }
                Text(
                    "Playlists largas: expórtalas en exportify.net",
                    color = Palette.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            when (val p = preview) {
                Preview.Empty -> {}
                is Preview.Loading -> Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Palette.mint, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(p.label, color = Palette.muted)
                }
                is Preview.Error -> Text(p.message, color = Palette.pink, modifier = Modifier.padding(20.dp))
                is Preview.Ready -> PreviewCard(p.resolved)
                is Preview.Results -> Spacer(Modifier.height(8.dp))
            }
        }
        (preview as? Preview.Results)?.let { r ->
            items(r.hits, key = { it.id }) { hit ->
                val on = hit in picked
                HitRow(hit, on, have = songKey(hit.title, hit.uploader.removeSuffix(" - Topic")) in owned) {
                    picked = if (on) picked - hit else picked + hit
                }
            }
        }
        if (ready != null) {
            item { SectionTitle("¿A qué lista?") }
            item {
                val chips = rememberLazyListState()
                LaunchedEffect(target) {
                    val i = lib.folders.indexOfFirst { it.relPath == target }
                    if (i >= 0) chips.animateScrollToItem(i + 1)
                }
                LazyRow(state = chips, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Pill(
                            "Nueva", icon = Icons.Rounded.Add,
                            color = if (newList) Palette.mint else Palette.surfaceHigh, textColor = if (newList) Palette.bg else Palette.text,
                        ) { target = "" }
                    }
                    items(lib.folders, key = { it.relPath }) { f ->
                        val on = target == f.relPath
                        Pill(f.name, color = if (on) Palette.mint else Palette.surfaceHigh, textColor = if (on) Palette.bg else Palette.text) { target = f.relPath }
                    }
                }
            }
            if (newList) {
                item {
                    TextField(
                        value = newName, onValueChange = { newName = it }, singleLine = true,
                        placeholder = { Text(ready.playlistTitle ?: "Nombre de la lista") },
                        shape = RoundedCornerShape(14.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Palette.surfaceHigh, unfocusedIndicatorColor = Palette.surfaceHigh,
                            focusedContainerColor = Palette.surfaceHigh, unfocusedContainerColor = Palette.surfaceHigh,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            if (ready.playlistTitle != null && ready.origin != Origin.FILE) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { linkIt = !linkIt }.padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Vincular", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Guárdala para sincronizar más adelante: solo se bajan las canciones nuevas.",
                                color = Palette.muted, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(linkIt, { linkIt = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.mint, checkedThumbColor = Palette.bg))
                    }
                }
            }
            item {
                val n = ready.items.size
                Box(
                    Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(99.dp))
                        .background(if (folder.isNotBlank()) Palette.mint else Palette.surfaceHigh)
                        .then(
                            if (folder.isNotBlank()) Modifier.clickable {
                                Graph.prefs.lastDownloadFolder = target.ifEmpty { null }
                                Graph.downloads.enqueue(ready.items, folder)
                                if (linkIt) Graph.downloads.link(ready, folder)
                                preview = Preview.Empty
                                picked = emptyList()
                                text = ""
                            } else Modifier,
                        )
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (n == 1) "Descargar" else "Descargar $n canciones",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (folder.isNotBlank()) Palette.bg else Palette.muted,
                    )
                }
            }
        }
        if (linked.isNotEmpty()) {
            item {
                SectionTitle("Vinculadas") {
                    TextButton(onClick = {
                        scope.launch {
                            linked.forEach { p -> runCatching { Graph.downloads.sync(p) }.onFailure { actions.toast("${p.name}: ${it.message}") } }
                        }
                    }) { Text("Sincronizar todas") }
                }
            }
            items(linked, key = { it.link }) { p -> LinkedRow(p, lib) }
        }
        if (jobs.isNotEmpty()) {
            item {
                SectionTitle("Descargas") {
                    if (jobs.any { it.state == DlState.Waiting }) TextButton(onClick = Graph.downloads::cancelPending) { Text("Cancelar pendientes") }
                    if (jobs.any { it.finished }) TextButton(onClick = Graph.downloads::clearFinished) { Text("Limpiar") }
                }
            }
            items(jobs.reversed(), key = { it.id }) { JobRow(it, lib) }
        }
    }
}

private fun readFile(context: Context, uri: Uri): Pair<String, String> {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Importada.csv"
    val body = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
    return name to body
}

@Composable
private fun rememberUrlImage(url: String?): Bitmap? {
    val bmp by produceState<Bitmap?>(null, url) {
        value = url?.let { u ->
            withContext(Dispatchers.IO) { runCatching { YouTube.bytes(u).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull() }
        }
    }
    return bmp
}

@Composable
private fun PreviewCard(r: Resolved) {
    val first = r.items.firstOrNull() ?: return
    val thumb = rememberUrlImage(r.cover)
    Row(
        Modifier.padding(20.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 112.dp, height = 63.dp).clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh)) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (r.playlistTitle != null) {
                Text(r.playlistTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${r.origin.label} · ${r.items.size} canciones", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                if (r.truncated) {
                    Text("Spotify solo deja ver las ${Spotify.EMBED_LIMIT} primeras", color = Palette.amber, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(first.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${first.artist} · ${fmtTime(first.durationSec * 1000)} · ${r.origin.label}", color = Palette.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Same normalisation as the download queue's duplicate check: main artist and title without "(…)" or " - …". */
private fun songKey(title: String, artist: String) =
    fold(primaryArtist(artist)) + "|" + fold(title.substringBefore(" - ").substringBefore(" (").trim())

@Composable
private fun HitRow(hit: VideoRef, picked: Boolean, have: Boolean, onClick: () -> Unit) {
    // YouTube Music hands out 60 px covers; the URL picks the size.
    val thumb = rememberUrlImage(hit.thumb?.replace(Regex("=w\\d+-h\\d+"), "=w180-h180"))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Palette.surfaceHigh)) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (have) Palette.muted else Palette.text)
            Text(
                listOfNotNull(hit.uploader.removeSuffix(" - Topic"), fmtTime(hit.durationSec * 1000), "Ya la tienes".takeIf { have }).joinToString(" · "),
                color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (picked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
            if (picked) "Quitar" else "Elegir",
            tint = if (picked) Palette.mint else Palette.muted,
        )
    }
}

@Composable
private fun LinkedRow(p: LinkedPlaylist, lib: Library) {
    val scope = rememberCoroutineScope()
    val actions = LocalActions.current
    var busy by remember { mutableStateOf(false) }
    val folderName = lib.allFolders.firstOrNull { it.relPath == p.folder }?.name ?: p.folder.trimEnd('/').substringAfterLast('/')
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Origin.valueOf(p.origin).label} → $folderName · ${ago(p.lastSync)}",
                color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { Graph.downloads.sync(p) }
                    .onSuccess { actions.toast("Comprobando $it canciones de ${p.name}") }
                    .onFailure { actions.toast("No se pudo: ${it.message}") }
                busy = false
            }
        }) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Palette.mint, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Sync, "Sincronizar", tint = Palette.mint)
        }
        IconButton(onClick = { Graph.downloads.unlink(p) }) { Icon(Icons.Rounded.LinkOff, "Desvincular", tint = Palette.muted) }
    }
}

private fun ago(ms: Long): String {
    val min = (System.currentTimeMillis() - ms) / 60_000
    return when {
        min < 1 -> "sincronizada ahora"
        min < 60 -> "hace $min min"
        min < 60 * 24 -> "hace ${min / 60} h"
        else -> "hace ${min / (60 * 24)} días"
    }
}

@Composable
private fun JobRow(job: DlJob, lib: Library) {
    val folderName = lib.allFolders.firstOrNull { it.relPath == job.folder }?.name ?: job.folder
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, tint) = when (job.state) {
                DlState.Waiting -> Icons.Rounded.Schedule to Palette.muted
                is DlState.Working -> Icons.Rounded.Schedule to Palette.mint
                DlState.Done -> Icons.Rounded.CheckCircle to Palette.mint
                is DlState.Skipped -> Icons.Rounded.SkipNext to Palette.amber
                is DlState.Failed -> Icons.Rounded.ErrorOutline to Palette.pink
            }
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${job.item.title} · ${job.item.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (val s = job.state) {
                        DlState.Waiting -> "En cola · $folderName"
                        is DlState.Working -> "Descargando · $folderName"
                        DlState.Done -> "En $folderName"
                        is DlState.Skipped -> s.why
                        is DlState.Failed -> s.why
                    },
                    color = Palette.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        (job.state as? DlState.Working)?.let { w ->
            Spacer(Modifier.height(6.dp))
            val f = w.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, Modifier.fillMaxWidth(), color = Palette.mint, trackColor = Palette.line)
            else LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.mint, trackColor = Palette.line)
        }
    }
}

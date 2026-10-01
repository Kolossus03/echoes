package app.echoes.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import app.echoes.Graph
import app.echoes.ui.components.LyricsView
import app.echoes.SleepTimer
import app.echoes.library.Library
import app.echoes.library.Track
import app.echoes.library.primaryArtist
import app.echoes.ui.LocalNav
import app.echoes.ui.LocalPlayer
import app.echoes.ui.Palette
import app.echoes.ui.Route
import app.echoes.ui.components.Artwork
import app.echoes.ui.components.EqBars
import app.echoes.ui.components.Pill
import app.echoes.ui.components.WaveformSeekBar
import app.echoes.ui.components.fmtTime
import app.echoes.ui.components.rememberArtColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
private fun rememberPosition(): Long {
    val player = LocalPlayer.current
    val ui by player.ui.collectAsState()
    var pos by remember { mutableLongStateOf(player.positionMs) }
    LaunchedEffect(ui.isPlaying, ui.currentId) {
        do {
            pos = player.positionMs
            delay(250)
        } while (ui.isPlaying)
    }
    return pos
}

/**
 * Playing: the full bar. Paused: a slim one-line bar with a close button, and a swipe down
 * (or the ×) hides it until something plays again.
 */
@Composable
fun MiniPlayer(lib: Library, modifier: Modifier = Modifier) {
    val player = LocalPlayer.current
    val nav = LocalNav.current
    val ui by player.ui.collectAsState()
    LaunchedEffect(ui.isPlaying) { if (ui.isPlaying) nav.miniHidden = false }
    val track = ui.currentId?.let { lib.byId[it] } ?: return
    if (nav.miniHidden) return
    val compact = !ui.isPlaying
    val colors = rememberArtColors(track)
    val bg by animateColorAsState(colors.base, label = "mini")
    val pos = rememberPosition()
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    Column(
        modifier.padding(horizontal = if (compact) 24.dp else 8.dp).fillMaxWidth().animateContentSize()
            .offset { IntOffset(0, dy.coerceAtLeast(0f).roundToInt()) }
            .clip(RoundedCornerShape(if (compact) 22.dp else 14.dp))
            .background(Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0.75f).compositeOver(Palette.surface))))
            .clickable { nav.playerOpen = true }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        when {
                            dy > 60 && dy > abs(dx) -> nav.miniHidden = true
                            dx < -120 -> player.next()
                            dx > 120 -> player.previous()
                        }
                        dx = 0f
                        dy = 0f
                    },
                    onDragCancel = { dx = 0f; dy = 0f },
                ) { _, d -> dx += d.x; dy += d.y }
            },
    ) {
        Row(
            Modifier.padding(if (compact) 5.dp else 8.dp).offset { IntOffset((dx / 3).roundToInt(), 0) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track, Modifier.size(if (compact) 34.dp else 44.dp), corner = if (compact) 17.dp else 8.dp)
            Spacer(Modifier.width(10.dp))
            if (compact) {
                Text(
                    "${track.title} · ${track.artist}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = player::toggle, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "Reproducir", tint = Color.White, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = { nav.miniHidden = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Close, "Ocultar", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                }
            } else {
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
                IconButton(onClick = player::toggle) {
                    Icon(Icons.Rounded.Pause, "Pausar", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { player.next() }) { Icon(Icons.Rounded.SkipNext, "Siguiente", tint = Color.White) }
            }
        }
        if (!compact) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(if (ui.durationMs > 0) (pos.toFloat() / ui.durationMs).coerceIn(0f, 1f) else 0f).height(2.dp).background(Color.White))
            }
        }
    }
}

@Composable
fun NowPlaying(lib: Library) {
    val player = LocalPlayer.current
    val nav = LocalNav.current
    val ui by player.ui.collectAsState()
    val track = ui.currentId?.let { lib.byId[it] }
    if (track == null) {
        LaunchedEffect(Unit) { nav.playerOpen = false }
        return
    }
    val colors = rememberArtColors(track)
    val base by animateColorAsState(colors.base, label = "base")
    val accent by animateColorAsState(colors.accent, label = "accent")
    val flowOn by Graph.flowMode.collectAsState()
    val favs by Graph.favorites.collectAsState()
    val analysisMap by Graph.analysis.byPath.collectAsState()
    val analysis = analysisMap[track.path]?.takeIf { it.fingerprint == track.fingerprint && it.usable }
    val trim by Graph.prefs.trimSilence.flow.collectAsState()
    val shuffleOn by Graph.prefs.shuffle.flow.collectAsState()
    val normalize by Graph.prefs.normalize.flow.collectAsState()
    val pos = rememberPosition()
    val artScale by animateFloatAsState(if (ui.isPlaying) 1f else 0.86f, label = "art")
    var dx by remember { mutableFloatStateOf(0f) }
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(base, base.copy(alpha = 0.6f).compositeOver(Palette.bg), Palette.bg)))
            .statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.playerOpen = false }) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", modifier = Modifier.size(30.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (flowOn) "FLOW" else "REPRODUCIENDO", style = MaterialTheme.typography.labelMedium, color = if (flowOn) Palette.pink else Color.White.copy(alpha = 0.7f))
                Text(track.folder.trimEnd('/').substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
            IconButton(onClick = { showLyrics = !showLyrics }) {
                Icon(Icons.Rounded.Lyrics, "Letra", tint = if (showLyrics) Color.White else Color.White.copy(alpha = 0.6f))
            }
            IconButton(onClick = { nav.queueOpen = true }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Cola") }
        }
        if (showLyrics) {
            // Playback of a trimmed song starts after its leading silence; lyrics are timed from the file start.
            val clipStart = if (trim && analysis != null && (analysis.introMs > 0 || analysis.outroMs > 0)) analysis.introMs else 0L
            LyricsView(
                track, pos + clipStart, accent, onSeek = { player.seekTo((it - clipStart).coerceAtLeast(0)) },
                Modifier.weight(1.2f).fillMaxWidth(),
            )
        } else {
        Spacer(Modifier.weight(0.6f))
        Artwork(
            track,
            Modifier.fillMaxWidth().aspectRatio(1f).scale(artScale)
                .graphicsLayer { translationX = dx; rotationZ = dx / 60f; alpha = 1f - (abs(dx) / 900f).coerceAtMost(0.5f) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = {
                        if (dx < -180) player.next() else if (dx > 180) player.previous()
                        dx = 0f
                    }, onDragCancel = { dx = 0f }) { _, d -> dx += d }
                },
            corner = 18.dp, large = true,
        )
        Spacer(Modifier.weight(0.6f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.basicMarquee())
                Text(
                    track.artist, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.7f), maxLines = 1,
                    modifier = Modifier.clickable { nav.go(Route.Artist(primaryArtist(track.artist))) },
                )
            }
            val fav = track.path in favs
            IconButton(onClick = { Graph.toggleFavorite(track.path) }) {
                Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorita", tint = if (fav) Palette.pink else Color.White)
            }
        }
        Spacer(Modifier.height(10.dp))
        WaveformSeekBar(
            analysis = analysis, trimmed = trim, trackDurationMs = track.durationMs,
            progress = if (ui.durationMs > 0) pos.toFloat() / ui.durationMs else 0f, accent = accent,
            onSeek = { player.seekTo((it * ui.durationMs).toLong()) },
        )
        Row {
            Text(fmtTime(pos), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(fmtTime(ui.durationMs), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = player::toggleShuffle) {
                Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (shuffleOn) accent else Color.White.copy(alpha = 0.6f))
            }
            IconButton(onClick = { player.previous() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "Anterior", modifier = Modifier.size(38.dp)) }
            Box(
                Modifier.size(76.dp).clip(CircleShape).background(Color.White).clickable(onClick = player::toggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (ui.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Reproducir", tint = Color.Black, modifier = Modifier.size(42.dp))
            }
            IconButton(onClick = { player.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "Siguiente", modifier = Modifier.size(38.dp)) }
            IconButton(onClick = { player.cycleRepeat() }) {
                Icon(
                    if (ui.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repetir",
                    tint = if (ui.repeatMode == Player.REPEAT_MODE_OFF) Color.White.copy(alpha = 0.6f) else accent,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (analysis != null) {
                val gain = if (normalize) analysis.levelGainDb else 0f
                Pill("${analysis.bpm.roundToInt()} BPM", color = Color.White.copy(alpha = 0.1f))
                Pill("${(analysis.energy * 100).roundToInt()}% energía", color = Color.White.copy(alpha = 0.1f))
                if (normalize) Pill("%+.1f dB".format(gain), color = Color.White.copy(alpha = 0.1f))
            } else {
                Pill("Midiendo…", color = Color.White.copy(alpha = 0.1f))
            }
            Spacer(Modifier.weight(1f))
                IconButton(onClick = { if (flowOn) Graph.flowMode.value = false else player.startFlowKeepingQueue() }) {
                    Icon(Icons.Rounded.Waves, "Flow", tint = if (flowOn) Palette.pink else Color.White.copy(alpha = 0.6f))
                }
            SleepButton()
        }
        Spacer(Modifier.weight(0.4f))
    }
}

@Composable
private fun SleepButton() {
    val sleep by Graph.sleep.collectAsState()
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(sleep) { while (sleep is SleepTimer.At) { now = System.currentTimeMillis(); delay(1000) } }
    Box {
        val label = when (val s = sleep) {
            SleepTimer.Off -> null
            SleepTimer.EndOfTrack -> "Fin"
            is SleepTimer.At -> "${((s.epochMs - now) / 60_000 + 1).coerceAtLeast(0)} min"
        }
        Row(
            Modifier.clip(CircleShape).clickable { open = true }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Bedtime, "Temporizador", tint = if (label != null) Palette.amber else Color.White.copy(alpha = 0.7f))
            if (label != null) Text(" $label", color = Palette.amber, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(open, { open = false }, containerColor = Palette.surfaceHigh) {
            listOf(15, 30, 45, 60, 90).forEach { m ->
                DropdownMenuItem({ Text("$m minutos") }, onClick = {
                    open = false
                    Graph.sleep.value = SleepTimer.At(System.currentTimeMillis() + m * 60_000L)
                })
            }
            DropdownMenuItem({ Text("Al acabar esta canción") }, onClick = { open = false; Graph.sleep.value = SleepTimer.EndOfTrack })
            if (sleep != SleepTimer.Off) DropdownMenuItem({ Text("Desactivar") }, onClick = { open = false; Graph.sleep.value = SleepTimer.Off })
        }
    }
}

@Composable
fun QueueSheet(lib: Library) {
    val player = LocalPlayer.current
    val nav = LocalNav.current
    val ui by player.ui.collectAsState()
    val state = rememberLazyListState(initialFirstVisibleItemIndex = ui.index.coerceAtLeast(0))
    var dragging by remember { mutableIntStateOf(-1) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }

    Column(Modifier.fillMaxSize().background(Palette.bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cola", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            val shuffleOn by Graph.prefs.shuffle.flow.collectAsState()
            IconButton(onClick = player::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (shuffleOn) Palette.mint else Palette.text) }
            IconButton(onClick = { nav.queueOpen = false }) { Icon(Icons.Rounded.Close, "Cerrar") }
        }
        Text(
            "${ui.upNext.size} por sonar · ${app.echoes.ui.components.fmtLong(ui.upNext.sumOf { lib.byId[it]?.durationMs ?: 0 })}",
            color = Palette.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(ui.queue, key = { i, id -> "$i-$id" }) { i, id ->
                val t: Track = lib.byId[id] ?: return@itemsIndexed
                val current = i == ui.index
                Row(
                    Modifier.fillMaxWidth().height(64.dp)
                        .graphicsLayer { if (i == dragging) { translationY = dragDy; shadowElevation = 16f } }
                        .background(if (i == dragging) Palette.surfaceHigh else Color.Transparent)
                        .clickable { player.jumpTo(i) }
                        .padding(start = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        Artwork(t, Modifier.size(44.dp), corner = 6.dp)
                        if (current) Box(Modifier.size(44.dp).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            EqBars(ui.isPlaying, Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (current) Palette.mint else if (i < ui.index) Palette.muted else Palette.text)
                        Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!current) IconButton(onClick = { player.remove(i) }) { Icon(Icons.Rounded.Close, "Quitar", tint = Palette.muted) }
                    Icon(
                        Icons.Rounded.DragHandle, "Mover", tint = Palette.muted,
                        modifier = Modifier.padding(horizontal = 12.dp).pointerInput(i) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = i; dragDy = 0f },
                                onDragEnd = {
                                    val to = (i + (dragDy / rowPx).roundToInt()).coerceIn(0, ui.queue.lastIndex)
                                    if (to != i) player.move(i, to)
                                    dragging = -1; dragDy = 0f
                                },
                                onDragCancel = { dragging = -1; dragDy = 0f },
                            ) { change, d -> change.consume(); dragDy += d.y }
                        },
                    )
                }
            }
        }
    }
}

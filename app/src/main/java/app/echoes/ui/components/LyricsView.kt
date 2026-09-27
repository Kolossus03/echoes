package app.echoes.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.library.Track
import app.echoes.lyrics.LyricsState

/**
 * The song's lyrics in place of the artwork. Synced lyrics follow the song: the sung line is
 * lit and kept a third of the way down; tapping a line jumps there. [positionMs] is the time in
 * the file, not in the (possibly silence-trimmed) playback.
 */
@Composable
fun LyricsView(track: Track, positionMs: Long, accent: Color, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val state by produceState<LyricsState>(LyricsState.Loading, track.path) { value = Graph.lyrics.load(track) }
    Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            LyricsState.Loading -> CircularProgressIndicator(color = accent)
            LyricsState.None -> Note("No hay letra para esta canción.")
            LyricsState.Offline -> Note("Sin conexión: la letra se busca en internet la primera vez.")
            is LyricsState.Plain -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 24.dp)) {
                itemsIndexed(s.lines) { _, line -> LyricLine(line, Color.White.copy(alpha = 0.85f)) }
            }
            is LyricsState.Synced -> Synced(s, positionMs, accent, onSeek)
        }
    }
}

@Composable
private fun Synced(s: LyricsState.Synced, positionMs: Long, accent: Color, onSeek: (Long) -> Unit) {
    val current = s.lines.indexOfLast { it.ms <= positionMs + 250 }
    val list = rememberLazyListState()
    val offset = with(LocalDensity.current) { 110.dp.roundToPx() }
    LaunchedEffect(current) {
        if (current >= 0 && !list.isScrollInProgress) list.animateScrollToItem(current, -offset)
    }
    LazyColumn(Modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(top = 24.dp, bottom = 240.dp)) {
        itemsIndexed(s.lines) { i, line ->
            val color by animateColorAsState(if (i == current) Color.White else Color.White.copy(alpha = 0.4f), label = "line")
            LyricLine(line.text.ifBlank { "♪" }, color, Modifier.clickable { onSeek(line.ms) })
        }
    }
}

@Composable
private fun LyricLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(vertical = 7.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
}

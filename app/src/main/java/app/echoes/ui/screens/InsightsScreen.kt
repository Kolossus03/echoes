package app.echoes.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.echoes.Graph
import app.echoes.flow.Insights
import app.echoes.library.Library
import app.echoes.ui.LocalActions
import app.echoes.ui.LocalPlayer
import app.echoes.ui.Palette
import app.echoes.ui.components.SectionTitle
import app.echoes.ui.components.TrackRow
import app.echoes.ui.components.fmtLong
import app.echoes.ui.components.screenPadding

@Composable
fun InsightsScreen(lib: Library) {
    val plays by Graph.dao.recentPlays(50_000).collectAsState(emptyList())
    val analysis by Graph.analysis.byPath.collectAsState()
    val i = remember(plays, lib, analysis.size / 50) { Insights(plays, lib, analysis) }
    val player = LocalPlayer.current
    val actions = LocalActions.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding) {
        item {
            Text("Tú", style = MaterialTheme.typography.displaySmall, modifier = Modifier.statusBarsPadding().padding(start = 20.dp, top = 18.dp))
            Text("Últimos 30 días", color = Palette.muted, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(fmtLong(i.totalMs), "escuchado", Modifier.weight(1f))
                Stat("${i.playCount}", "canciones enteras", Modifier.weight(1f))
                Stat("${i.streak} d", "racha", Modifier.weight(1f))
            }
        }
        if (plays.isEmpty()) {
            item {
                Text(
                    "Aún no hay escuchas. Pon música y esto se llena solo: qué escuchas, cuándo y qué acabas saltando.",
                    color = Palette.muted, modifier = Modifier.padding(20.dp),
                )
            }
            return@LazyColumn
        }
        item {
            Card("Minutos al día") {
                val max = (i.daily.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
                Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                    val slot = size.width / i.daily.size
                    i.daily.forEachIndexed { k, (_, ms) ->
                        val h = (ms.toFloat() / max) * size.height
                        drawRoundRect(
                            if (k == i.daily.lastIndex) Palette.mint else Palette.mint.copy(alpha = 0.45f),
                            Offset(k * slot + slot * 0.18f, size.height - h.coerceAtLeast(3f)),
                            Size(slot * 0.64f, h.coerceAtLeast(3f)), CornerRadius(6f),
                        )
                    }
                }
            }
        }
        item {
            Card("A qué hora escuchas") {
                val max = (i.hours.maxOrNull() ?: 1L).coerceAtLeast(1L)
                Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                    val slot = size.width / 24
                    i.hours.forEachIndexed { h, ms ->
                        drawRoundRect(
                            Palette.pink.copy(alpha = 0.08f + 0.92f * ms / max.toFloat()),
                            Offset(h * slot + 1, 0f), Size(slot - 2, size.height), CornerRadius(5f),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("0h", "6h", "12h", "18h", "24h").forEachIndexed { k, s ->
                        Text(s, color = Palette.muted, style = MaterialTheme.typography.labelMedium)
                        if (k < 4) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (i.avgBpm != null && i.avgEnergy != null) {
            item {
                Card("Tu sonido") {
                    Text(
                        "Tiras hacia los ${i.avgBpm.toInt()} BPM con una energía media del ${(i.avgEnergy * 100).toInt()}%. " +
                            when {
                                i.avgEnergy > 0.62f -> "Te va la caña."
                                i.avgEnergy < 0.4f -> "Eres de escuchar tranquilo."
                                else -> "Ni muy arriba ni muy abajo."
                            },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        if (i.topArtists.isNotEmpty()) {
            item { SectionTitle("Artistas top") }
            items(i.topArtists) { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Text(r.item, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtLong(r.ms), color = Palette.muted)
                }
            }
        }
        if (i.topTracks.isNotEmpty()) {
            item { SectionTitle("Canciones top") }
            items(i.topTracks, key = { "top" + it.item.id }) { r ->
                TrackRow(r.item, onClick = { player.play(i.topTracks.map { it.item }, i.topTracks.indexOf(r)) }, detail = "${r.item.artist} · ${r.plays} veces")
            }
        }
        if (i.alwaysSkipped.isNotEmpty()) {
            item {
                SectionTitle("Siempre las saltas")
                Text(
                    "Flow ya las evita. Si sobran, ocúltalas quitándolas del móvil.",
                    color = Palette.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            items(i.alwaysSkipped, key = { "skip" + it.item.id }) { r ->
                TrackRow(
                    r.item, onClick = { player.play(listOf(r.item)) }, detail = "${r.item.artist} · saltada ${r.plays} veces",
                    trailing = {
                        IconButton(onClick = { actions.delete(listOf(r.item)) }) { Icon(Icons.Rounded.Delete, "Eliminar", tint = Palette.muted) }
                    },
                )
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Palette.surface).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Text(label, color = Palette.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.surface).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        content()
    }
}


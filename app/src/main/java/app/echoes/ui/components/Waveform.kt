package app.echoes.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.echoes.data.Analysis
import kotlin.math.max
import kotlin.math.pow

/**
 * Seek bar drawn from the song's own loudness envelope. When silence trimming is on, the
 * player's timeline starts at the trimmed intro, so the envelope is sliced to match.
 */
@Composable
fun WaveformSeekBar(
    analysis: Analysis?,
    trimmed: Boolean,
    trackDurationMs: Long,
    progress: Float,
    accent: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val bars = remember(analysis, trimmed, trackDurationMs) { bars(analysis, trimmed, trackDurationMs) }
    val shown = drag ?: progress
    Canvas(
        modifier.fillMaxWidth().height(56.dp)
            .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(onSeek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            },
    ) {
        val n = bars.size
        val slot = size.width / n
        val w = max(1.5f, slot * 0.58f)
        for (i in 0 until n) {
            val h = max(3.dp.toPx(), bars[i] * size.height)
            val x = i * slot + (slot - w) / 2
            val played = (i + 0.5f) / n <= shown
            drawRoundRect(
                color = if (played) accent else Color.White.copy(alpha = 0.22f),
                topLeft = Offset(x, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

private fun bars(analysis: Analysis?, trimmed: Boolean, durationMs: Long): FloatArray {
    val env = analysis?.envelope
    if (env == null || env.isEmpty() || durationMs <= 0) return FloatArray(64) { 0.18f }
    var from = 0
    var to = env.size
    if (trimmed) {
        from = (analysis.introMs * env.size / durationMs).toInt().coerceIn(0, env.size - 1)
        to = (env.size - analysis.outroMs * env.size / durationMs).toInt().coerceIn(from + 1, env.size)
    }
    val slice = env.copyOfRange(from, to)
    val n = 64
    val raw = FloatArray(n) { i ->
        val a = i * slice.size / n
        val b = max(a + 1, (i + 1) * slice.size / n).coerceAtMost(slice.size)
        var m = 0
        for (j in a until b) m = max(m, slice[j].toInt() and 0xFF)
        m / 255f
    }
    val lo = raw.min()
    val span = (raw.max() - lo).coerceAtLeast(0.08f)
    return FloatArray(n) { 0.14f + 0.86f * ((raw[it] - lo) / span).pow(1.4f) }
}

@Composable
fun EqBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = app.echoes.ui.Palette.mint) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = listOf(420, 530, 380).map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d), RepeatMode.Reverse), label = "bar$d")
    }
    Canvas(modifier) {
        val w = size.width / 5
        phases.forEachIndexed { i, p ->
            val h = size.height * (if (playing) p.value else 0.3f)
            drawRoundRect(color, Offset(w * (i * 2), size.height - h), Size(w, h), CornerRadius(w / 2))
        }
    }
}

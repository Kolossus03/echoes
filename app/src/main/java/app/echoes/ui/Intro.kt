package app.echoes.ui

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Once per process: reopening the app from recents while it is still alive does not replay it. */
private var introPending = true

/**
 * Optional startup intro. An edition opts in by shipping a drawable named `intro_image` and,
 * optionally, a raw `intro_sound`; the base app ships neither and never shows it. The image
 * fades in out of a blur while the sound plays, then the intro fades away. A tap skips it.
 */
@SuppressLint("DiscouragedApi")
@Composable
fun Intro() {
    val context = LocalContext.current
    val image = remember { context.resourceId("intro_image", "drawable") }
    var visible by remember { mutableStateOf(introPending && image != 0) }
    if (!visible) return

    val reveal = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    var skip by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        introPending = false
        val sound = context.resourceId("intro_sound", "raw")
        val player = if (sound != 0) MediaPlayer.create(context, sound)?.apply { start() } else null
        onDispose { player?.release() }
    }
    LaunchedEffect(skip) {
        if (!skip) {
            reveal.animateTo(1f, tween(1100, easing = FastOutSlowInEasing))
            delay(1000)
        }
        launch { reveal.animateTo(1f, tween(150)) }
        exit.animateTo(1f, tween(if (skip) 250 else 500, easing = LinearEasing))
        visible = false
    }

    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = 1f - exit.value }
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, indication = null) { skip = true },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(image), null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(0.68f)
                .graphicsLayer {
                    alpha = reveal.value
                    val s = 1.12f - 0.12f * reveal.value + 0.05f * exit.value
                    scaleX = s
                    scaleY = s
                }
                .blur((18 * (1f - reveal.value)).dp),
        )
    }
}

@SuppressLint("DiscouragedApi")
private fun Context.resourceId(name: String, type: String) = resources.getIdentifier(name, type, packageName)

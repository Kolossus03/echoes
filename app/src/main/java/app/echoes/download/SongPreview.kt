package app.echoes.download

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.echoes.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays a few seconds from the middle of a search result, streamed, so versions can be told
 * apart before downloading. The main player is paused meanwhile and resumed afterwards.
 */
class SongPreview(context: Context, private val main: PlayerConnection, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Loading(val id: String) : State
        data class Playing(val id: String) : State
    }

    var state by mutableStateOf<State>(State.Idle); private set
    private val http = DefaultHttpDataSource.Factory()
    private var totalBytes: Long? = null
    private var rn = 0

    /** A plain GET gets 403; ask the way YouTube's players do (see YouTube.fetch): POST, `range` and `rn` in the URL. */
    private val youtube = ResolvingDataSource.Factory(http) { spec ->
        val end = if (spec.length != C.LENGTH_UNSET.toLong()) spec.position + spec.length - 1 else (totalBytes ?: Long.MAX_VALUE / 2) - 1
        spec.buildUpon()
            .setUri("${spec.uri}&range=${spec.position}-$end&rn=${++rn}")
            .setPosition(0)
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(byteArrayOf(0x78, 0))
            .build()
    }
    private val exo = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(youtube)).build().apply {
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val s = state
                if (isPlaying && s is State.Loading) state = State.Playing(s.id)
            }
        })
    }
    private var job: Job? = null
    private var resumeMain = false

    /** The result being loaded or played, if any. */
    val current: String? get() = state.id

    fun toggle(ref: VideoRef) = if (current == ref.id) stop() else start(ref)

    private fun start(ref: VideoRef) {
        stop()
        state = State.Loading(ref.id)
        main.controller?.takeIf { it.isPlaying }?.let { it.pause(); resumeMain = true }
        job = scope.launch {
            val src = runCatching { withContext(Dispatchers.IO) { YouTube.audio(ref, lookupCover = false) } }
                .getOrElse { stop(); return@launch }
            val url = src.stream.content
            totalBytes = src.contentLength
            http.setUserAgent(YouTube.userAgent(url)).setDefaultRequestProperties(YouTube.headers(url))
            val from = (ref.durationSec * 1000 * 0.35).toLong().coerceAtMost((ref.durationSec * 1000 - LENGTH_MS).coerceAtLeast(0))
            exo.setMediaItem(MediaItem.fromUri(url), from)
            exo.prepare()
            exo.play()
            while (exo.currentPosition < from + LENGTH_MS && exo.playerError == null) delay(250)
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        exo.stop()
        state = State.Idle
        if (resumeMain) main.controller?.play()
        resumeMain = false
    }

    fun release() {
        stop()
        exo.release()
    }

    private val State.id get() = when (this) {
        State.Idle -> null
        is State.Loading -> id
        is State.Playing -> id
    }

    private companion object {
        const val LENGTH_MS = 15_000L
    }
}

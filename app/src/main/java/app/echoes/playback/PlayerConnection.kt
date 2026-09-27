package app.echoes.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.echoes.Graph
import app.echoes.flow.FlowEngine
import app.echoes.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerUi(
    val connected: Boolean = false,
    val currentId: Long? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val queue: List<Long> = emptyList(),
    val index: Int = -1,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val upNext get() = if (index < 0) emptyList() else queue.drop(index + 1)
}

/** UI- and Portal-side handle on the playback service. Commands go through Media3, never around it. */
class PlayerConnection(private val context: Context) {
    private val state = MutableStateFlow(PlayerUi())
    val ui: StateFlow<PlayerUi> = state.asStateFlow()
    var controller: MediaController? = null; private set

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            })
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        controller?.release()
        controller = null
        state.value = PlayerUi()
    }

    private fun publish() {
        val c = controller ?: return
        state.value = PlayerUi(
            connected = true,
            currentId = c.currentMediaItem?.mediaId?.toLongOrNull(),
            isPlaying = c.isPlaying,
            durationMs = c.duration.coerceAtLeast(0),
            queue = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).mediaId.toLongOrNull() },
            index = c.currentMediaItemIndex,
            repeatMode = c.repeatMode,
        )
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0

    private fun item(t: Track) = MediaItem.Builder().setMediaId(t.mediaId).build()

    /** `start = null` means "play the list": from the top, or anywhere when shuffle is on. */
    fun play(tracks: List<Track>, start: Int? = null) {
        val c = controller ?: return
        Graph.flowMode.value = false
        if (start == null) c.setMediaItems(tracks.map(::item), true) else c.setMediaItems(tracks.map(::item), start, 0)
        c.prepare()
        c.play()
    }

    fun toggleShuffle() = Graph.prefs.shuffle.set(!Graph.prefs.shuffle.value)

    fun startFlow(seed: Track) {
        val c = controller ?: return
        val ctx = Graph.flowContext() ?: return
        val next = FlowEngine.continueFrom(ctx, seed, setOf(seed.path), 4)
        Graph.flowMode.value = true
        c.setMediaItems((listOf(seed) + next).map(::item), 0, 0)
        c.prepare()
        c.play()
        Graph.flowMode.value = true
    }

    /** Keeps the current song and lets Flow write everything after it. */
    fun startFlowKeepingQueue() {
        val c = controller ?: return
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
        Graph.flowMode.value = true
    }

    fun playNext(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(t))
        c.addMediaItem(c.currentMediaItemIndex + 1, item(t))
    }

    fun enqueue(t: Track) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(listOf(t))
        c.addMediaItem(item(t))
    }

    fun jumpTo(index: Int) = controller?.run { seekToDefaultPosition(index); play() }
    fun toggle() = controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } }
    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun remove(index: Int) = controller?.removeMediaItem(index)
    fun move(from: Int, to: Int) = controller?.moveMediaItem(from, to)

    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

}

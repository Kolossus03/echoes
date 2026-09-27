package app.echoes.playback

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import app.echoes.Graph
import app.echoes.SleepTimer
import app.echoes.data.Outcome
import app.echoes.data.Play
import app.echoes.data.SavedQueue
import app.echoes.flow.FlowEngine
import app.echoes.library.Library
import app.echoes.library.Track
import app.echoes.ui.MainActivity
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlin.math.pow

private const val TARGET_LUFS = -14f

class PlaybackService : MediaLibraryService() {
    private val scope = MainScope()
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private var enhancer: LoudnessEnhancer? = null
    private var sleepJob: Job? = null
    private val tracker = PlayTracker()

    /** The queue in the order it was asked for; shuffle reorders the player, this stays put. */
    private var baseItems: List<MediaItem> = emptyList()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(listener)
        attachEnhancer(player.audioSessionId)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(open)
            .setBitmapLoader(ArtBitmapLoader(DataSourceBitmapLoader(this), scope))
            .build()

        scope.launch {
            combine(Graph.prefs.normalize.flow, Graph.analysis.byPath) { _, _ -> }.collect { applyGain() }
        }
        scope.launch { Graph.flowMode.collect { if (it) extendFlow() } }
        scope.launch { Graph.sleep.collect(::armSleep) }
        scope.launch { Graph.prefs.shuffle.flow.drop(1).collect { if (it) shuffleAroundCurrent() else unshuffle() } }
        scope.launch { restoreQueue() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    /**
     * Swiping Echoes out of recents closes it for real. With the player paused Android kills the
     * process right after this call, so everything happens here, synchronously: onDestroy may
     * never run, and a paused player's notification is a plain one that outlives the process.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        player.pause()
        removeSession(session)
        getSystemService(NotificationManager::class.java).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        tracker.finish(Outcome.PARTIAL)
        saveQueue()
        scope.cancel()
        enhancer?.release()
        session.release()
        player.release()
        getSystemService(NotificationManager::class.java).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun library(): Library? = Graph.library.library.value

    private fun currentTrack(): Track? = player.currentMediaItem?.mediaId?.toLongOrNull()?.let { library()?.byId?.get(it) }

    private fun resolve(lib: Library, id: String): MediaItem? {
        val t = lib.byId[id.toLongOrNull() ?: return null] ?: return null
        return t.toMediaItem(Graph.analysis.current(t), Graph.prefs.trimSilence.value)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            tracker.finish(if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) Outcome.COMPLETED else null)
            tracker.start(currentTrack(), player.isPlaying)
            applyGain()
            ensureAnalysed()
            extendFlow()
            saveQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (tracker.path == null) tracker.start(currentTrack(), isPlaying) else tracker.playing(isPlaying)
            if (!isPlaying) saveQueue()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && Graph.sleep.value == SleepTimer.EndOfTrack) {
                Graph.sleep.value = SleepTimer.Off
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = attachEnhancer(audioSessionId)
    }

    private fun attachEnhancer(sessionId: Int) {
        enhancer?.release()
        enhancer = runCatching { LoudnessEnhancer(sessionId) }.getOrNull()
        applyGain()
    }

    /** Quiet tracks are lifted with LoudnessEnhancer, loud ones attenuated; both toward one target. */
    private fun applyGain() {
        val a = currentTrack()?.let { Graph.analysis.current(it) }
        val gainDb = if (Graph.prefs.normalize.value && a != null) (TARGET_LUFS - a.loudnessLufs).coerceIn(-15f, 8f) else 0f
        player.volume = if (gainDb < 0) 10f.pow(gainDb / 20f) else 1f
        enhancer?.runCatching {
            setTargetGain(if (gainDb > 0) (gainDb * 100).toInt() else 0)
            enabled = gainDb > 0
        }
    }

    private fun ensureAnalysed() {
        val lib = library() ?: return
        val idx = player.currentMediaItemIndex
        listOf(idx, idx + 1).filter { it in 0 until player.mediaItemCount }.forEach { i ->
            val t = lib.byId[player.getMediaItemAt(i).mediaId.toLongOrNull()] ?: return@forEach
            if (Graph.analysis.current(t) == null) scope.launch { Graph.analysis.analyze(t) }
        }
    }

    private fun extendFlow() {
        if (!Graph.flowMode.value) return
        val ctx = Graph.flowContext() ?: return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining >= 3) return
        val seedId = player.getMediaItemAt(player.mediaItemCount - 1).mediaId.toLongOrNull() ?: return
        val seed = ctx.library.byId[seedId] ?: return
        val queued = (0 until player.mediaItemCount).mapNotNull { ctx.library.byId[player.getMediaItemAt(it).mediaId.toLongOrNull()]?.path }
        val next = FlowEngine.continueFrom(ctx, seed, queued.toSet(), 5)
        player.addMediaItems(next.map { it.toMediaItem(Graph.analysis.current(it), Graph.prefs.trimSilence.value) })
    }

    private fun armSleep(timer: SleepTimer) {
        sleepJob?.cancel()
        player.pauseAtEndOfMediaItems = timer == SleepTimer.EndOfTrack
        if (timer !is SleepTimer.At) return
        sleepJob = scope.launch {
            val fadeMs = 12_000L
            delay((timer.epochMs - System.currentTimeMillis() - fadeMs).coerceAtLeast(0))
            val start = player.volume
            val steps = 60
            for (i in 1..steps) {
                player.volume = start * (1 - i / steps.toFloat())
                delay(fadeMs / steps)
            }
            player.pause()
            applyGain()
            Graph.sleep.value = SleepTimer.Off
        }
    }

    private fun saveQueue() {
        val lib = library() ?: return
        if (player.mediaItemCount == 0) return
        val paths = (0 until player.mediaItemCount).mapNotNull { lib.byId[player.getMediaItemAt(it).mediaId.toLongOrNull()]?.path }
        val base = baseItems.mapNotNull { lib.byId[it.mediaId.toLongOrNull()]?.path }
        Graph.prefs.savedQueue = SavedQueue(paths, player.currentMediaItemIndex, player.currentPosition, base)
    }

    private fun savedItems(lib: Library): MediaItemsWithStartPosition? {
        val saved = Graph.prefs.savedQueue ?: return null
        val items = saved.paths.mapNotNull { lib.byPath[it] }.map { it.toMediaItem(Graph.analysis.current(it), Graph.prefs.trimSilence.value) }
        if (items.isEmpty()) return null
        return MediaItemsWithStartPosition(items, saved.index.coerceIn(0, items.size - 1), saved.positionMs)
    }

    private suspend fun restoreQueue() {
        val lib = Graph.library.library.filterNotNull().first()
        if (player.mediaItemCount > 0) return
        val saved = savedItems(lib) ?: return
        baseItems = Graph.prefs.savedQueue?.base.orEmpty().mapNotNull { lib.byPath[it] }
            .map { it.toMediaItem(Graph.analysis.current(it), Graph.prefs.trimSilence.value) }
        player.setMediaItems(saved.mediaItems, saved.startIndex, saved.startPositionMs)
        player.prepare()
    }

    private fun queue(): List<MediaItem> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

    /** The current song keeps playing; everything else is shuffled after it. */
    private fun shuffleAroundCurrent() {
        if (player.mediaItemCount < 2 || Graph.flowMode.value) return
        val idx = player.currentMediaItemIndex
        val others = queue().filterIndexed { i, _ -> i != idx }.shuffled()
        if (idx > 0) player.removeMediaItems(0, idx)
        player.replaceMediaItems(1, player.mediaItemCount, others)
    }

    /** Back to the order the list was played in, around the song that is playing now. */
    private fun unshuffle() {
        if (player.mediaItemCount < 2 || baseItems.isEmpty() || Graph.flowMode.value) return
        val current = player.currentMediaItem ?: return
        val pos = baseItems.indexOfFirst { it.mediaId == current.mediaId }
        if (pos < 0) return
        val baseIds = baseItems.map { it.mediaId }.toSet()
        val extras = queue().filterIndexed { i, it -> i > player.currentMediaItemIndex && it.mediaId !in baseIds }
        val idx = player.currentMediaItemIndex
        if (idx > 0) player.removeMediaItems(0, idx)
        player.replaceMediaItems(1, player.mediaItemCount, extras + baseItems.drop(pos + 1))
        player.addMediaItems(0, baseItems.take(pos))
    }

    private inner class PlayTracker {
        var path: String? = null; private set
        private var durationMs = 0L
        private var startedAt = 0L
        private var listened = 0L
        private var playingSince: Long? = null
        private var lastPath: String? = null
        private var lastEndedAt = 0L

        fun start(track: Track?, isPlaying: Boolean) {
            path = track?.path
            durationMs = track?.durationMs ?: 0
            startedAt = System.currentTimeMillis()
            listened = 0
            playingSince = if (isPlaying && track != null) startedAt else null
        }

        fun playing(isPlaying: Boolean) {
            val now = System.currentTimeMillis()
            playingSince?.let { listened += now - it }
            playingSince = if (isPlaying) now else null
        }

        /** `natural` is COMPLETED when the song ran out on its own; null means the user moved on. */
        fun finish(natural: Outcome?) {
            val p = path ?: return
            playing(false)
            path = null
            if (listened < 1500 && natural == null) return
            val threshold = minOf(durationMs / 2, 240_000L)
            val outcome = when {
                natural != null -> natural
                listened >= threshold -> Outcome.COMPLETED
                listened < minOf(30_000L, durationMs / 3) -> Outcome.SKIPPED
                else -> Outcome.PARTIAL
            }
            val now = System.currentTimeMillis()
            val prev = lastPath.takeIf { now - lastEndedAt < 30 * 60_000 && startedAt - lastEndedAt < 30 * 60_000 }
            val play = Play(
                path = p, prevPath = prev, startedAt = startedAt, listenedMs = listened, durationMs = durationMs,
                outcome = outcome, source = if (Graph.flowMode.value) "flow" else "queue",
            )
            Graph.scope.launch { Graph.dao.insertPlay(play) }
            lastPath = p
            lastEndedAt = now
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            val lib = Graph.library.library.filterNotNull().first()
            mediaItems.mapNotNull { resolve(lib, it.mediaId) }.toMutableList()
        }

        /**
         * Every "play this list" lands here. With shuffle on, the tapped song (or a random one
         * for "play all") goes first and the rest are shuffled; Flow builds its own order.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            val lib = Graph.library.library.filterNotNull().first()
            val items = mediaItems.mapNotNull { resolve(lib, it.mediaId) }
            baseItems = items
            if (items.isEmpty()) return@future MediaItemsWithStartPosition(items, 0, 0)
            val shuffled = Graph.prefs.shuffle.value && !Graph.flowMode.value && items.size > 1
            when {
                shuffled -> {
                    val first = items[if (startIndex == C.INDEX_UNSET) items.indices.random() else startIndex.coerceIn(items.indices)]
                    MediaItemsWithStartPosition(listOf(first) + (items - first).shuffled(), 0, if (startIndex == C.INDEX_UNSET) 0 else startPositionMs)
                }
                startIndex == C.INDEX_UNSET -> MediaItemsWithStartPosition(items, 0, 0)
                else -> MediaItemsWithStartPosition(items, startIndex.coerceIn(items.indices), startPositionMs)
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            savedItems(Graph.library.library.filterNotNull().first()) ?: error("nothing to resume")
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(browsable("root", "Echoes"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val lib = Graph.library.library.filterNotNull().first()
            val items = when {
                parentId == "root" -> lib.folders.map { browsable("folder:${it.relPath}", it.name, "${it.tracks.size} canciones") }
                parentId.startsWith("folder:") -> lib.folders.firstOrNull { it.relPath == parentId.removePrefix("folder:") }
                    ?.tracks.orEmpty().map { it.toMediaItem(null, false) }
                else -> emptyList()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val lib = Graph.library.library.filterNotNull().first()
            resolve(lib, mediaId)?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
    }
}

package app.echoes.analysis

import android.content.Context
import android.util.Log
import app.echoes.Graph
import app.echoes.data.Analysis
import app.echoes.data.EchoesDao
import app.echoes.library.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class AnalysisProgress(val done: Int, val total: Int)

class AnalysisRepository(private val context: Context, private val dao: EchoesDao, private val scope: CoroutineScope) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val neededByPlayer = ConcurrentHashMap.newKeySet<String>()
    private val publishNow = Channel<Unit>(Channel.CONFLATED)

    /**
     * Every upsert reloads the whole table, so after each load Room is held back for a while:
     * a backlog publishes every few seconds instead of once per song. Songs the player is
     * waiting for cut the wait short.
     */
    val byPath: StateFlow<Map<String, Analysis>> = dao.analysis()
        .transform { list ->
            emit(list.associateBy { it.path })
            withTimeoutOrNull(PUBLISH_EVERY_MS) { publishNow.receive() }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val progress = MutableStateFlow<AnalysisProgress?>(null)

    fun current(track: Track): Analysis? = byPath.value[track.path]?.takeIf { it.fingerprint == track.fingerprint && it.usable }

    /** For the songs the player needs next: their result reaches [byPath] at once, even if the backlog is the one measuring them. */
    suspend fun analyzeNow(track: Track) {
        neededByPlayer += track.path
        analyze(track)
    }

    /** Unreadable files are stored too (see [Analysis.usable]) so they are not retried on every pass. */
    suspend fun analyze(track: Track): Analysis? {
        if (!inFlight.add(track.path)) return null
        try {
            val started = System.currentTimeMillis()
            val a = withContext(Dispatchers.Default) {
                runCatching { AudioAnalyzer.analyze(context, track.uri) }
                    .onFailure { Log.w("Echoes", "analysis failed for ${track.path}", it) }
                    .getOrNull()
            }
            val row = Analysis(
                path = track.path,
                fingerprint = track.fingerprint,
                loudnessLufs = a?.loudnessLufs ?: Analysis.UNREADABLE,
                peakDb = a?.peakDb ?: 0f,
                introMs = a?.introMs ?: 0,
                outroMs = a?.outroMs ?: 0,
                energy = a?.energy ?: 0.5f,
                bpm = a?.bpm ?: 0f,
                envelope = a?.envelope ?: ByteArray(0),
                analyzedAt = System.currentTimeMillis(),
            )
            dao.upsertAnalysis(row)
            if (neededByPlayer.remove(track.path)) publishNow.trySend(Unit)
            Log.i("Echoes", "analyzed ${track.title}: ${row.loudnessLufs} LUFS, ${row.bpm} bpm, e=${row.energy}, in=${row.introMs} out=${row.outroMs} (${System.currentTimeMillis() - started} ms)")
            return row
        } finally {
            inFlight.remove(track.path)
        }
    }

    suspend fun analyzeBacklog() {
        while (true) {
            val lib = Graph.library.library.filterNotNull().first()
            val known = dao.analysisFingerprints().associate { it.path to it.fingerprint }
            val todo = lib.tracks.filter { known[it.path] != it.fingerprint }
            if (todo.isEmpty()) break
            val done = AtomicInteger(0)
            progress.value = AnalysisProgress(0, todo.size)
            val gate = Semaphore(4)
            val measured = coroutineScope {
                todo.map { t ->
                    async {
                        val row = gate.withPermit { analyze(t) }
                        progress.value = AnalysisProgress(done.incrementAndGet(), todo.size)
                        row != null
                    }
                }.awaitAll()
            }
            // Whatever was skipped is already being measured for the player, which stores it.
            if (measured.none { it }) break
        }
        progress.value = null
    }

    private var backlog: Job? = null

    /** Runs while the process is alive (app open or music playing); restarts pick up where it left off. */
    fun schedule() {
        if (backlog?.isActive == true) return
        backlog = scope.launch(Dispatchers.Default) { analyzeBacklog() }
    }

    private companion object {
        const val PUBLISH_EVERY_MS = 5_000L
    }
}

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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class AnalysisProgress(val done: Int, val total: Int)

class AnalysisRepository(private val context: Context, private val dao: EchoesDao, private val scope: CoroutineScope) {
    val byPath: StateFlow<Map<String, Analysis>> =
        dao.analysis().map { list -> list.associateBy { it.path } }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val progress = MutableStateFlow<AnalysisProgress?>(null)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    fun current(track: Track): Analysis? = byPath.value[track.path]?.takeIf { it.fingerprint == track.fingerprint && it.usable }

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
            coroutineScope {
                todo.map { t ->
                    async {
                        gate.withPermit { analyze(t) }
                        progress.value = AnalysisProgress(done.incrementAndGet(), todo.size)
                    }
                }.awaitAll()
            }
        }
        progress.value = null
    }

    private var backlog: Job? = null

    /** Runs while the process is alive (app open or music playing); restarts pick up where it left off. */
    fun schedule() {
        if (backlog?.isActive == true) return
        backlog = scope.launch(Dispatchers.Default) { analyzeBacklog() }
    }
}

package app.echoes

import android.app.Application
import app.echoes.update.Updates
import app.echoes.analysis.AnalysisRepository
import app.echoes.data.EchoesDao
import app.echoes.data.EchoesDb
import app.echoes.data.Favorite
import app.echoes.data.Prefs
import app.echoes.download.Downloads
import app.echoes.flow.FlowContext
import app.echoes.flow.Taste
import app.echoes.library.ArtLoader
import app.echoes.library.Covers
import app.echoes.library.LibraryRepository
import app.echoes.lyrics.Lyrics
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class At(val epochMs: Long) : SleepTimer
    data object EndOfTrack : SleepTimer
}

/** App-wide singletons. The playback service, the UI and the Portal all read the same state. */
object Graph {
    lateinit var app: Application; private set
    lateinit var dao: EchoesDao; private set
    lateinit var prefs: Prefs; private set
    lateinit var library: LibraryRepository; private set
    lateinit var analysis: AnalysisRepository; private set
    lateinit var art: ArtLoader; private set
    lateinit var covers: Covers; private set
    lateinit var downloads: Downloads; private set
    lateinit var lyrics: Lyrics; private set
    lateinit var taste: StateFlow<Taste>; private set
    lateinit var favorites: StateFlow<Set<String>>; private set

    /** A failure in background work (one bad file, one lost request) is logged, never fatal to playback. */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.e("Echoes", "background task failed", e) },
    )
    val flowMode = MutableStateFlow(false)
    val sleep = MutableStateFlow<SleepTimer>(SleepTimer.Off)

    fun init(app: Application) {
        this.app = app
        dao = EchoesDb.open(app).dao()
        prefs = Prefs(app)
        library = LibraryRepository(app, dao, scope)
        analysis = AnalysisRepository(app, dao, scope)
        covers = Covers(app)
        art = ArtLoader(app, covers)
        downloads = Downloads(app, scope)
        lyrics = Lyrics(app)
        taste = dao.recentPlays(50_000).map { Taste(it) }.stateIn(scope, SharingStarted.Eagerly, Taste(emptyList()))
        scope.launch {
            library.library.filterNotNull().map { it.tracks.size }.distinctUntilChanged().collect { analysis.schedule() }
        }
        Updates.check(scope)
        favorites = dao.favorites().map { list -> list.map { it.path }.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())
    }

    fun flowContext(): FlowContext? = library.library.value?.let {
        FlowContext(it, analysis.byPath.value, taste.value, favorites.value)
    }

    fun toggleFavorite(path: String) = scope.launch {
        if (path in favorites.value) dao.removeFavorite(path) else dao.addFavorite(Favorite(path, System.currentTimeMillis()))
    }
}

class EchoesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Graph.library.refresh()
        }
    }
}

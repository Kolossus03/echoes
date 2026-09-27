package app.echoes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.echoes.playback.PlayerConnection

sealed interface Route {
    data object Home : Route
    data object Library : Route
    data object Search : Route
    data object Insights : Route
    data object Settings : Route
    data object Favorites : Route
    data object Download : Route
    data class Folder(val relPath: String) : Route
    data class Album(val key: String) : Route
    data class Artist(val name: String) : Route
    data class Mix(val id: String) : Route
    data class Playlist(val id: Long) : Route
}

val tabs = listOf(Route.Home, Route.Library, Route.Search, Route.Insights)

class Navigator {
    val stack = mutableStateListOf<Route>(Route.Home)
    var playerOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)

    /** The paused mini player was swiped away; it comes back when something plays. */
    var miniHidden by mutableStateOf(false)

    /** Text shared from another app (YouTube's share sheet), picked up by the Descargar screen. */
    var sharedLink by mutableStateOf<String?>(null)

    /** A song list file (CSV) opened or shared with Echoes, picked up by the Descargar screen. */
    var sharedFile by mutableStateOf<android.net.Uri?>(null)
    val current get() = stack.last()
    val tab get() = stack.first()

    fun go(route: Route) {
        playerOpen = false
        stack.add(route)
    }

    fun switchTab(route: Route) {
        stack.clear()
        stack.add(route)
    }

    fun back(): Boolean = when {
        queueOpen -> { queueOpen = false; true }
        playerOpen -> { playerOpen = false; true }
        stack.size > 1 -> { stack.removeAt(stack.lastIndex); true }
        stack.first() != Route.Home -> { switchTab(Route.Home); true }
        else -> false
    }
}

val LocalNav = staticCompositionLocalOf<Navigator> { error("no navigator") }
val LocalPlayer = staticCompositionLocalOf<PlayerConnection> { error("no player") }
val LocalActions = staticCompositionLocalOf<Actions> { error("no actions") }

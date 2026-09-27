package app.echoes.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.echoes.download.LinkedPlaylist
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom

@Serializable
data class SavedQueue(val paths: List<String>, val index: Int, val positionMs: Long, val base: List<String> = emptyList())

class BoolPref(private val sp: SharedPreferences, private val key: String, default: Boolean) {
    private val state = MutableStateFlow(sp.getBoolean(key, default))
    val flow: StateFlow<Boolean> = state.asStateFlow()
    val value get() = state.value

    fun set(value: Boolean) {
        sp.edit().putBoolean(key, value).apply()
        state.value = value
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("echoes", Context.MODE_PRIVATE)

    val normalize = BoolPref(sp, "normalize", true)
    val trimSilence = BoolPref(sp, "trimSilence", true)
    val portalEnabled = BoolPref(sp, "portal", false)
    val shuffle = BoolPref(sp, "shuffle", false)
    val helpSeen = BoolPref(sp, "helpSeen", false)

    val portalToken: String
        get() = sp.getString("portalToken", null) ?: rotatePortalToken()

    fun rotatePortalToken(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val token = (1..6).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        sp.edit().putString("portalToken", token).apply()
        return token
    }

    var lastDownloadFolder: String?
        get() = sp.getString("lastDownloadFolder", null)
        set(value) = sp.edit().putString("lastDownloadFolder", value).apply()

    /** Sort chosen inside each list, keyed by the list's route; survives restarts. */
    fun sortFor(key: String): String? = sp.getString("sort:$key", null)
    fun setSortFor(key: String, sort: String) = sp.edit().putString("sort:$key", sort).apply()

    private val linkedSerializer = ListSerializer(LinkedPlaylist.serializer())
    private val linkedState = MutableStateFlow(
        sp.getString("linked", null)?.let { runCatching { Json.decodeFromString(linkedSerializer, it) }.getOrNull() }.orEmpty(),
    )
    val linkedFlow: StateFlow<List<LinkedPlaylist>> = linkedState.asStateFlow()
    var linked: List<LinkedPlaylist>
        get() = linkedState.value
        set(value) {
            sp.edit().putString("linked", Json.encodeToString(linkedSerializer, value)).apply()
            linkedState.value = value
        }

    var savedQueue: SavedQueue?
        get() = sp.getString("queue", null)?.let { runCatching { Json.decodeFromString<SavedQueue>(it) }.getOrNull() }
        set(value) {
            sp.edit().putString("queue", value?.let { Json.encodeToString(SavedQueue.serializer(), it) }).apply()
        }
}

package app.echoes.update

import android.util.Log
import app.echoes.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val apkUrl: String)

/**
 * The base app lives on GitHub Releases. On launch it asks for the latest release and, if it is
 * newer, Home offers the APK: the browser downloads it and Android installs it over this one.
 * Private builds have no repo and never check.
 */
object Updates {
    private val state = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = state.asStateFlow()

    fun check(scope: CoroutineScope) {
        val repo = BuildConfig.UPDATE_REPO.ifEmpty { return }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val release = Json.parseToJsonElement(c.inputStream.bufferedReader().use { it.readText() }).jsonObject
                val version = release["tag_name"]!!.jsonPrimitive.content.removePrefix("v")
                val apk = release["assets"]!!.jsonArray.map { it.jsonObject["browser_download_url"]!!.jsonPrimitive.content }
                    .firstOrNull { it.endsWith(".apk") }
                if (apk != null && newer(version, BuildConfig.VERSION_NAME)) state.value = Release(version, apk)
            }.onFailure { Log.i("Echoes", "update check skipped: ${it.message}") }
        }
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

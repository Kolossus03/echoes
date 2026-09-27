package app.echoes.ui

import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.echoes.Graph
import app.echoes.data.FolderMeta
import app.echoes.data.Playlist
import app.echoes.data.TagOverride
import app.echoes.library.Covers
import app.echoes.library.Folder
import app.echoes.library.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything that needs the Activity (system dialogs, toasts) or opens one of our dialogs. */
class Actions(private val activity: ComponentActivity) {
    var editing by mutableStateOf<Track?>(null)
    var addingToPlaylist by mutableStateOf<List<Track>?>(null)
    var renamingFolder by mutableStateOf<Folder?>(null)
    var renamingPlaylist by mutableStateOf<Playlist?>(null)

    private var deletingFolder: Folder? = null
    private val deleteLauncher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
            val folder = deletingFolder
            deletingFolder = null
            if (r.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
            if (folder == null) {
                toast("Eliminada del móvil")
                return@registerForActivityResult
            }
            Graph.scope.launch { Graph.dao.deleteFolderMeta(folder.relPath) }
            Graph.covers.remove(Covers.folder(folder.relPath))
            Graph.prefs.linked = Graph.prefs.linked.filterNot { it.folder == folder.relPath }
            toast("Lista «${folder.name}» borrada")
        }

    private var coverKey: String? = null
    private val coverPicker = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val key = coverKey ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        Graph.scope.launch {
            runCatching {
                val bmp = withContext(Dispatchers.IO) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { d, _, _ ->
                        d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                }
                Graph.covers.set(key, bmp)
            }.onSuccess { toast("Portada cambiada") }.onFailure { toast("No se pudo usar esa imagen") }
        }
    }

    /** Opens the system photo picker (no permission needed) and stores the result as `key`'s cover. */
    fun pickCover(key: String) {
        coverKey = key
        coverPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun removeCover(key: String) = Graph.covers.remove(key)

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    /** Android asks the user itself; no "all files" permission needed. */
    fun delete(tracks: List<Track>) {
        deletingFolder = null
        val pi = MediaStore.createDeleteRequest(activity.contentResolver, tracks.map { it.uri })
        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    /**
     * Deletes every song of a list from the phone (Android shows its own confirmation) and
     * forgets the list's name, cover and Spotify/YouTube link. Hiding is in Ajustes → Listas.
     */
    fun deleteFolder(folder: Folder) {
        deletingFolder = folder
        val pi = MediaStore.createDeleteRequest(activity.contentResolver, folder.tracks.map { it.uri })
        deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }

    fun saveTags(track: Track, title: String, artist: String, album: String) = Graph.scope.launch {
        Graph.dao.upsertOverride(TagOverride(track.path, title.ifBlank { null }, artist.ifBlank { null }, album.ifBlank { null }))
    }

    fun resetTags(track: Track) = Graph.scope.launch { Graph.dao.deleteOverride(track.path) }

    fun setFolder(folder: Folder, name: String? = folder.name, hidden: Boolean = folder.hidden) = Graph.scope.launch {
        Graph.dao.upsertFolderMeta(FolderMeta(folder.relPath, name, hidden))
    }

    fun addToPlaylist(playlistId: Long, tracks: List<Track>) = Graph.scope.launch {
        val existing = Graph.dao.playlistItemsOf(playlistId).map { it.path }
        Graph.dao.setPlaylistPaths(playlistId, existing + tracks.map { it.path }.filterNot { it in existing })
        toast(if (tracks.size == 1) "Añadida" else "${tracks.size} añadidas")
    }

    fun createPlaylist(name: String, tracks: List<Track>) = Graph.scope.launch {
        val id = Graph.dao.insertPlaylist(Playlist(name = name, createdAt = System.currentTimeMillis()))
        if (tracks.isNotEmpty()) Graph.dao.setPlaylistPaths(id, tracks.map { it.path })
        toast("Playlist \"$name\" creada")
    }
}

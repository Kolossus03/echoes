package app.echoes.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * User data is keyed by file path, never by MediaStore id: ids change when Android
 * re-indexes, paths survive reinstalls and re-scans.
 */

@Entity(tableName = "analysis")
class Analysis(
    @PrimaryKey val path: String,
    val fingerprint: String,
    val loudnessLufs: Float,
    val peakDb: Float,
    val introMs: Long,
    val outroMs: Long,
    val energy: Float,
    val bpm: Float,
    val envelope: ByteArray,
    val analyzedAt: Long,
) {
    /** False for files the decoder could not read; they are stored so they are not retried. */
    val usable get() = loudnessLufs > UNREADABLE + 1

    companion object {
        /** SQLite stores NaN as NULL, so "unreadable" is a sentinel far below any real loudness. */
        const val UNREADABLE = -999f
    }
}

enum class Outcome { COMPLETED, SKIPPED, PARTIAL }

@Entity(tableName = "plays", indices = [Index("path"), Index("startedAt")])
data class Play(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val path: String,
    val prevPath: String?,
    val startedAt: Long,
    val listenedMs: Long,
    val durationMs: Long,
    val outcome: Outcome,
    val source: String,
)

@Entity(tableName = "favorites")
data class Favorite(@PrimaryKey val path: String, val addedAt: Long)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "position"])
data class PlaylistItem(val playlistId: Long, val position: Int, val path: String)

@Entity(tableName = "folder_meta")
data class FolderMeta(@PrimaryKey val relPath: String, val name: String?, val hidden: Boolean)

/** Display fixes for badly tagged files (YouTube rips), applied without touching the file. */
@Entity(tableName = "overrides")
data class TagOverride(
    @PrimaryKey val path: String,
    val title: String?,
    val artist: String?,
    val album: String?,
)

@Dao
interface EchoesDao {
    @Query("SELECT * FROM analysis")
    fun analysis(): Flow<List<Analysis>>

    @Query("SELECT path, fingerprint FROM analysis")
    suspend fun analysisFingerprints(): List<AnalysisKey>

    @Upsert
    suspend fun upsertAnalysis(a: Analysis)

    @Insert
    suspend fun insertPlay(p: Play)

    @Query("SELECT * FROM plays ORDER BY startedAt DESC LIMIT :limit")
    fun recentPlays(limit: Int): Flow<List<Play>>

    @Query("SELECT * FROM plays WHERE startedAt >= :since ORDER BY startedAt")
    fun playsSince(since: Long): Flow<List<Play>>

    @Query("SELECT * FROM plays")
    suspend fun allPlays(): List<Play>

    @Query("SELECT COUNT(*) FROM plays")
    fun playCount(): Flow<Int>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun favorites(): Flow<List<Favorite>>

    @Upsert
    suspend fun addFavorite(f: Favorite)

    @Query("DELETE FROM favorites WHERE path = :path")
    suspend fun removeFavorite(path: String)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun playlists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlist_items ORDER BY playlistId, position")
    fun playlistItems(): Flow<List<PlaylistItem>>

    @Insert
    suspend fun insertPlaylist(p: Playlist): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylistRow(id: Long)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    suspend fun clearPlaylist(id: Long)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun playlistItemsOf(id: Long): List<PlaylistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistItems(items: List<PlaylistItem>)

    @Transaction
    suspend fun setPlaylistPaths(id: Long, paths: List<String>) {
        clearPlaylist(id)
        insertPlaylistItems(paths.mapIndexed { i, p -> PlaylistItem(id, i, p) })
    }

    @Transaction
    suspend fun deletePlaylist(id: Long) {
        clearPlaylist(id)
        deletePlaylistRow(id)
    }

    @Query("SELECT * FROM folder_meta")
    fun folderMeta(): Flow<List<FolderMeta>>

    @Upsert
    suspend fun upsertFolderMeta(m: FolderMeta)

    @Query("DELETE FROM folder_meta WHERE relPath = :relPath")
    suspend fun deleteFolderMeta(relPath: String)

    @Query("SELECT * FROM overrides")
    fun overrides(): Flow<List<TagOverride>>

    @Upsert
    suspend fun upsertOverride(o: TagOverride)

    @Query("DELETE FROM overrides WHERE path = :path")
    suspend fun deleteOverride(path: String)

    @Query("SELECT * FROM sources WHERE videoId = :id")
    suspend fun source(id: String): Source?

    @Upsert
    suspend fun upsertSource(s: Source)
}

/** Which YouTube video became which file, so the same link is never downloaded twice. */
@Entity(tableName = "sources")
data class Source(@PrimaryKey val videoId: String, val path: String, val addedAt: Long)

data class AnalysisKey(val path: String, val fingerprint: String)

@Database(
    entities = [Analysis::class, Play::class, Favorite::class, Playlist::class, PlaylistItem::class, FolderMeta::class, TagOverride::class, Source::class],
    version = 2,
    exportSchema = true,
)
abstract class EchoesDb : RoomDatabase() {
    abstract fun dao(): EchoesDao

    companion object {
        fun open(context: Context): EchoesDb =
            Room.databaseBuilder(context, EchoesDb::class.java, "echoes.db").addMigrations(V1_TO_V2).build()

        private val V1_TO_V2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `sources` (`videoId` TEXT NOT NULL, `path` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`videoId`))")
            }
        }
    }
}

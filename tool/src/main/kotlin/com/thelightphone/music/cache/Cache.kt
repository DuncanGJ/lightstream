package com.thelightphone.music.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.thelightphone.music.model.Track
import com.thelightphone.music.subsonic.SubsonicClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

@Entity(tableName = "cached_track")
data class CachedTrackEntity(
    @PrimaryKey val trackId: String,
    val path: String,
    val bytes: Long,
    val lastAccessedAt: Long,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cached_track WHERE trackId = :id")
    suspend fun get(id: String): CachedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedTrackEntity)

    @Query("UPDATE cached_track SET lastAccessedAt = :t WHERE trackId = :id")
    suspend fun touch(id: String, t: Long)

    @Query("SELECT SUM(bytes) FROM cached_track")
    suspend fun totalBytes(): Long?

    @Query("SELECT * FROM cached_track ORDER BY lastAccessedAt ASC")
    suspend fun lruOrder(): List<CachedTrackEntity>

    @Query("DELETE FROM cached_track WHERE trackId = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM cached_track")
    suspend fun count(): Int

    @Query("SELECT * FROM cached_track")
    suspend fun all(): List<CachedTrackEntity>

    @Query("DELETE FROM cached_track")
    suspend fun clearAll()
}

data class CacheUsage(val bytes: Long, val tracks: Int)

@Database(entities = [CachedTrackEntity::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cacheDao(): CacheDao
}

/**
 * The rolling cache (CONTEXT.md): transcoded audio kept on disk under an LRU budget. Playback
 * resolves a track to a local file when cached; [prefetch] fills the upcoming queue window one
 * track at a time (rolling, cancellable) so it never bursts a big download. beets/Navidrome own
 * the library — this is opportunistic and evictable, never pinned offline.
 */
class TrackCache(
    private val dao: CacheDao,
    private val http: HttpClient,
    private val subsonic: SubsonicClient,
    private val dir: File,
    private val budgetBytes: () -> Long = { DEFAULT_BUDGET_BYTES }, // supplier: Settings-tunable live
    private val now: () -> Long = { System.currentTimeMillis() }, // injectable for deterministic LRU tests
) {
    init {
        dir.mkdirs()
    }

    /** Absolute path of the cached file for [trackId], or null if not cached (self-heals stale rows). */
    suspend fun localPath(trackId: String): String? = withContext(Dispatchers.IO) {
        val entry = dao.get(trackId) ?: return@withContext null
        val file = File(entry.path)
        if (file.exists()) {
            file.absolutePath
        } else {
            dao.deleteById(trackId)
            null
        }
    }

    /** Mark [trackId] as most-recently-used (drives LRU eviction). */
    suspend fun touch(trackId: String) = withContext(Dispatchers.IO) {
        dao.touch(trackId, now())
    }

    /** Download any of [tracks] not already cached, one at a time; stops early if cancelled. */
    suspend fun prefetch(tracks: List<Track>) = withContext(Dispatchers.IO) {
        for (track in tracks) {
            if (!isActive) break
            if (dao.get(track.id) != null) continue
            runCatching { download(track.id) }
        }
    }

    private suspend fun download(trackId: String) {
        val bytes: ByteArray = http.get(subsonic.streamUrl(trackId)).body()
        val file = File(dir, "$trackId.mp3")
        file.writeBytes(bytes)
        dao.upsert(CachedTrackEntity(trackId, file.absolutePath, bytes.size.toLong(), now()))
        evictIfNeeded()
    }

    /** Current cache footprint, for the Settings readout. */
    suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) {
        CacheUsage(bytes = dao.totalBytes() ?: 0L, tracks = dao.count())
    }

    /** Delete every cached file and index row (dev/debug reset). */
    suspend fun clear() = withContext(Dispatchers.IO) {
        dao.all().forEach { File(it.path).delete() }
        dao.clearAll()
    }

    private suspend fun evictIfNeeded() {
        val budget = budgetBytes()
        var total = dao.totalBytes() ?: 0L
        if (total <= budget) return
        for (entry in dao.lruOrder()) {
            if (total <= budget) break
            File(entry.path).delete()
            dao.deleteById(entry.trackId)
            total -= entry.bytes
        }
    }

    companion object {
        const val DEFAULT_BUDGET_BYTES: Long = 500L * 1024 * 1024 // 500 MB
    }
}

package com.thelightphone.music.cache

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Persistent key→JSON store for browse metadata (stale-while-revalidate; see LibraryRepository).
 * A separate database from the audio cache so each schema can evolve independently — the SDK's
 * buildDatabase exposes no migration hooks, so new tables mean new files, not version bumps.
 */
@Entity(tableName = "metadata_cache")
data class MetadataCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface MetadataDao {
    @Query("SELECT * FROM metadata_cache WHERE `key` = :key")
    suspend fun get(key: String): MetadataCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MetadataCacheEntity)

    @Query("DELETE FROM metadata_cache")
    suspend fun clearAll()
}

@Database(entities = [MetadataCacheEntity::class], version = 1, exportSchema = false)
abstract class MetadataDatabase : RoomDatabase() {
    abstract fun metadataDao(): MetadataDao
}

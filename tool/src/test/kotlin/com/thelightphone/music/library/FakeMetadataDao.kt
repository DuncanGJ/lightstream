package com.thelightphone.music.library

import com.thelightphone.music.cache.MetadataCacheEntity
import com.thelightphone.music.cache.MetadataDao

/** An in-memory Metadata cache table — the Room boundary, faked once for every suite. */
internal class FakeMetadataDao : MetadataDao {
    val rows = HashMap<String, MetadataCacheEntity>()
    override suspend fun get(key: String): MetadataCacheEntity? = rows[key]
    override suspend fun upsert(entity: MetadataCacheEntity) { rows[entity.key] = entity }
    override suspend fun clearAll() { rows.clear() }
}

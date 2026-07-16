package com.thelightphone.music.library

import com.thelightphone.music.cache.MetadataCacheEntity
import com.thelightphone.music.cache.MetadataDao
import com.thelightphone.music.subsonic.SubsonicClient
import com.thelightphone.music.subsonic.SubsonicCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ARTISTS_RESPONSE =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","artists":{"index":[
        {"name":"B","artist":[{"id":"ar1","name":"Beatles","albumCount":2}]},
        {"name":"Z","artist":[{"id":"ar2","name":"Zappa","albumCount":5}]}
    ]}}}"""

/** JSON of the domain list exactly as the repository would persist it. */
private const val CACHED_OLD = """[{"id":"old","name":"Old Artist","albumCount":1}]"""
private const val CACHED_FRESH =
    """[{"id":"ar1","name":"Beatles","albumCount":2},{"id":"ar2","name":"Zappa","albumCount":5}]"""

private class FakeMetadataDao : MetadataDao {
    val rows = HashMap<String, MetadataCacheEntity>()
    override suspend fun get(key: String): MetadataCacheEntity? = rows[key]
    override suspend fun upsert(entity: MetadataCacheEntity) { rows[entity.key] = entity }
    override suspend fun clearAll() { rows.clear() }
}

private fun repository(
    dao: FakeMetadataDao = FakeMetadataDao(),
    offline: Boolean = false,
): Pair<LibraryRepository, FakeMetadataDao> {
    val engine = MockEngine {
        if (offline) throw IOException("offline")
        respond(ARTISTS_RESPONSE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val client = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), HttpClient(engine))
    return LibraryRepository(client, dao, now = { 42L }) to dao
}

class LibraryRepositoryTest {

    @Test
    fun `first load fetches from the server and persists the result`() = runBlocking {
        val (repo, dao) = repository()

        val emissions = repo.artists().toList()

        assertEquals(1, emissions.size)
        assertEquals(listOf("Beatles", "Zappa"), emissions.single().map { it.name })
        assertTrue(dao.rows.containsKey("artists"), "the fresh result must be persisted")
    }

    @Test
    fun `cached metadata is served instantly and then refreshed`() = runBlocking {
        val dao = FakeMetadataDao()
        dao.rows["artists"] = MetadataCacheEntity("artists", CACHED_OLD, 0)
        val (repo, _) = repository(dao)

        val emissions = repo.artists().toList()

        assertEquals(2, emissions.size, "stale first, fresh second")
        assertEquals(listOf("Old Artist"), emissions.first().map { it.name })
        assertEquals(listOf("Beatles", "Zappa"), emissions.last().map { it.name })
        assertEquals(CACHED_FRESH, dao.rows["artists"]?.json, "the cache must be refreshed")
    }

    @Test
    fun `offline browsing serves the cache without failing`() = runBlocking {
        val dao = FakeMetadataDao()
        dao.rows["artists"] = MetadataCacheEntity("artists", CACHED_OLD, 0)
        val (repo, _) = repository(dao, offline = true)

        val emissions = repo.artists().toList()

        assertEquals(1, emissions.size)
        assertEquals(listOf("Old Artist"), emissions.single().map { it.name })
    }

    @Test
    fun `unchanged data does not re-emit`() = runBlocking {
        val dao = FakeMetadataDao()
        dao.rows["artists"] = MetadataCacheEntity("artists", CACHED_FRESH, 0)
        val (repo, _) = repository(dao)

        val emissions = repo.artists().toList()

        assertEquals(1, emissions.size, "identical refresh must not churn the UI")
    }
}

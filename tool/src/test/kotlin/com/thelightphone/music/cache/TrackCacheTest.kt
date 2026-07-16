package com.thelightphone.music.cache

import com.thelightphone.music.model.Track
import com.thelightphone.music.subsonic.SubsonicClient
import com.thelightphone.music.subsonic.SubsonicCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun track(id: String) = Track(
    id = id,
    title = "Title $id",
    artist = null,
    album = null,
    albumId = null,
    trackNumber = null,
    durationSec = null,
)

/** In-memory stand-in for the Room DAO (our own port interface — the one boundary we fake). */
private class FakeCacheDao : CacheDao {
    val rows = LinkedHashMap<String, CachedTrackEntity>()
    override suspend fun get(id: String): CachedTrackEntity? = rows[id]
    override suspend fun upsert(entity: CachedTrackEntity) { rows[entity.trackId] = entity }
    override suspend fun touch(id: String, t: Long) { rows[id]?.let { rows[id] = it.copy(lastAccessedAt = t) } }
    override suspend fun totalBytes(): Long? = if (rows.isEmpty()) null else rows.values.sumOf { it.bytes }
    override suspend fun lruOrder(): List<CachedTrackEntity> = rows.values.sortedBy { it.lastAccessedAt }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun count(): Int = rows.size
    override suspend fun all(): List<CachedTrackEntity> = rows.values.toList()
    override suspend fun clearAll() { rows.clear() }
}

private const val BODY = "12345678" // 8 bytes per "track"

private class Fixture(budgetBytes: Long = Long.MAX_VALUE) {
    val engine = MockEngine { respond(BODY, HttpStatusCode.OK) }
    val dao = FakeCacheDao()
    val dir: File = createTempDirectory("trackcache-test").toFile()
    private var clock = 0L
    val cache = TrackCache(
        dao = dao,
        http = HttpClient(engine),
        subsonic = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), HttpClient(engine)),
        dir = dir,
        budgetBytes = { budgetBytes },
        now = { ++clock },
    )
}

class TrackCacheTest {

    @Test
    fun `a prefetched track is served from disk`() = runBlocking {
        val f = Fixture()

        f.cache.prefetch(listOf(track("t1")))

        val path = assertNotNull(f.cache.localPath("t1"))
        assertEquals(BODY, File(path).readText())
    }

    @Test
    fun `prefetch downloads each track only once`() = runBlocking {
        val f = Fixture()

        f.cache.prefetch(listOf(track("t1")))
        f.cache.prefetch(listOf(track("t1")))

        assertEquals(1, f.engine.requestHistory.size)
    }

    @Test
    fun `a stale row self-heals and the track re-downloads`() = runBlocking<Unit> {
        val f = Fixture()
        f.cache.prefetch(listOf(track("t1")))
        val path = assertNotNull(f.cache.localPath("t1"))

        File(path).delete() // disk and index now disagree

        assertNull(f.cache.localPath("t1"), "missing file must not be served")
        f.cache.prefetch(listOf(track("t1")))
        assertEquals(2, f.engine.requestHistory.size, "self-heal must allow a re-download")
        assertNotNull(f.cache.localPath("t1"))
    }

    @Test
    fun `usage reports the cache and clear empties it`() = runBlocking {
        val f = Fixture()
        f.cache.prefetch(listOf(track("t1"), track("t2")))

        val usage = f.cache.usage()
        assertEquals(2, usage.tracks)
        assertEquals(2L * BODY.length, usage.bytes)

        f.cache.clear()

        assertEquals(0, f.cache.usage().tracks)
        assertNull(f.cache.localPath("t1"), "cleared tracks must not be served")
    }

    @Test
    fun `eviction drops the least-recently-used track beyond the budget`() = runBlocking<Unit> {
        val f = Fixture(budgetBytes = 2L * BODY.length) // room for two tracks

        f.cache.prefetch(listOf(track("t1")))
        f.cache.prefetch(listOf(track("t2")))
        f.cache.touch("t1")                  // t1 is now fresher than t2
        f.cache.prefetch(listOf(track("t3"))) // over budget → evict LRU

        assertNull(f.cache.localPath("t2"), "t2 was least-recently-used and must be evicted")
        assertNotNull(f.cache.localPath("t1"), "touched t1 must survive")
        assertNotNull(f.cache.localPath("t3"), "the newest track must survive")
    }
}

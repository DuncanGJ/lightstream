package com.thelightphone.music.library

import com.thelightphone.music.cache.MetadataCacheEntity
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

    // --- albums pagination ---------------------------------------------------

    private fun albumsJson(vararg names: String) =
        names.joinToString(",") { """{"id":"id-$it","name":"$it","songCount":1}""" }
            .let { """{"subsonic-response":{"status":"ok","albumList2":{"album":[$it]}}}""" }

    @Test
    fun `albums pages through the whole library instead of truncating`() = runBlocking {
        val engine = MockEngine { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            val body = when (offset) {
                0 -> albumsJson("A", "B")   // full page: more must follow
                2 -> albumsJson("C")        // short page: the end
                else -> albumsJson()
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), HttpClient(engine))
        val repo = LibraryRepository(client, FakeMetadataDao(), now = { 42L }, albumPageSize = 2)

        val albums = repo.albums().toList().last()

        assertEquals(listOf("A", "B", "C"), albums.map { it.name }, "no silent cap — every page is fetched")
        assertEquals(2, engine.requestHistory.size)
    }

    // --- search policy ---------------------------------------------------------

    @Test
    fun `search is live and returns null offline instead of failing`() = runBlocking {
        val (repo, _) = repository(offline = true)

        assertEquals(null, repo.search("beatles"), "offline search must degrade, not throw")
    }

    @Test
    fun `search maps live results`() = runBlocking {
        val engine = MockEngine {
            respond(
                """{"subsonic-response":{"status":"ok","searchResult3":{
                    "artist":[{"id":"ar1","name":"Beatles","albumCount":2}],"album":[],"song":[]}}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), HttpClient(engine))
        val repo = LibraryRepository(client, FakeMetadataDao(), now = { 42L })

        assertEquals(listOf("Beatles"), repo.search("beat")?.artists?.map { it.name })
    }

    // --- songs pages: network-first with cache fallback -------------------------

    private fun songsJson(vararg ids: String) =
        ids.joinToString(",") { """{"id":"$it","title":"T $it"}""" }
            .let { """{"subsonic-response":{"status":"ok","searchResult3":{"song":[$it]}}}""" }

    @Test
    fun `a songs page is persisted and served from cache offline`() = runBlocking {
        val dao = FakeMetadataDao()
        val onlineEngine = MockEngine {
            respond(songsJson("s1", "s2"), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val online = LibraryRepository(
            SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), HttpClient(onlineEngine)),
            dao,
            now = { 42L },
        )
        assertEquals(listOf("s1", "s2"), online.songsPage(0, 2)?.map { it.id })

        val (offlineRepo, _) = repository(dao, offline = true)
        assertEquals(
            listOf("s1", "s2"),
            offlineRepo.songsPage(0, 2)?.map { it.id },
            "a page you have scrolled must stay browsable offline",
        )
        assertEquals(null, offlineRepo.songsPage(2, 2), "an unseen page offline is unavailable, not empty")
    }

    // --- artistAlbumsNow: decisions must not act on a stale cache ---------------

    /** Domain-shaped Album JSON exactly as the repository persists it (no field omitted). */
    private fun cachedAlbumJson(id: String, name: String) =
        """[{"id":"$id","name":"$name","artist":null,"artistId":null,"year":null,"songCount":1}]"""

    @Test
    fun `artistAlbumsNow prefers the server over a stale cache`() = runBlocking {
        val dao = FakeMetadataDao()
        // Stale cache: the artist used to have one album.
        dao.rows["artist-albums/ar1"] =
            MetadataCacheEntity("artist-albums/ar1", cachedAlbumJson("al1", "Old"), 0)
        val engine = MockEngine {
            respond(
                """{"subsonic-response":{"status":"ok","artist":{"id":"ar1","name":"X","album":[
                    {"id":"al1","name":"Old","songCount":1},{"id":"al2","name":"New","songCount":1}]}}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), HttpClient(engine))
        val repo = LibraryRepository(client, dao, now = { 42L })

        val albums = repo.artistAlbumsNow("ar1")

        assertEquals(2, albums?.size, "a second album must be seen immediately, not after a cache refresh")
    }

    @Test
    fun `artistAlbumsNow falls back to the cache offline`() = runBlocking {
        val dao = FakeMetadataDao()
        dao.rows["artist-albums/ar1"] =
            MetadataCacheEntity("artist-albums/ar1", cachedAlbumJson("al1", "Old"), 0)
        val (repo, _) = repository(dao, offline = true)

        assertEquals(listOf("Old"), repo.artistAlbumsNow("ar1")?.map { it.name })
    }
}

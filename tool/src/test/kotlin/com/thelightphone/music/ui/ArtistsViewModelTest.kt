package com.thelightphone.music.ui

import com.thelightphone.music.cache.MetadataCacheEntity
import com.thelightphone.music.library.FakeMetadataDao
import com.thelightphone.music.library.LibraryRepository
import com.thelightphone.music.model.Artist
import com.thelightphone.music.subsonic.SubsonicClient
import com.thelightphone.music.subsonic.SubsonicCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NO_ARTISTS = """{"subsonic-response":{"status":"ok","artists":{"index":[]}}}"""

/** Domain-shaped Album JSON exactly as the repository persists it (no field omitted). */
private const val CACHED_SINGLE_ALBUM =
    """[{"id":"al-Only","name":"Only","artist":null,"artistId":null,"year":null,"songCount":1}]"""

private fun artistJson(vararg albumNames: String): String {
    val albums = albumNames.joinToString(",") { """{"id":"al-$it","name":"$it","songCount":1}""" }
    return """{"subsonic-response":{"status":"ok","artist":{"id":"ar1","name":"X","album":[$albums]}}}"""
}

/** A library whose getArtist endpoint serves [albumNames]; offline throws instead. */
private fun library(
    dao: FakeMetadataDao = FakeMetadataDao(),
    offline: Boolean = false,
    vararg albumNames: String,
): LibraryRepository {
    val engine = MockEngine { request ->
        if (offline) throw IOException("offline")
        val body = when {
            request.url.encodedPath.endsWith("getArtists") -> NO_ARTISTS
            else -> artistJson(*albumNames)
        }
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val client = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), HttpClient(engine))
    return LibraryRepository(client, dao, now = { 42L })
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistsViewModelTest {

    private val artist = Artist("ar1", "X", albumCount = 0)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a single-album artist skips the album level`() = runTest {
        val vm = ArtistsViewModel(library(albumNames = arrayOf("Only")))

        val dest = vm.destinationFor(artist)

        assertEquals("Only", (dest as ArtistDestination.SingleAlbum).album.name)
    }

    @Test
    fun `a multi-album artist opens the album list`() = runTest {
        val vm = ArtistsViewModel(library(albumNames = arrayOf("First", "Second")))

        assertTrue(vm.destinationFor(artist) is ArtistDestination.Albums)
    }

    @Test
    fun `the tap-through decision sees a new second album despite a stale cache`() = runTest {
        // Regression: deciding on the stale-while-revalidate cache's FIRST emission kept skipping
        // the album level until a refresh happened to complete.
        val dao = FakeMetadataDao()
        dao.rows["artist-albums/ar1"] = MetadataCacheEntity(
            "artist-albums/ar1",
            CACHED_SINGLE_ALBUM, // the artist USED to have one album
            0,
        )
        val vm = ArtistsViewModel(library(dao, albumNames = arrayOf("Only", "Brand New")))

        assertTrue(
            vm.destinationFor(artist) is ArtistDestination.Albums,
            "the fresh two-album answer must win over the stale one-album cache",
        )
    }

    @Test
    fun `offline the decision falls back to the cached albums`() = runTest {
        val dao = FakeMetadataDao()
        dao.rows["artist-albums/ar1"] = MetadataCacheEntity("artist-albums/ar1", CACHED_SINGLE_ALBUM, 0)
        val vm = ArtistsViewModel(library(dao, offline = true))

        assertTrue(vm.destinationFor(artist) is ArtistDestination.SingleAlbum)
    }
}

package com.thelightphone.music.subsonic

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val OK_EMPTY = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""

private const val ERROR_AUTH =
    """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password."}}}"""

private const val ARTISTS =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","artists":{"index":[
        {"name":"B","artist":[{"id":"ar1","name":"Beatles","albumCount":2}]},
        {"name":"Z","artist":[{"id":"ar2","name":"Zappa","albumCount":5}]}
    ]}}}"""

private const val ALBUM =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","album":{"id":"al1","name":"Blue","artist":"X",
        "song":[
            {"id":"t1","title":"One","artist":"X","album":"Blue","albumId":"al1","track":1,"duration":201},
            {"id":"t2","title":"Two","artist":"X","album":"Blue","albumId":"al1","track":2,"duration":95}
        ]}}}"""

private const val SONGS_PAGE =
    """{"subsonic-response":{"status":"ok","version":"1.16.1","searchResult3":{
        "song":[{"id":"s40","title":"Forty"},{"id":"s41","title":"FortyOne"}]}}}"""

private fun md5(input: String): String =
    MessageDigest.getInstance("MD5").digest(input.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** A client wired to a fake HTTP server returning [response]; requests are captured on the engine. */
private fun client(
    baseUrl: String = "https://music.example.com",
    response: String = OK_EMPTY,
): Pair<SubsonicClient, MockEngine> {
    val engine = MockEngine {
        respond(
            content = response,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    val creds = SubsonicCredentials(baseUrl, "alice", "sw0rdfish")
    return SubsonicClient(creds, HttpClient(engine)) to engine
}

class SubsonicClientTest {

    @Test
    fun `requests carry salted-token auth and never the raw password`() = runBlocking {
        val (client, engine) = client()

        client.ping()

        val url = engine.requestHistory.single().url
        val salt = assertNotNull(url.parameters["s"], "salt must be sent")
        assertEquals("alice", url.parameters["u"])
        assertEquals(md5("sw0rdfish" + salt), url.parameters["t"], "token must be md5(password+salt)")
        assertFalse(url.toString().contains("sw0rdfish"), "the raw password must never appear in a URL")
    }

    @Test
    fun `a scheme-less untrimmed base url is normalized to https`() = runBlocking {
        // Regression: Ktor treats a scheme-less URL as a path on http://localhost.
        val (client, engine) = client(baseUrl = " music.example.com/ ")

        client.ping()

        val url = engine.requestHistory.single().url
        assertEquals("https", url.protocol.name)
        assertEquals("music.example.com", url.host)
        assertEquals("/rest/ping", url.encodedPath)
    }

    @Test
    fun `getArtists flattens the indexed response`() = runBlocking {
        val (client, _) = client(response = ARTISTS)

        val artists = client.getArtists()

        assertEquals(listOf("Beatles", "Zappa"), artists.map { it.name })
        assertEquals(listOf(2, 5), artists.map { it.albumCount })
    }

    @Test
    fun `getAlbumTracks maps songs with their metadata in order`() = runBlocking {
        val (client, _) = client(response = ALBUM)

        val tracks = client.getAlbumTracks("al1")

        assertEquals(listOf("t1", "t2"), tracks.map { it.id })
        assertEquals(listOf("One", "Two"), tracks.map { it.title })
        assertEquals(listOf(1, 2), tracks.map { it.trackNumber })
        assertEquals(listOf(201, 95), tracks.map { it.durationSec })
    }

    @Test
    fun `server errors raise SubsonicException carrying the server's message`() = runBlocking {
        val (client, _) = client(response = ERROR_AUTH)

        val e = assertFailsWith<SubsonicException> { client.getArtists() }

        assertEquals(40, e.code)
        assertEquals("Wrong username or password.", e.message)
    }

    @Test
    fun `pingError returns the failure reason instead of throwing`() = runBlocking {
        val (client, _) = client(response = ERROR_AUTH)

        assertEquals("Wrong username or password.", client.pingError())
    }

    @Test
    fun `getSongs pages the whole library via empty-query search3`() = runBlocking {
        val (client, engine) = client(response = SONGS_PAGE)

        val songs = client.getSongs(offset = 40, count = 20)

        val url = engine.requestHistory.single().url
        assertEquals("/rest/search3", url.encodedPath)
        assertEquals("", url.parameters["query"])
        assertEquals("40", url.parameters["songOffset"])
        assertEquals("20", url.parameters["songCount"])
        assertEquals("0", url.parameters["artistCount"])
        assertEquals("0", url.parameters["albumCount"])
        assertEquals(listOf("s40", "s41"), songs.map { it.id })
    }

    @Test
    fun `streamUrl honors the configured bitrate supplier`() {
        val engine = MockEngine { respond(OK_EMPTY, HttpStatusCode.OK) }
        var bitrate = 128
        val client = SubsonicClient(
            SubsonicCredentials("https://music.example.com", "alice", "pw"),
            HttpClient(engine),
            bitrateKbps = { bitrate },
        )

        assertEquals("128", Url(client.streamUrl("t1")).parameters["maxBitRate"])
        bitrate = 64 // live: no client rebuild needed
        assertEquals("64", Url(client.streamUrl("t1")).parameters["maxBitRate"])
    }

    @Test
    fun `streamUrl is self-authenticating and requests 256k mp3`() {
        val (client, _) = client(baseUrl = "music.example.com")

        val url = Url(client.streamUrl("t9"))

        assertEquals("https", url.protocol.name)
        assertEquals("/rest/stream", url.encodedPath)
        assertEquals("t9", url.parameters["id"])
        assertEquals("256", url.parameters["maxBitRate"])
        assertEquals("mp3", url.parameters["format"])
        assertEquals("alice", url.parameters["u"])
        assertTrue(url.parameters.contains("t") && url.parameters.contains("s"), "auth must live in the URL itself")
    }
}

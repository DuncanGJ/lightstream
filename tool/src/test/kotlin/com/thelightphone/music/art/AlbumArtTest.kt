package com.thelightphone.music.art

import com.thelightphone.music.subsonic.SubsonicClient
import com.thelightphone.music.subsonic.SubsonicCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stands in for the Android bitmap codec: "decodes" any 4+ byte body to a 2×2 gray, "encodes" as the raw pixels. */
private class FakeCodec : ArtCodec {
    override fun decode(bytes: ByteArray): Gray? =
        if (bytes.size < 4) null else Gray(2, 2, IntArray(4) { bytes[it].toInt() and 0xFF })
    override fun encodePng(image: Gray): ByteArray = ByteArray(image.pixels.size) { image.pixels[it].toByte() }
}

private class Fixture(status: HttpStatusCode = HttpStatusCode.OK, body: ByteArray = byteArrayOf(0, 0, -1, -1)) {
    val engine = MockEngine { respond(body, status) }
    private val http = HttpClient(engine)
    val subsonic = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), http)
    val dir = createTempDirectory("art-test").toFile()
    val art = AlbumArt(http, subsonic, dir, FakeCodec())
}

class AlbumArtTest {

    @Test
    fun `the cover is fetched once, dithered, and stored for next time`() = runBlocking {
        val f = Fixture()

        val first = assertNotNull(f.art.png("al1"))
        val second = assertNotNull(f.art.png("al1"))

        assertContentEquals(first, second)
        assertEquals(1, f.engine.requestHistory.size, "second call must come from disk")
        assertTrue(f.dir.resolve("al1.png").exists())
        assertTrue(f.engine.requestHistory.single().url.toString().contains("getCoverArt"))
        Unit
    }

    @Test
    fun `a stored cover is already processed - black stays black, white stays white`() = runBlocking {
        val f = Fixture(body = byteArrayOf(0, 0, -1, -1)) // two black, two white pixels

        val png = assertNotNull(f.art.png("al1"))

        assertContentEquals(byteArrayOf(0, 0, -1, -1), png, "the fake codec stores raw pixels; dither must keep the extremes")
        Unit
    }

    @Test
    fun `a server error yields no art and leaves nothing on disk`() = runBlocking {
        val f = Fixture(status = HttpStatusCode.NotFound)

        assertNull(f.art.png("missing"))
        assertTrue(f.dir.listFiles().isNullOrEmpty())
        Unit
    }

    @Test
    fun `an undecodable body yields no art`() = runBlocking {
        val f = Fixture(body = byteArrayOf(1))

        assertNull(f.art.png("al1"))
        Unit
    }

    @Test
    fun `a cover produced this session is served from memory even if its file is gone`() = runBlocking {
        val f = Fixture()
        val first = assertNotNull(f.art.png("al1"))
        f.dir.resolve("al1.png").delete()

        val again = assertNotNull(f.art.png("al1"))

        assertContentEquals(first, again)
        assertEquals(1, f.engine.requestHistory.size, "no refetch")
        Unit
    }
}

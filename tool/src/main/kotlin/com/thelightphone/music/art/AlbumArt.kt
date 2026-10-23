package com.thelightphone.music.art

import com.thelightphone.music.subsonic.SubsonicClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The platform half of album art: image bytes ↔ luminance. Android-backed in production, faked in tests. */
interface ArtCodec {
    /** Decode an encoded image (whatever the server sent) to 8-bit luminance, or null if it isn't one. */
    fun decode(bytes: ByteArray): Gray?
    /** Encode a luminance image as PNG — lossless, so the dither pattern is stored exactly. */
    fun encodePng(image: Gray): ByteArray
}

/**
 * Album art for Now Playing (ADR-0005): one `getCoverArt` fetch per cover id, server-resized to
 * [ART_SIZE_PX], turned into the dithered iPod look once, and kept as a small PNG under [dir].
 * Nothing is ever re-dithered; a cover seen once is available offline. Failures (no art, server
 * error, undecodable body) are simply "no art" and leave nothing on disk, so a later attempt retries.
 */
class AlbumArt(
    private val http: HttpClient,
    private val subsonic: SubsonicClient,
    private val dir: File,
    private val codec: ArtCodec,
) {
    suspend fun png(coverArtId: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(dir, fileNameFor(coverArtId))
        if (file.exists()) return@withContext file.readBytes()
        val response = http.get(subsonic.coverArtUrl(coverArtId, ART_SIZE_PX))
        if (!response.status.isSuccess()) return@withContext null
        val gray = codec.decode(response.body<ByteArray>()) ?: return@withContext null
        val png = codec.encodePng(dither(autocontrast(gray)))
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(png)
        if (!tmp.renameTo(file)) tmp.delete()
        png
    }

    /** Forget every stored cover (Settings → clear metadata cache). */
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileNameFor(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png"

    companion object {
        /** The square the server is asked for and the dither runs at; shown at [ART_DISPLAY_SCALE]× device pixels. */
        const val ART_SIZE_PX = 240
        /** Nearest-neighbour upscale on screen, so each dither dot is a visible 2×2 block. */
        const val ART_DISPLAY_SCALE = 2
    }
}

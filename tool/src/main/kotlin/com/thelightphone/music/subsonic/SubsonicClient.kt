package com.thelightphone.music.subsonic

import com.thelightphone.music.model.Album
import com.thelightphone.music.model.Artist
import com.thelightphone.music.model.Playlist
import com.thelightphone.music.model.SearchResults
import com.thelightphone.music.model.Track
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest

/** Connection + credentials for a Subsonic/OpenSubsonic server (e.g. Navidrome). */
data class SubsonicCredentials(
    val baseUrl: String,   // e.g. https://music.example.com  (no trailing /rest)
    val username: String,
    val password: String,
)

/**
 * A minimal Subsonic/OpenSubsonic client (see ADR-0001). Auth is per-request salted token
 * (t = md5(password + salt)); [streamUrl] carries the same auth in its query so the resulting
 * URL is self-authenticating and can be handed to any dumb player — the Stream URL contract in
 * CONTEXT.md. Responses are read as text and decoded explicitly so the client does not depend on
 * the server returning a JSON content-type.
 */
class SubsonicClient(
    private val creds: SubsonicCredentials,
    private val http: HttpClient,
    private val clientName: String = "lightlms",
    private val bitrateKbps: () -> Int = { STREAM_BITRATE_KBPS }, // supplier: Settings-tunable live
) {
    private val json = Json { ignoreUnknownKeys = true }

    // A scheme-less base URL makes Ktor treat it as a *path* on its default host — literally
    // http://localhost — yielding baffling "CLEARTEXT to localhost" errors. Normalise: trim,
    // strip trailing slashes, and default the scheme to https://.
    private val baseUrl: String = creds.baseUrl.trim().trimEnd('/').let {
        if (it.startsWith("http://", ignoreCase = true) ||
            it.startsWith("https://", ignoreCase = true)
        ) it else "https://$it"
    }

    /** Null if the server is reachable and responds OK; otherwise a short failure reason. */
    suspend fun pingError(): String? = try {
        request("ping") // throws SubsonicException (e.g. bad credentials) or IO errors
        null
    } catch (e: Exception) {
        e.message?.take(200) ?: "connection failed"
    }

    suspend fun getArtists(): List<Artist> =
        request("getArtists").artists?.index.orEmpty()
            .flatMap { it.artist }
            .map { Artist(it.id, it.name, it.albumCount) }

    suspend fun getArtistAlbums(artistId: String): List<Album> =
        request("getArtist", mapOf("id" to artistId)).artist?.album.orEmpty()
            .map { it.toAlbum() }

    suspend fun getAlbumTracks(albumId: String): List<Track> =
        request("getAlbum", mapOf("id" to albumId)).album?.song.orEmpty()
            .map { it.toTrack() }

    suspend fun getAlbums(
        type: String = "alphabeticalByName",
        size: Int = 100,
        offset: Int = 0,
    ): List<Album> =
        request(
            "getAlbumList2",
            mapOf("type" to type, "size" to size.toString(), "offset" to offset.toString()),
        ).albumList2.album.map { it.toAlbum() }

    suspend fun search(
        query: String,
        artistCount: Int = 20,
        albumCount: Int = 20,
        songCount: Int = 40,
    ): SearchResults {
        val r = request(
            "search3",
            mapOf(
                "query" to query,
                "artistCount" to artistCount.toString(),
                "albumCount" to albumCount.toString(),
                "songCount" to songCount.toString(),
            ),
        ).searchResult3
        return SearchResults(
            artists = r.artist.map { Artist(it.id, it.name, it.albumCount) },
            albums = r.album.map { it.toAlbum() },
            tracks = r.song.map { it.toTrack() },
        )
    }

    suspend fun getPlaylists(): List<Playlist> =
        request("getPlaylists").playlists.playlist.map { Playlist(it.id, it.name, it.songCount) }

    suspend fun getPlaylistTracks(playlistId: String): List<Track> =
        request("getPlaylist", mapOf("id" to playlistId)).playlist?.entry.orEmpty()
            .map { it.toTrack() }

    /** One page of the whole song library (search3 with an empty query — Navidrome returns all). */
    suspend fun getSongs(offset: Int, count: Int): List<Track> =
        request(
            "search3",
            mapOf(
                "query" to "",
                "artistCount" to "0",
                "albumCount" to "0",
                "songCount" to count.toString(),
                "songOffset" to offset.toString(),
            ),
        ).searchResult3.song.map { it.toTrack() }

    /** The self-authenticating, server-transcoded Stream URL for a track (MP3, tunable bitrate). */
    fun streamUrl(trackId: String): String = buildUrl(
        "stream",
        mapOf(
            "id" to trackId,
            "maxBitRate" to bitrateKbps().toString(),
            "format" to STREAM_FORMAT,
        ),
    )

    // --- internals ---------------------------------------------------------

    private suspend fun request(
        endpoint: String,
        extra: Map<String, String> = emptyMap(),
    ): SubsonicResponse = withContext(Dispatchers.IO) {
        val text = http.get(buildUrl(endpoint, extra)).bodyAsText()
        val r = json.decodeFromString(SubsonicEnvelope.serializer(), text).response
        if (r.status != "ok") {
            val e = r.error
            throw SubsonicException(e?.code ?: -1, e?.message ?: "Subsonic '$endpoint' failed")
        }
        r
    }

    private fun buildUrl(endpoint: String, extra: Map<String, String>): String {
        val salt = newSalt()
        val params = linkedMapOf(
            "u" to creds.username,
            "t" to md5(creds.password + salt),
            "s" to salt,
            "v" to PROTOCOL_VERSION,
            "c" to clientName,
            "f" to "json",
        )
        params.putAll(extra)
        val query = params.entries.joinToString("&") { (k, v) -> "${k.enc()}=${v.enc()}" }
        return "$baseUrl/rest/$endpoint?$query"
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun newSalt(): String {
        val alphabet = "abcdef0123456789"
        return buildString { repeat(12) { append(alphabet.random()) } }
    }

    private fun String.enc(): String = URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val PROTOCOL_VERSION = "1.16.1"
        const val STREAM_BITRATE_KBPS = 256
        const val STREAM_FORMAT = "mp3"
    }
}

class SubsonicException(val code: Int, message: String) : RuntimeException(message)

private fun AlbumItem.toAlbum() = Album(id, name, artist, artistId, year, songCount)
private fun SongItem.toTrack() = Track(id, title, artist, album, albumId, track, duration)

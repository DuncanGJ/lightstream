package com.thelightphone.music.library

import com.thelightphone.music.cache.MetadataCacheEntity
import com.thelightphone.music.cache.MetadataDao
import com.thelightphone.music.model.Album
import com.thelightphone.music.model.Artist
import com.thelightphone.music.model.Playlist
import com.thelightphone.music.model.SearchResults
import com.thelightphone.music.model.Track
import com.thelightphone.music.queue.PagedSource
import com.thelightphone.music.queue.Source
import com.thelightphone.music.subsonic.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The one seam for library data — every screen reads the library through here, never through
 * [SubsonicClient] directly, so "is this cached / does it work offline?" is answered in one place:
 *
 * - The browse flows ([artists], [albums], …) are stale-while-revalidate: they emit the last
 *   persisted copy immediately (if any), then fetch fresh, persist, and emit again — unless
 *   nothing changed. Offline, the cached copy is all you get and nothing fails. See CONTEXT.md
 *   ("Metadata cache").
 * - [artistAlbumsNow] and [songsPage] are network-first with cache fallback — for decisions and
 *   pages that shouldn't act on stale data but must still work offline.
 * - [search] is live and deliberately uncached: results are query-shaped and change with the
 *   library. Offline it returns null and the UI says so.
 */
class LibraryRepository(
    private val subsonic: SubsonicClient,
    private val dao: MetadataDao,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val albumPageSize: Int = ALBUM_PAGE_SIZE,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun artists(): Flow<List<Artist>> =
        cached("artists", ListSerializer(Artist.serializer())) { subsonic.getArtists() }

    /** Every album, fetched page-by-page until a short page — big libraries aren't truncated. */
    fun albums(): Flow<List<Album>> =
        cached("albums", ListSerializer(Album.serializer())) {
            val all = mutableListOf<Album>()
            while (true) {
                val page = subsonic.getAlbums(size = albumPageSize, offset = all.size)
                all += page
                if (page.size < albumPageSize) break
            }
            all
        }

    fun artistAlbums(artistId: String): Flow<List<Album>> =
        cached("artist-albums/$artistId", ListSerializer(Album.serializer())) {
            subsonic.getArtistAlbums(artistId)
        }

    /**
     * The freshest available album list for an artist: network first (persisting to the Metadata
     * cache, so it feeds [artistAlbums] too), cache fallback offline, null if neither. For
     * decisions that must not act on a stale cache — e.g. Browse's single-album skip, where a
     * stale "one album" would hide an artist's new second album.
     */
    suspend fun artistAlbumsNow(artistId: String): List<Album>? =
        freshest("artist-albums/$artistId", ListSerializer(Album.serializer())) {
            subsonic.getArtistAlbums(artistId)
        }

    fun albumTracks(albumId: String): Flow<List<Track>> =
        cached("album-tracks/$albumId", ListSerializer(Track.serializer())) {
            subsonic.getAlbumTracks(albumId)
        }

    fun playlists(): Flow<List<Playlist>> =
        cached("playlists", ListSerializer(Playlist.serializer())) { subsonic.getPlaylists() }

    fun playlistTracks(playlistId: String): Flow<List<Track>> =
        cached("playlist-tracks/$playlistId", ListSerializer(Track.serializer())) {
            subsonic.getPlaylistTracks(playlistId)
        }

    /** Live search3 — the escape hatch alongside Browse. Uncached by policy; null offline. */
    suspend fun search(query: String): SearchResults? =
        withContext(Dispatchers.IO) { runCatching { subsonic.search(query) }.getOrNull() }

    /**
     * One page of the whole song library, network-first with cache fallback: pages you have
     * scrolled remain browsable offline. Null = unavailable right now (offline and never seen).
     */
    suspend fun songsPage(offset: Int, count: Int): List<Track>? =
        freshest("songs/$offset/$count", ListSerializer(Track.serializer())) {
            subsonic.getSongs(offset, count)
        }

    /** The whole library A→Z as a lazily-paged Source — the backing for the Songs page AND its queue. */
    fun songs(): Source =
        PagedSource("Songs", pageSize = SONGS_PAGE_SIZE) { offset, count -> songsPage(offset, count) }

    private fun <T> cached(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): Flow<T> =
        flow {
            val cachedJson = dao.get(key)?.json
            if (cachedJson != null) {
                runCatching { json.decodeFromString(serializer, cachedJson) }
                    .getOrNull()
                    ?.let { emit(it) }
            }
            val fresh = runCatching { fetch() }.getOrNull() ?: return@flow
            val freshJson = json.encodeToString(serializer, fresh)
            if (freshJson == cachedJson) return@flow // unchanged: don't churn the UI
            dao.upsert(MetadataCacheEntity(key, freshJson, now()))
            emit(fresh)
        }.flowOn(Dispatchers.IO)

    /** Network-first: persist and return fresh, else fall back to the cache, else null. */
    private suspend fun <T> freshest(key: String, serializer: KSerializer<T>, fetch: suspend () -> T): T? =
        withContext(Dispatchers.IO) {
            val fresh = runCatching { fetch() }.getOrNull()
            if (fresh != null) {
                dao.upsert(MetadataCacheEntity(key, json.encodeToString(serializer, fresh), now()))
                fresh
            } else {
                dao.get(key)?.json?.let { c -> runCatching { json.decodeFromString(serializer, c) }.getOrNull() }
            }
        }

    private companion object {
        const val ALBUM_PAGE_SIZE = 500
        const val SONGS_PAGE_SIZE = 100
    }
}

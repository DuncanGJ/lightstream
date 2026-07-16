package com.thelightphone.music.library

import com.thelightphone.music.cache.MetadataCacheEntity
import com.thelightphone.music.cache.MetadataDao
import com.thelightphone.music.model.Album
import com.thelightphone.music.model.Artist
import com.thelightphone.music.model.Playlist
import com.thelightphone.music.model.Track
import com.thelightphone.music.subsonic.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Stale-while-revalidate metadata for browsing: every flow emits the last persisted copy
 * immediately (if any), then fetches fresh, persists, and emits again — unless nothing changed.
 * Offline, the cached copy is all you get and nothing fails. Browsing stays seamless no matter
 * how slow the link is; see CONTEXT.md ("Metadata cache").
 */
class LibraryRepository(
    private val subsonic: SubsonicClient,
    private val dao: MetadataDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun artists(): Flow<List<Artist>> =
        cached("artists", ListSerializer(Artist.serializer())) { subsonic.getArtists() }

    fun albums(): Flow<List<Album>> =
        cached("albums", ListSerializer(Album.serializer())) { subsonic.getAlbums(size = 500) }

    fun artistAlbums(artistId: String): Flow<List<Album>> =
        cached("artist-albums/$artistId", ListSerializer(Album.serializer())) {
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
}

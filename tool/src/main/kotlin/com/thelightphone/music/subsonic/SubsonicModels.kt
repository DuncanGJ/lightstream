package com.thelightphone.music.subsonic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models for the Subsonic / OpenSubsonic JSON API. Every response is wrapped in a
 * "subsonic-response" envelope. The client decodes with ignoreUnknownKeys = true, so we model
 * only the fields the tool reads; defaults keep decoding resilient across server versions.
 */

@Serializable
data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse = SubsonicResponse(),
)

@Serializable
data class SubsonicResponse(
    val status: String = "failed",
    val version: String = "",
    val error: SubsonicError? = null,
    val artists: ArtistsID3? = null,
    val artist: ArtistWithAlbumsID3? = null,
    val album: AlbumWithSongsID3? = null,
    val albumList2: AlbumList2 = AlbumList2(),
    val searchResult3: SearchResult3 = SearchResult3(),
    val playlists: Playlists = Playlists(),
    val playlist: PlaylistWithSongs? = null,
)

@Serializable
data class SubsonicError(val code: Int = 0, val message: String = "")

@Serializable
data class ArtistsID3(val index: List<IndexID3> = emptyList())

@Serializable
data class IndexID3(val name: String = "", val artist: List<ArtistItem> = emptyList())

@Serializable
data class ArtistItem(
    val id: String = "",
    val name: String = "",
    val albumCount: Int = 0,
)

@Serializable
data class ArtistWithAlbumsID3(
    val id: String = "",
    val name: String = "",
    val album: List<AlbumItem> = emptyList(),
)

@Serializable
data class AlbumItem(
    val id: String = "",
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val year: Int? = null,
    val songCount: Int = 0,
)

@Serializable
data class AlbumWithSongsID3(
    val id: String = "",
    val name: String = "",
    val artist: String? = null,
    val song: List<SongItem> = emptyList(),
)

@Serializable
data class AlbumList2(val album: List<AlbumItem> = emptyList())

@Serializable
data class SearchResult3(
    val artist: List<ArtistItem> = emptyList(),
    val album: List<AlbumItem> = emptyList(),
    val song: List<SongItem> = emptyList(),
)

@Serializable
data class Playlists(val playlist: List<PlaylistItem> = emptyList())

@Serializable
data class PlaylistItem(
    val id: String = "",
    val name: String = "",
    val songCount: Int = 0,
)

@Serializable
data class PlaylistWithSongs(
    val id: String = "",
    val name: String = "",
    val entry: List<SongItem> = emptyList(),
)

@Serializable
data class SongItem(
    val id: String = "",
    val title: String = "",
    val artist: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val track: Int? = null,
    val duration: Int? = null,
)

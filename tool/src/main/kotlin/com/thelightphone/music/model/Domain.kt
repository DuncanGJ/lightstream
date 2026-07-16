package com.thelightphone.music.model

import kotlinx.serialization.Serializable

/**
 * The tool's own domain vocabulary, decoupled from the Subsonic wire shape. Everything above the
 * Subsonic client (queue, cache, playback, UI) speaks these types, so the concrete server/API can
 * change without rippling outward. Serializable so the metadata cache can persist them as JSON.
 * See CONTEXT.md.
 */

@Serializable
data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int,
)

@Serializable
data class Album(
    val id: String,
    val name: String,
    val artist: String?,
    val artistId: String?,
    val year: Int?,
    val songCount: Int,
)

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumId: String?,
    val trackNumber: Int?,
    val durationSec: Int?,
)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val songCount: Int,
)

data class SearchResults(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
)

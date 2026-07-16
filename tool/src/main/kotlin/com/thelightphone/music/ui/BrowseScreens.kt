package com.thelightphone.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.library.LibraryRepository
import com.thelightphone.music.model.Album
import com.thelightphone.music.model.Artist
import com.thelightphone.music.model.Playlist
import com.thelightphone.music.model.Track
import com.thelightphone.music.playback.Player
import com.thelightphone.music.queue.ListSource
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/** Where tapping an artist should go — decided on the freshest available album list. */
sealed interface ArtistDestination {
    /** The artist has exactly one album: skip the album level, open its tracks directly. */
    data class SingleAlbum(val album: Album) : ArtistDestination
    data class Albums(val artist: Artist) : ArtistDestination
}

class ArtistsViewModel(private val library: LibraryRepository) : LightViewModel<Unit>() {
    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        viewModelScope.launch {
            library.artists()
                .onCompletion { _loaded.value = true }
                .collect {
                    _artists.value = it
                    _loaded.value = true
                }
        }
    }

    fun setFilter(value: String) { _filter.value = value }

    /**
     * Decide the tap-through on [LibraryRepository.artistAlbumsNow] — never the stale-while-
     * revalidate cache, whose first emission could hide an artist's newly-added second album.
     */
    suspend fun destinationFor(artist: Artist): ArtistDestination {
        val only = library.artistAlbumsNow(artist.id)?.singleOrNull()
        return if (only != null) ArtistDestination.SingleAlbum(only) else ArtistDestination.Albums(artist)
    }
}

class ArtistsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ArtistsViewModel>(sealedActivity) {

    private val listState = retainedListState()

    override val viewModelClass: Class<ArtistsViewModel>
        get() = ArtistsViewModel::class.java

    override fun createViewModel() = ArtistsViewModel(MusicApp.requireSession().library)

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val filter by viewModel.filter.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val scope = rememberCoroutineScope()
        val shown = if (filter.isBlank()) artists else artists.filter { it.name.contains(filter, ignoreCase = true) }

        // Decided at tap time, not on compose, so back-navigation doesn't bounce forward again.
        fun openArtist(artist: Artist) {
            scope.launch {
                when (val dest = viewModel.destinationFor(artist)) {
                    is ArtistDestination.SingleAlbum ->
                        navigateTo({ TrackListScreen(it, dest.album.name, TrackListRequest.Album(dest.album.id)) })
                    is ArtistDestination.Albums ->
                        navigateTo({ AlbumListScreen(it, dest.artist.name, dest.artist.id) })
                }
            }
        }

        MusicScaffold(
            title = if (filter.isBlank()) "Artists" else "Artists · $filter",
            onBack = { goBack() },
            onSearch = { editText("Filter Artists", filter) { viewModel.setFilter(it) } },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            if (!loaded) {
                LightText("Loading…", variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.padding(vertical = 16.dp))
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(shown) { artist ->
                        LightText(
                            text = artist.name,
                            variant = LightTextVariant.Subheading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { openArtist(artist) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

class AlbumListViewModel(
    library: LibraryRepository,
    artistId: String?,
) : LightViewModel<Unit>() {
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    init {
        viewModelScope.launch {
            val albums = if (artistId == null) library.albums() else library.artistAlbums(artistId)
            albums.collect { _albums.value = it }
        }
    }

    fun setFilter(value: String) { _filter.value = value }
}

class AlbumListScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val artistId: String?,
) : LightScreen<Unit, AlbumListViewModel>(sealedActivity) {

    private val listState = retainedListState()

    override val viewModelClass: Class<AlbumListViewModel>
        get() = AlbumListViewModel::class.java

    override fun createViewModel() = AlbumListViewModel(MusicApp.requireSession().library, artistId)

    @Composable
    override fun Content() {
        val albums by viewModel.albums.collectAsState()
        val filter by viewModel.filter.collectAsState()
        val shown = if (filter.isBlank()) albums else albums.filter { it.name.contains(filter, ignoreCase = true) }

        MusicScaffold(
            title = title,
            onBack = { goBack() },
            onSearch = { editText("Filter Albums", filter) { viewModel.setFilter(it) } },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(shown) { album ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { navigateTo({ TrackListScreen(it, album.name, TrackListRequest.Album(album.id)) }) }
                            .padding(vertical = 12.dp),
                    ) {
                        LightText(album.name, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        album.artist?.let { LightText(it, variant = LightTextVariant.Detail, lighten = true) }
                    }
                }
            }
        }
    }
}

sealed interface TrackListRequest {
    data class Album(val id: String) : TrackListRequest
    data class PlaylistTracks(val id: String) : TrackListRequest
}

class TrackListViewModel(
    library: LibraryRepository,
    private val player: Player,
    private val title: String,
    request: TrackListRequest,
) : LightViewModel<Unit>() {
    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    init {
        viewModelScope.launch {
            val flow = when (request) {
                is TrackListRequest.Album -> library.albumTracks(request.id)
                is TrackListRequest.PlaylistTracks -> library.playlistTracks(request.id)
            }
            flow.collect { _tracks.value = it }
        }
    }

    fun play(index: Int) = player.playFrom(ListSource(title, _tracks.value), index)
    fun queue(track: Track) = player.addToQueue(track)
}

class TrackListScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val request: TrackListRequest,
) : LightScreen<Unit, TrackListViewModel>(sealedActivity) {

    private val listState = retainedListState()

    override val viewModelClass: Class<TrackListViewModel>
        get() = TrackListViewModel::class.java

    override fun createViewModel(): TrackListViewModel {
        val session = MusicApp.requireSession()
        return TrackListViewModel(session.library, session.player, title, request)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()

        MusicScaffold(
            title = title,
            onBack = { goBack() },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(tracks) { index, track ->
                    TrackRow(
                        track = track,
                        onPlay = { viewModel.play(index) },
                        onQueue = { viewModel.queue(track) },
                    )
                }
            }
        }
    }
}

class PlaylistsViewModel(library: LibraryRepository) : LightViewModel<Unit>() {
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    init {
        viewModelScope.launch { library.playlists().collect { _playlists.value = it } }
    }
}

class PlaylistsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, PlaylistsViewModel>(sealedActivity) {

    private val listState = retainedListState()

    override val viewModelClass: Class<PlaylistsViewModel>
        get() = PlaylistsViewModel::class.java

    override fun createViewModel() = PlaylistsViewModel(MusicApp.requireSession().library)

    @Composable
    override fun Content() {
        val playlists by viewModel.playlists.collectAsState()

        MusicScaffold(
            title = "Playlists",
            onBack = { goBack() },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(playlists) { playlist ->
                    LightText(
                        text = playlist.name,
                        variant = LightTextVariant.Subheading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .lightClickable { navigateTo({ TrackListScreen(it, playlist.name, TrackListRequest.PlaylistTracks(playlist.id)) }) }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

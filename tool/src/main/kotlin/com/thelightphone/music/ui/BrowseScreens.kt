package com.thelightphone.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.model.Album
import com.thelightphone.music.model.Artist
import com.thelightphone.music.model.Playlist
import com.thelightphone.music.model.Track
import com.thelightphone.music.queue.ListSource
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

class ArtistsViewModel : LightViewModel<Unit>() {
    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        viewModelScope.launch {
            val library = MusicApp.library.filterNotNull().first()
            library.artists()
                .onCompletion { _loaded.value = true }
                .collect {
                    _artists.value = it
                    _loaded.value = true
                }
        }
    }

    fun setFilter(value: String) { _filter.value = value }
}

class ArtistsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, ArtistsViewModel>(sealedActivity) {

    private val listState = LazyListState() // instance-held: survives back-navigation

    override val viewModelClass: Class<ArtistsViewModel>
        get() = ArtistsViewModel::class.java

    override fun createViewModel() = ArtistsViewModel()

    @Composable
    override fun Content() {
        val artists by viewModel.artists.collectAsState()
        val filter by viewModel.filter.collectAsState()
        val loaded by viewModel.loaded.collectAsState()
        val scope = rememberCoroutineScope()
        val shown = if (filter.isBlank()) artists else artists.filter { it.name.contains(filter, ignoreCase = true) }

        // Single-album artists skip the album level and open the tracks directly (decided at tap
        // time, not on compose, so back-navigation doesn't bounce forward again).
        fun openArtist(artist: Artist) {
            scope.launch {
                val albums = MusicApp.library.value
                    ?.let { lib -> runCatching { lib.artistAlbums(artist.id).firstOrNull() }.getOrNull() }
                val only = albums?.singleOrNull()
                if (only != null) {
                    navigateTo({ TrackListScreen(it, only.name, TrackListRequest.Album(only.id)) })
                } else {
                    navigateTo({ AlbumListScreen(it, artist.name, artist.id) })
                }
            }
        }

        MusicScaffold(
            title = if (filter.isBlank()) "Artists" else "Artists · $filter",
            onBack = { goBack() },
            onSearch = {
                navigateTo(
                    screenFactory = { TextEntryScreen(it, "Filter Artists", filter) },
                    resultCallback = { viewModel.setFilter(it) },
                )
            },
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

class AlbumListViewModel(private val artistId: String?) : LightViewModel<Unit>() {
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()
    private val _filter = MutableStateFlow("")
    val filter: StateFlow<String> = _filter.asStateFlow()

    init {
        viewModelScope.launch {
            val library = MusicApp.library.filterNotNull().first()
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

    private val listState = LazyListState() // instance-held: survives back-navigation

    override val viewModelClass: Class<AlbumListViewModel>
        get() = AlbumListViewModel::class.java

    override fun createViewModel() = AlbumListViewModel(artistId)

    @Composable
    override fun Content() {
        val albums by viewModel.albums.collectAsState()
        val filter by viewModel.filter.collectAsState()
        val shown = if (filter.isBlank()) albums else albums.filter { it.name.contains(filter, ignoreCase = true) }

        MusicScaffold(
            title = title,
            onBack = { goBack() },
            onSearch = {
                navigateTo(
                    screenFactory = { TextEntryScreen(it, "Filter Albums", filter) },
                    resultCallback = { viewModel.setFilter(it) },
                )
            },
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

class TrackListScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val request: TrackListRequest,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private val listState = LazyListState() // instance-held: survives back-navigation

    @Composable
    override fun Content() {
        val library by MusicApp.library.collectAsState()
        val player by MusicApp.player.collectAsState()
        var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }

        LaunchedEffect(library) {
            val lib = library ?: return@LaunchedEffect
            val flow = when (val r = request) {
                is TrackListRequest.Album -> lib.albumTracks(r.id)
                is TrackListRequest.PlaylistTracks -> lib.playlistTracks(r.id)
            }
            flow.collect { tracks = it }
        }

        MusicScaffold(
            title = title,
            onBack = { goBack() },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(tracks) { index, track ->
                    TrackRow(
                        track = track,
                        onPlay = { player?.playFrom(ListSource(title, tracks), index) },
                        onQueue = { player?.addToQueue(track) },
                    )
                }
            }
        }
    }
}

class PlaylistsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val listState = LazyListState() // instance-held: survives back-navigation

    @Composable
    override fun Content() {
        val library by MusicApp.library.collectAsState()
        var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

        LaunchedEffect(library) {
            val lib = library ?: return@LaunchedEffect
            lib.playlists().collect { playlists = it }
        }

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

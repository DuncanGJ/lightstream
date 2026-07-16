package com.thelightphone.music.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.library.LibraryRepository
import com.thelightphone.music.model.SearchResults
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
import kotlinx.coroutines.launch

class SearchViewModel(
    private val library: LibraryRepository,
    private val player: Player,
) : LightViewModel<Unit>() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<SearchResults?>(null)
    val results: StateFlow<SearchResults?> = _results.asStateFlow()
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    fun search(q: String) {
        _query.value = q
        if (q.isBlank()) return
        viewModelScope.launch {
            val r = library.search(q) // Library policy: live, uncached; null offline
            _results.value = r
            _failed.value = r == null
        }
    }

    fun playTrack(index: Int) {
        val r = _results.value ?: return
        player.playFrom(ListSource("Search: ${_query.value}", r.tracks), index)
    }

    fun queue(track: Track) = player.addToQueue(track)
}

/** Global search via the Library — the escape hatch alongside iPod-style drill-down. */
class SearchScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SearchViewModel>(sealedActivity) {

    private val listState = retainedListState()

    override val viewModelClass: Class<SearchViewModel>
        get() = SearchViewModel::class.java

    override fun createViewModel(): SearchViewModel {
        val session = MusicApp.requireSession()
        return SearchViewModel(session.library, session.player)
    }

    @Composable
    override fun Content() {
        val query by viewModel.query.collectAsState()
        val results by viewModel.results.collectAsState()
        val failed by viewModel.failed.collectAsState()

        MusicScaffold(
            title = "Search",
            onBack = { goBack() },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            TextEntryField(
                label = "Search",
                value = query,
                placeholder = "artist, album, song",
                modifier = Modifier.padding(top = 8.dp),
                onResult = { viewModel.search(it) },
            )

            if (failed) {
                LightText(
                    text = "Search needs the server — try again online.",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            val r = results ?: return@MusicScaffold
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (r.artists.isNotEmpty()) {
                    item { SectionHeader("Artists") }
                    items(r.artists) { a ->
                        LightText(
                            text = a.name,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { navigateTo({ AlbumListScreen(it, a.name, a.id) }) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
                if (r.albums.isNotEmpty()) {
                    item { SectionHeader("Albums") }
                    items(r.albums) { al ->
                        LightText(
                            text = al.name,
                            variant = LightTextVariant.Copy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .lightClickable { navigateTo({ TrackListScreen(it, al.name, TrackListRequest.Album(al.id)) }) }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
                if (r.tracks.isNotEmpty()) {
                    item { SectionHeader("Songs") }
                    itemsIndexed(r.tracks) { index, t ->
                        TrackRow(
                            track = t,
                            onPlay = { viewModel.playTrack(index) },
                            onQueue = { viewModel.queue(t) },
                        )
                    }
                }
            }
        }
    }
}

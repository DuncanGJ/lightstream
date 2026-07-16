package com.thelightphone.music.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.model.SearchResults
import com.thelightphone.music.queue.ListSource
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : LightViewModel<Unit>() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _results = MutableStateFlow<SearchResults?>(null)
    val results: StateFlow<SearchResults?> = _results.asStateFlow()

    fun search(q: String) {
        _query.value = q
        viewModelScope.launch {
            _results.value = MusicApp.subsonic.value?.let { runCatching { it.search(q) }.getOrNull() }
        }
    }
}

/** Global search via Subsonic search3 — the escape hatch alongside iPod-style drill-down. */
class SearchScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SearchViewModel>(sealedActivity) {

    private val listState = LazyListState() // instance-held: survives back-navigation

    override val viewModelClass: Class<SearchViewModel>
        get() = SearchViewModel::class.java

    override fun createViewModel() = SearchViewModel()

    @Composable
    override fun Content() {
        val player by MusicApp.player.collectAsState()
        val query by viewModel.query.collectAsState()
        val results by viewModel.results.collectAsState()

        MusicScaffold(
            title = "Search",
            onBack = { goBack() },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            LightTextField(
                label = "Search",
                value = query,
                placeholder = "artist, album, song",
                onClick = {
                    navigateTo(
                        screenFactory = { TextEntryScreen(it, "Search", query) },
                        resultCallback = { viewModel.search(it) },
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            )

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
                            onPlay = { player?.playFrom(ListSource("Search: $query", r.tracks), index) },
                            onQueue = { player?.addToQueue(t) },
                        )
                    }
                }
            }
        }
    }
}

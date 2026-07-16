package com.thelightphone.music.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.library.LibraryRepository
import com.thelightphone.music.model.Track
import com.thelightphone.music.playback.Player
import com.thelightphone.music.queue.SourceWindow
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The whole library A→Z. One Library songs Source backs BOTH the visible list (via [SourceWindow])
 * and the queue: playing the midpoint queues the rest of the library without materializing it.
 */
class SongsViewModel(
    library: LibraryRepository,
    private val player: Player,
) : LightViewModel<Unit>() {
    private val source = library.songs() // page cache lives here, retained across back-navigation
    private val window = SourceWindow(source)

    val tracks: StateFlow<List<Track>> = window.items
    val endReached: StateFlow<Boolean> = window.endReached

    /** Keep the realized window ahead of the scroll position. */
    fun ensure(count: Int) {
        viewModelScope.launch { window.ensure(count) }
    }

    fun play(index: Int) = player.playFrom(source, index)
    fun queue(track: Track) = player.addToQueue(track)
}

class SongsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SongsViewModel>(sealedActivity) {

    private val listState = retainedListState()

    override val viewModelClass: Class<SongsViewModel>
        get() = SongsViewModel::class.java

    override fun createViewModel(): SongsViewModel {
        val session = MusicApp.requireSession()
        return SongsViewModel(session.library, session.player)
    }

    @Composable
    override fun Content() {
        val tracks by viewModel.tracks.collectAsState()
        val endReached by viewModel.endReached.collectAsState()

        // The realization logic lives (tested) in SourceWindow; this only reports scroll position.
        LaunchedEffect(Unit) {
            snapshotFlow { listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size }
                .collect { lastVisible -> viewModel.ensure(lastVisible + PRELOAD) }
        }

        MusicScaffold(
            title = "Songs",
            onBack = { goBack() },
            onOpenNowPlaying = { navigateTo({ NowPlayingScreen(it) }) },
        ) {
            if (tracks.isEmpty()) {
                LightText(
                    text = if (endReached) "No songs." else "Loading…",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
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

    private companion object {
        const val PRELOAD = 40
    }
}

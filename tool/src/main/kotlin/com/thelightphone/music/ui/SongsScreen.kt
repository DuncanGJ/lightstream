package com.thelightphone.music.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.model.Track
import com.thelightphone.music.queue.PagedSource
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * The whole library, A→Z, backed by [PagedSource] — rows realize as you scroll, and playing the
 * midpoint queues the rest of the library without materializing it (the Source IS the queue).
 */
class SongsScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val listState = LazyListState() // instance-held: survives back-navigation
    private var source: PagedSource? = null // instance-held: page cache survives too

    @Composable
    override fun Content() {
        val subsonic by MusicApp.subsonic.collectAsState()
        val player by MusicApp.player.collectAsState()
        var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
        var endReached by remember { mutableStateOf(false) }

        val s = subsonic
        if (source == null && s != null) {
            source = PagedSource("Songs", pageSize = PAGE) { offset, count -> s.getSongs(offset, count) }
        }
        val src = source

        // Keep the realized list ahead of the scroll position (rolling, page-cached).
        LaunchedEffect(src) {
            if (src == null) return@LaunchedEffect
            snapshotFlow { listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size }
                .collect { lastVisible ->
                    while (!endReached && tracks.size < lastVisible + PRELOAD) {
                        val from = tracks.size
                        val batch = (from until from + PAGE).mapNotNull { src.get(it) }
                        if (batch.isEmpty()) {
                            endReached = true
                        } else {
                            tracks = tracks + batch
                            if (batch.size < PAGE) endReached = true
                        }
                    }
                }
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
                        onPlay = { src?.let { player?.playFrom(it, index) } },
                        onQueue = { player?.addToQueue(track) },
                    )
                }
            }
        }
    }

    private companion object {
        const val PAGE = 100
        const val PRELOAD = 40
    }
}

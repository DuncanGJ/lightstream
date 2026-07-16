package com.thelightphone.music.ui

import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.queue.Queue
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.launch

/**
 * The current queue: now playing + the upcoming window (hand-arranged items bold, Source-derived
 * lightened). Per row: ▲/▼ move the song (insert semantics — the touched prefix materializes into
 * the overlay), ✕ removes. Everything past the touched window stays lazy (see Queue).
 */
class QueueScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val listState = LazyListState() // instance-held: survives back-navigation

    @Composable
    override fun Content() {
        val player by MusicApp.player.collectAsState()
        val scope = rememberCoroutineScope()
        var version by remember { mutableStateOf(0) }
        var items by remember { mutableStateOf<List<Queue.UpcomingItem>>(emptyList()) }

        MusicScaffold(title = "Queue", onBack = { goBack() }) {
            val p = player
            if (p == null) {
                LightText("Nothing queued.", variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.padding(vertical = 16.dp))
                return@MusicScaffold
            }
            val track by p.current.collectAsState()
            LaunchedEffect(version, track) { items = p.upcomingItems(WINDOW) }

            track?.let {
                SectionHeader("Now")
                LightText(it.title, variant = LightTextVariant.Copy, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SectionHeader("Up next")
            if (items.isEmpty()) {
                LightText("Nothing queued.", variant = LightTextVariant.Copy, lighten = true)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(items) { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (index == 0 && track == null) {
                            // Nothing playing: the queue head gets an explicit play affordance.
                            LightIcon(
                                icon = LightIcons.PLAY,
                                contentDescription = "play queue",
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .lightClickable { p.playNextInQueue() },
                            )
                        }
                        LightText(
                            text = item.track.title,
                            variant = LightTextVariant.Copy,
                            lighten = !item.fromOverlay,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        LightIcon(
                            icon = LightIcons.ARROW_DOWN,
                            contentDescription = "move up",
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .rotate(180f)
                                .lightClickable { scope.launch { p.moveUpcoming(index, index - 1); version++ } },
                        )
                        LightIcon(
                            icon = LightIcons.ARROW_DOWN,
                            contentDescription = "move down",
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .lightClickable { scope.launch { p.moveUpcoming(index, index + 1); version++ } },
                        )
                        LightIcon(
                            icon = LightIcons.CLOSE,
                            contentDescription = "remove",
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .lightClickable { scope.launch { p.removeUpcoming(index); version++ } },
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val WINDOW = 50
    }
}

package com.thelightphone.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.playback.PlaybackStatus
import com.thelightphone.music.queue.RepeatMode
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.lightClickable

class NowPlayingScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val player = MusicApp.requireSession().player

    @Composable
    override fun Content() {
        MusicScaffold(
            title = "Now Playing",
            onBack = { goBack() },
            onQueue = { navigateTo({ QueueScreen(it) }) },
        ) {
            val track by player.current.collectAsState()
            val playback by player.state.collectAsState()
            val repeat by player.repeatMode.collectAsState()

            val t = track
            if (t == null) {
                LightText("Nothing playing.", variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.padding(vertical = 24.dp))
                return@MusicScaffold
            }

            Spacer(Modifier.height(24.dp))
            LightText(t.title, variant = LightTextVariant.Heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
            t.artist?.let { LightText(it, variant = LightTextVariant.Copy, lighten = true, modifier = Modifier.padding(top = 8.dp)) }
            t.album?.let { LightText(it, variant = LightTextVariant.Detail, lighten = true) }

            Spacer(Modifier.height(24.dp))
            LightText(
                text = "${formatTime(playback.positionMs)} / ${formatTime(playback.durationMs)}",
                variant = LightTextVariant.Detail,
                lighten = true,
            )

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(LightIcons.REWIND, modifier = Modifier.lightClickable { player.skipPrevious() })
                LightIcon(
                    icon = if (playback.status == PlaybackStatus.PLAYING) LightIcons.PAUSE else LightIcons.PLAY,
                    size = 3f,
                    modifier = Modifier.lightClickable { player.togglePlayPause() },
                )
                LightIcon(LightIcons.FAST_FORWARD, modifier = Modifier.lightClickable { player.skipNext() })
                LightIcon(
                    icon = LightIcons.STOP,
                    contentDescription = "stop (queue is kept)",
                    modifier = Modifier.lightClickable { player.stop() },
                )
            }

            val repeatLabel = when (repeat) {
                RepeatMode.OFF -> "Repeat: Off"
                RepeatMode.ALL -> "Repeat: All"
                RepeatMode.ONE -> "Repeat: One"
            }
            LightText(
                text = repeatLabel,
                variant = LightTextVariant.Detail,
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { player.cycleRepeat() }
                    .padding(vertical = 16.dp),
            )
        }
    }
}

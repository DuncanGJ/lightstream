package com.thelightphone.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import com.thelightphone.music.playback.PlaybackError
import com.thelightphone.music.playback.PlaybackErrorKind
import com.thelightphone.music.playback.PlaybackStatus
import com.thelightphone.music.queue.RepeatMode
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTouchableProgressBar
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
            // Scrubber: drag anywhere on the bar to seek. Until the duration resolves there is
            // nothing to scrub into, so it reads as empty and a drag lands on zero.
            val duration = playback.durationMs
            Box(Modifier.fillMaxWidth()) {
                LightTouchableProgressBar(
                    colors = LightThemeTokens.colors,
                    progress = if (duration > 0) playback.positionMs.toFloat() / duration else 0f,
                    onValueChange = { fraction -> player.seekTo((fraction * duration).toInt()) },
                )
            }
            LightText(
                text = "${formatTime(playback.positionMs)} / ${formatTime(duration)}",
                variant = LightTextVariant.Detail,
                lighten = true,
                modifier = Modifier.padding(top = 8.dp),
            )

            playback.error?.let { error ->
                LightText(
                    text = error.userMessage(),
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            Spacer(Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(LightIcons.REWIND, contentDescription = "previous", modifier = Modifier.lightClickable { player.skipPrevious() })
                LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, contentDescription = "back 15 seconds", modifier = Modifier.lightClickable { player.skipBack() })
                LightIcon(
                    icon = if (playback.status == PlaybackStatus.PLAYING) LightIcons.PAUSE else LightIcons.PLAY,
                    size = 3f,
                    modifier = Modifier.lightClickable { player.togglePlayPause() },
                )
                LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, contentDescription = "forward 15 seconds", modifier = Modifier.lightClickable { player.skipForward() })
                LightIcon(LightIcons.FAST_FORWARD, contentDescription = "next", modifier = Modifier.lightClickable { player.skipNext() })
            }

            val repeatLabel = when (repeat) {
                RepeatMode.OFF -> "Repeat: Off"
                RepeatMode.ALL -> "Repeat: All"
                RepeatMode.ONE -> "Repeat: One"
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = repeatLabel,
                    variant = LightTextVariant.Detail,
                    modifier = Modifier.weight(1f).lightClickable { player.cycleRepeat() },
                )
                LightIcon(
                    icon = LightIcons.STOP,
                    contentDescription = "stop (queue is kept)",
                    modifier = Modifier.lightClickable { player.stop() },
                )
            }
        }
    }
}

/** Product copy per failure kind — says what happened and what the buttons on this screen can do about it. */
internal fun PlaybackError.userMessage(): String = when (kind) {
    PlaybackErrorKind.SOURCE -> "Couldn't fetch this track. Check your connection, then press play to retry or skip ahead."
    PlaybackErrorKind.UNSUPPORTED -> "This track's format can't be played on this phone. Skip ahead."
    PlaybackErrorKind.OUTPUT -> "The speaker couldn't be opened. Press play to retry."
    PlaybackErrorKind.UNAVAILABLE -> "Playback is unavailable right now. Close the tool fully and reopen it to reconnect."
    PlaybackErrorKind.UNKNOWN -> "Playback failed ($diagnostic). Press play to retry or skip ahead."
}

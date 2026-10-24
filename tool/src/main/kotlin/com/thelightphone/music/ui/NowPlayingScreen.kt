package com.thelightphone.music.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.art.AlbumArt
import com.thelightphone.music.playback.PlaybackError
import com.thelightphone.music.playback.PlaybackErrorKind
import com.thelightphone.music.playback.PlaybackStatus
import com.thelightphone.music.playback.showsPause
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One centered column, top to bottom: dithered album art (ADR-0005), title, artist, album, the
 * scrubber with times, then transport. Stop grays the art and title; the art stays, because the
 * track is still current.
 */
class NowPlayingScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    private val session = MusicApp.requireSession()
    private val player = session.player

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
            val stopped = playback.status == PlaybackStatus.STOPPED

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The art takes whatever height is left after everything below it is laid out, so
                // the transport is never pushed off a short screen; the image picks the largest
                // whole-pixel scale that fits that slot.
                Spacer(Modifier.height(8.dp))
                AlbumArtImage(
                    coverArtId = t.coverArt,
                    dimmed = stopped,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.height(12.dp))
                LightText(t.title, variant = LightTextVariant.Subheading, lighten = stopped, maxLines = 1, overflow = TextOverflow.Ellipsis, align = TextAlign.Center)
                t.artist?.let { LightText(it, variant = LightTextVariant.Detail, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis, align = TextAlign.Center, modifier = Modifier.padding(top = 4.dp)) }
                t.album?.let { LightText(it, variant = LightTextVariant.Fine, lighten = true, maxLines = 1, overflow = TextOverflow.Ellipsis, align = TextAlign.Center) }

                Spacer(Modifier.height(12.dp))
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
                    text = if (stopped) "Stopped · ${formatTime(duration)}" else "${formatTime(playback.positionMs)} / ${formatTime(duration)}",
                    variant = LightTextVariant.Fine,
                    lighten = true,
                    modifier = Modifier.padding(top = 6.dp),
                )

                playback.error?.let { error ->
                    LightText(
                        text = error.userMessage(),
                        variant = LightTextVariant.Detail,
                        align = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LightIcon(LightIcons.REWIND, size = TRANSPORT_ICON, contentDescription = "previous", modifier = Modifier.lightClickable { player.skipPrevious() })
                    LightIcon(LightIcons.SKIP_BACKWARD_FIFTEEN, size = TRANSPORT_ICON, contentDescription = "back 15 seconds", modifier = Modifier.lightClickable { player.skipBack() })
                    LightIcon(
                        icon = if (playback.status.showsPause()) LightIcons.PAUSE else LightIcons.PLAY,
                        size = PLAY_ICON,
                        modifier = Modifier.lightClickable { player.togglePlayPause() },
                    )
                    LightIcon(LightIcons.SKIP_FORWARD_FIFTEEN, size = TRANSPORT_ICON, contentDescription = "forward 15 seconds", modifier = Modifier.lightClickable { player.skipForward() })
                    LightIcon(LightIcons.FAST_FORWARD, size = TRANSPORT_ICON, contentDescription = "next", modifier = Modifier.lightClickable { player.skipNext() })
                }
            }

            val repeatLabel = when (repeat) {
                RepeatMode.OFF -> "Repeat: Off"
                RepeatMode.ALL -> "Repeat: All"
                RepeatMode.ONE -> "Repeat: One"
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightText(
                    text = repeatLabel,
                    variant = LightTextVariant.Fine,
                    modifier = Modifier.weight(1f).lightClickable { player.cycleRepeat() },
                )
                LightIcon(
                    icon = LightIcons.STOP,
                    size = TRANSPORT_ICON,
                    contentDescription = "stop (parks the track at the start)",
                    modifier = Modifier.lightClickable { player.stop() },
                )
            }
        }
    }

    /**
     * The dithered cover at the largest whole-pixel multiple of its 240 px that fits the slot it is
     * given (2× when there is room, else 1×, else shrink-to-fit), sampled nearest-neighbour so each
     * dot stays a crisp square. No art yet leaves an empty square of the same size, so nothing jumps.
     */
    @Composable
    private fun AlbumArtImage(coverArtId: String?, dimmed: Boolean, modifier: Modifier = Modifier) {
        var image by remember(coverArtId) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(coverArtId) {
            image = coverArtId?.let { id ->
                session.art.png(id)?.let { png ->
                    withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }
                }
            }
        }
        BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val base = AlbumArt.ART_SIZE_PX
            val roomPx = with(density) { minOf(maxWidth.toPx(), maxHeight.toPx()) }.toInt()
            val sidePx = when {
                roomPx >= base * AlbumArt.ART_DISPLAY_SCALE -> base * AlbumArt.ART_DISPLAY_SCALE
                roomPx >= base -> base
                else -> roomPx.coerceAtLeast(0)
            }
            val side = with(density) { sidePx.toDp() }
            Box(Modifier.size(side).alpha(if (dimmed) 0.4f else 1f), contentAlignment = Alignment.Center) {
                image?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "album art",
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.size(side),
                    )
                }
            }
        }
    }
}

/** Transport icon sizes in SDK grid units (SDK default is 2; the play button was 3). */
private const val TRANSPORT_ICON = 1.5f
private const val PLAY_ICON = 2.25f

/** Product copy per failure kind — says what happened and what the buttons on this screen can do about it. */
internal fun PlaybackError.userMessage(): String = when (kind) {
    PlaybackErrorKind.SOURCE -> "Couldn't fetch this track. Check your connection, then press play to retry or skip ahead."
    PlaybackErrorKind.UNSUPPORTED -> "This track's format can't be played on this phone. Skip ahead."
    PlaybackErrorKind.OUTPUT -> "The speaker couldn't be opened. Press play to retry."
    PlaybackErrorKind.UNAVAILABLE -> "Playback is unavailable right now. Close the tool fully and reopen it to reconnect."
    PlaybackErrorKind.UNKNOWN -> "Playback failed ($diagnostic). Press play to retry or skip ahead."
}

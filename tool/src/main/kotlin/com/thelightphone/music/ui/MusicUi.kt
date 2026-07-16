package com.thelightphone.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.model.Track
import com.thelightphone.music.playback.PlaybackStatus
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextField
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay

/**
 * Standard chrome for every Music screen: theme + background, a top bar (optional back / search),
 * the content column, and a persistent Now-Playing bar that hides itself when nothing is playing.
 */
@Composable
fun MusicScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onOpenNowPlaying: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors by LightThemeController.colors.collectAsState()
    LightTheme(colors = colors) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(LightThemeTokens.colors.background),
        ) {
            LightTopBar(
                leftButton = onBack?.let { LightBarButton.LightIcon(LightIcons.BACK, onClick = it) },
                center = LightTopBarCenter.Text(title),
                rightButton = when {
                    onSearch != null -> LightBarButton.LightIcon(LightIcons.SEARCH, onClick = onSearch)
                    onQueue != null -> LightBarButton.LightIcon(LightIcons.LIST, onClick = onQueue)
                    else -> null
                },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                content = content,
            )
            if (onOpenNowPlaying != null) NowPlayingBar(onOpen = onOpenNowPlaying)
        }
    }
}

@Composable
private fun NowPlayingBar(onOpen: () -> Unit) {
    val session by MusicApp.session.collectAsState()
    val p = session?.player ?: return
    val track by p.current.collectAsState()
    val t = track ?: return
    val playback by p.state.collectAsState()
    val playing = playback.status == PlaybackStatus.PLAYING
    LightBottomBar(
        items = listOf(
            LightBarButton.LightIcon(
                icon = if (playing) LightIcons.PAUSE else LightIcons.PLAY,
                onClick = { p.togglePlayPause() },
            ),
            LightBarButton.Text(text = t.title, onClick = onOpen),
            LightBarButton.LightIcon(LightIcons.FAST_FORWARD, onClick = { p.skipNext() }),
        ),
    )
}

@Composable
fun MenuRow(label: String, onClick: () -> Unit) {
    LightText(
        text = label,
        variant = LightTextVariant.Subheading,
        modifier = Modifier
            .fillMaxWidth()
            .lightClickable(onClick = onClick)
            .padding(vertical = 16.dp),
    )
}

@Composable
fun TrackRow(
    track: Track,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
    // Default-arg wiring: the one place this leaf touches the app object, overridable by callers.
    flashMs: Long = MusicApp.queuedFlashMs.collectAsState().value,
) {
    // The ＋ flips to a filled dot (CAMERA_RECORDING) for a moment after queueing — quiet
    // feedback, no system toast (Toast needs a Context, which the Light plugin blocks).
    // SELECT_ON would be the semantic fit but its asset renders cropped (SDK bug).
    var queuedFlash by remember { mutableStateOf(false) }
    LaunchedEffect(queuedFlash) {
        if (queuedFlash) {
            delay(flashMs)
            queuedFlash = false
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = track.title,
            variant = LightTextVariant.Copy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .lightClickable(onClick = onPlay),
        )
        LightIcon(
            icon = if (queuedFlash) LightIcons.CAMERA_RECORDING else LightIcons.ADD,
            contentDescription = if (queuedFlash) "queued" else "play next",
            modifier = Modifier
                .padding(start = 16.dp)
                .lightClickable {
                    onQueue()
                    queuedFlash = true
                },
        )
    }
}

@Composable
fun SectionHeader(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        lighten = true,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

fun formatTime(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * A [LazyListState] owned by the SCREEN INSTANCE, not the composition: LightActivity disposes
 * non-current screens entirely, so `rememberLazyListState` would forget the scroll position on
 * every navigation. Hold this in a screen property to retain scrolling across back-navigation.
 */
fun retainedListState() = LazyListState()

/**
 * A [LightTextField] that opens the full-screen keyboard editor and routes the submitted text to
 * [onResult]. The caller's composition is DISPOSED while the editor is on screen, so [onResult]
 * must write ViewModel-held state — never `remember`. The round-trip lives here so call sites
 * can't hold it wrong. [display] masks the shown value (e.g. a password) without changing what
 * the editor opens with.
 */
@Composable
fun SimpleLightScreen<*>.TextEntryField(
    label: String,
    value: String,
    placeholder: String = "",
    display: String = value,
    modifier: Modifier = Modifier,
    onResult: (String) -> Unit,
) {
    LightTextField(
        label = label,
        value = display,
        placeholder = placeholder,
        onClick = { editText(label, value, onResult) },
        modifier = modifier,
    )
}

/** Open the keyboard editor from any affordance (e.g. the top-bar filter icon). Same invariant as [TextEntryField]. */
fun SimpleLightScreen<*>.editText(title: String, initial: String, onResult: (String) -> Unit) {
    navigateTo(
        screenFactory = { TextEntryScreen(it, title, initial) },
        resultCallback = onResult,
    )
}

package com.thelightphone.music.playback

import com.thelightphone.sdk.audio.LightAudio
import com.thelightphone.sdk.audio.LightAudioItem
import com.thelightphone.sdk.audio.LightAudioPlayback
import com.thelightphone.sdk.audio.LightAudioPlayer
import com.thelightphone.sdk.audio.LightAudioSource
import com.thelightphone.sdk.audio.LightAudioUsage
import com.thelightphone.sdk.audio.LightMediaMetadata
import com.thelightphone.sdk.audio.NO_MEDIA_ITEM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** The queue index the SDK player reports when it holds nothing. */
const val NO_QUEUE_ITEM = NO_MEDIA_ITEM

/**
 * The playback seam over the SDK's **detached** player (ADR-0003): music runs in a media session
 * owned by the SDK's audio service, so it keeps playing after the tool screen — and the tool's own
 * UI — is gone, and reaches the platform's transport surfaces (Bluetooth, headset, LightOS
 * now-playing) for free.
 *
 * The window pushed by [Player] becomes the session's media queue, which the service walks on its
 * own. This adapter only mirrors what the player reports back: five SDK flows reduced into one
 * [PlaybackState], plus the two events [Player] needs to keep its queue cursor in step.
 *
 * Requires `capabilities = ["detached-audio"]` in `lighttool.toml`; constructing it without that
 * throws `LightAudioPlayerException`. Construct on the main thread.
 */
class LightAudioPlaybackController(
    audio: LightAudio,
    private val scope: CoroutineScope,
) : PlaybackController {

    private val player: LightAudioPlayer = audio.newPlayer(
        usage = LightAudioUsage.Music,
        playback = LightAudioPlayback.Detached,
    )

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override var onAdvance: ((Int) -> Unit)? = null
    override var onWindowEnd: (() -> Unit)? = null

    private var windowSize = 0
    private var awaitingStart = false
    private var lastIndex = NO_QUEUE_ITEM
    private var endReported = false

    /** One snapshot of the SDK player's independently-emitted state. */
    private data class Snapshot(
        val isPlaying: Boolean,
        val index: Int,
        val positionMs: Long,
        val durationMs: Long,
        val hasError: Boolean,
    )

    private val mirror: Job = scope.launch {
        combine(
            player.isPlaying,
            player.currentMediaItemIndex,
            player.positionMs,
            player.durationMs,
            player.error,
        ) { isPlaying, index, positionMs, durationMs, error ->
            Snapshot(isPlaying, index, positionMs, durationMs, error != null)
        }.collect { snapshot ->
            if (snapshot.isPlaying) awaitingStart = false
            _state.value = playbackStateOf(
                isPlaying = snapshot.isPlaying,
                index = snapshot.index,
                positionMs = snapshot.positionMs,
                durationMs = snapshot.durationMs,
                hasError = snapshot.hasError,
                windowSize = windowSize,
                awaitingStart = awaitingStart,
            )
            followIndex(snapshot.index)
            if (_state.value.status == PlaybackStatus.ENDED && !endReported) {
                endReported = true
                onWindowEnd?.invoke()
            }
        }
    }

    /**
     * The session moved through its queue. Forward steps are natural completions — including ones
     * driven from outside the tool, such as a headset's next button — and the queue follows them.
     * A backward step (an external "previous") only re-syncs: the tool's History is the authority
     * on what "previous" means, so we never rewrite it from a platform transport command.
     */
    private fun followIndex(index: Int) {
        if (index > lastIndex && lastIndex != NO_QUEUE_ITEM) {
            lastIndex = index
            onAdvance?.invoke(index)
        } else if (index != lastIndex) {
            lastIndex = index
        }
    }

    override fun play(window: List<PlayableTrack>) {
        windowSize = window.size
        awaitingStart = true
        endReported = false
        lastIndex = 0
        scope.launch {
            if (!player.awaitReady()) return@launch
            player.setMediaQueue(window.map(PlayableTrack::toLightAudioItem), startIndex = 0)
            player.play()
        }
    }

    override fun pause() = player.pause()

    override fun resume() = player.play()

    override fun stop() {
        windowSize = 0
        lastIndex = NO_QUEUE_ITEM
        player.stop()
        player.setMediaQueue(emptyList())
    }

    /**
     * Ends this session's playback. Releasing a detached handle on its own would leave music
     * playing with nothing to control it — the tool only releases when its whole [Session] is torn
     * down (a credentials change), and that music belongs to the server we just left.
     */
    override fun release() {
        stop()
        mirror.cancel()
        player.release()
    }
}

/**
 * Reduce the SDK player's five state flows into the tool's one [PlaybackState].
 *
 * The SDK reports no explicit end-of-queue, so the end of the window is inferred: the last entry,
 * not playing, parked at its own duration. [awaitingStart] covers the gap between handing over a
 * window and the session actually producing sound, which reads as buffering.
 */
internal fun playbackStateOf(
    isPlaying: Boolean,
    index: Int,
    positionMs: Long,
    durationMs: Long,
    hasError: Boolean,
    windowSize: Int,
    awaitingStart: Boolean,
): PlaybackState {
    val status = when {
        hasError -> PlaybackStatus.ERROR
        index == NO_QUEUE_ITEM -> PlaybackStatus.IDLE
        isPlaying -> PlaybackStatus.PLAYING
        awaitingStart -> PlaybackStatus.BUFFERING
        atEndOfWindow(index, windowSize, positionMs, durationMs) -> PlaybackStatus.ENDED
        else -> PlaybackStatus.PAUSED
    }
    return PlaybackState(
        status = status,
        positionMs = positionMs.toInt(),
        durationMs = durationMs.toInt(),
    )
}

private fun atEndOfWindow(index: Int, windowSize: Int, positionMs: Long, durationMs: Long): Boolean =
    index == windowSize - 1 && durationMs > 0 && positionMs >= durationMs - END_OF_ITEM_TOLERANCE_MS

private fun PlayableTrack.toLightAudioItem(): LightAudioItem = LightAudioItem(
    source = when (val source = source) {
        is AudioSource.Remote -> LightAudioSource.UrlSource(source.url)
        is AudioSource.Local -> LightAudioSource.FileSource(File(source.path))
    },
    metadata = LightMediaMetadata(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
    ),
)

/** How close to its duration a parked item counts as finished, absorbing decoder rounding. */
private const val END_OF_ITEM_TOLERANCE_MS = 500L

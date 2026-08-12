package com.thelightphone.music.playback

import kotlinx.coroutines.flow.StateFlow

/** An audio source the [PlaybackController] can play: a remote Stream URL or a local cache file. */
sealed interface AudioSource {
    data class Remote(val url: String) : AudioSource
    data class Local(val path: String) : AudioSource
}

enum class PlaybackStatus { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)

/**
 * One entry of the window handed to the player: the bytes to play plus what the platform's
 * now-playing surfaces (lock screen, Bluetooth, LightOS) display while it plays.
 */
data class PlayableTrack(
    val source: AudioSource,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/**
 * The playback seam (ADR-0002, ADR-0003). Everything above it — queue, cache, UI — is
 * design-independent; only the implementation below it changes with the platform's audio API.
 * [LightAudioPlaybackController] is the real one, backed by the SDK's detached player.
 *
 * The seam is *window*-shaped rather than track-shaped: the [Player] hands over the current track
 * plus what follows it, and the implementation walks that window by itself. That is what keeps
 * music going once the tool screen is gone — an implementation that had to be called back into
 * the tool for every track would fall silent the moment the tool stopped running.
 *
 * Contract for adapters:
 * - [onAdvance] and [onWindowEnd] must be assigned BEFORE the first [play]; a callback that fires
 *   with no handler is dropped and the queue silently stops following playback. Either may be
 *   invoked on the implementation's own thread.
 * - Construct on the main thread unless the adapter documents otherwise.
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** Replace what is queued for playback with [window], starting at its first entry. */
    fun play(window: List<PlayableTrack>)
    fun pause()
    fun resume()
    fun stop()

    /** The player moved to `window[index]` on its own — used to follow it with the queue cursor. */
    var onAdvance: ((index: Int) -> Unit)?

    /** The player reached the end of the window and stopped. */
    var onWindowEnd: (() -> Unit)?

    fun release()
}

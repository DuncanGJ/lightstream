package com.thelightphone.music.playback

import kotlinx.coroutines.flow.StateFlow

/** An audio source the [PlaybackController] can play: a remote Stream URL or a local cache file. */
sealed interface AudioSource {
    data class Remote(val url: String) : AudioSource
    data class Local(val path: String) : AudioSource
}

/**
 * [STOPPED] is the user's Stop: the current track is still current and the window still loaded,
 * but playback is parked at 0:00 with the speaker released (stop-button-ux #01). [IDLE] means
 * nothing is loaded at all.
 */
enum class PlaybackStatus { IDLE, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR }

/**
 * Whether the play/pause control shows ⏸: true whenever playback is *meant* to continue, so the
 * buffering gap at every track boundary never flashes ▶ (play-pause-flash #01).
 */
fun PlaybackStatus.showsPause(): Boolean = this == PlaybackStatus.PLAYING || this == PlaybackStatus.BUFFERING

/**
 * The last transport command the session was given, by the tool or observed from outside it.
 * The SDK reports whether audio is advancing, not whether it is meant to be, so the adapter keeps
 * this to tell a buffering gap from a pause.
 */
enum class TransportIntent { PLAY, PAUSE, STOP }

/** Why playback stopped, at the granularity the UI can phrase and the user can act on. */
enum class PlaybackErrorKind {
    /** Network or file I/O: the stream URL did not answer, or the cache file is gone. Retry-able. */
    SOURCE,
    /** Container or codec the device cannot decode. Skip it. */
    UNSUPPORTED,
    /** The audio output could not be opened. */
    OUTPUT,
    /** The platform player handle is gone; nothing will play until a new one is made. */
    UNAVAILABLE,
    UNKNOWN,
}

data class PlaybackError(val kind: PlaybackErrorKind, val diagnostic: String)

data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    /** Set exactly when [status] is [PlaybackStatus.ERROR]. */
    val error: PlaybackError? = null,
)

/**
 * One entry of the window handed to the player: the bytes to play plus what the platform's
 * now-playing surfaces (lock screen, Bluetooth, LightOS) display while it plays.
 *
 * [id] is the library's track id — stable across resolves, unlike a [AudioSource.Remote] Stream
 * URL, which carries a fresh salted token every time. The implementation keys anything it keeps
 * about the entry (cached bytes, error attribution) on it.
 */
data class PlayableTrack(
    val id: String,
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
 * - [onIndexChanged] and [onWindowEnd] must be assigned BEFORE the first [play]; a callback that fires
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

    /** Park the current entry at its start and release the speaker; the window stays loaded. */
    fun stop()

    /** Unload everything: the session holds no queue afterwards (end of queue, teardown). */
    fun clear()

    /** Move within the current entry. Clamped to the entry by the implementation. */
    fun seekTo(positionMs: Int)

    /**
     * The player moved to `window[index]` on its own — a natural completion, or a headset / car /
     * LightOS transport command in either direction. Used to follow it with the queue cursor.
     */
    var onIndexChanged: ((index: Int) -> Unit)?

    /** The player reached the end of the window and stopped. */
    var onWindowEnd: (() -> Unit)?

    fun release()
}

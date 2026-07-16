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
 * The playback seam (ADR-0002). Everything above it — queue, cache, UI — is design-independent;
 * only the implementation below it changes when LightOS ships its sanctioned audio API. The
 * current implementation ([MediaPlayerController]) is a disposable foreground-only shim.
 *
 * Contract for adapters (the future LightOS one included):
 * - [onCompletion] must be assigned BEFORE the first [play]; a completion that fires with no
 *   handler is dropped and the queue silently stops advancing. It may be invoked on the
 *   implementation's own thread.
 * - Construct on the main thread unless the adapter documents otherwise (the MediaPlayer shim
 *   delivers callbacks on its creating thread).
 */
interface PlaybackController {
    val state: StateFlow<PlaybackState>

    /** Load and begin playing [source] from the start. */
    fun play(source: AudioSource)
    fun pause()
    fun resume()
    fun stop()

    /** Invoked when the current source finishes on its own — used to advance the queue. */
    var onCompletion: (() -> Unit)?

    fun release()
}

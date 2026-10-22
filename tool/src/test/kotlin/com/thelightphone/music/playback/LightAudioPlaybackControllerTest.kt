package com.thelightphone.music.playback

import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import com.thelightphone.sdk.audio.LightAudioSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SDK player reports its state as five independent flows; the tool's UI renders one
 * [PlaybackState]. That reduction is the only part of the detached adapter that can be exercised
 * off-device, and it is where the interesting cases live.
 */
class LightAudioPlaybackControllerTest {

    @Test
    fun `a player holding no queue item is idle`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = NO_QUEUE_ITEM,
            positionMs = 0,
            durationMs = 0,
            error = null,
            availability = LightAudioPlayerAvailability.Ready,
            windowSize = 0,
            intent = TransportIntent.PLAY,
            stalled = false,
        )

        assertEquals(PlaybackStatus.IDLE, state.status)
    }

    @Test
    fun `a playing item reports its position and duration in milliseconds`() {
        val state = playbackStateOf(
            isPlaying = true,
            index = 0,
            positionMs = 42_000,
            durationMs = 180_000,
            error = null,
            availability = LightAudioPlayerAvailability.Ready,
            windowSize = 3,
            intent = TransportIntent.PLAY,
            stalled = false,
        )

        assertEquals(PlaybackStatus.PLAYING, state.status)
        assertEquals(42_000, state.positionMs)
        assertEquals(180_000, state.durationMs)
    }

    @Test
    fun `a window handed over but not yet producing sound is buffering`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = 0,
            positionMs = 0,
            durationMs = 0,
            error = null,
            availability = LightAudioPlayerAvailability.Ready,
            windowSize = 3,
            intent = TransportIntent.PLAY,
            stalled = false,
        )

        assertEquals(PlaybackStatus.BUFFERING, state.status)
    }

    @Test
    fun `the last entry parked at its own duration has ended`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = 2,
            positionMs = 180_000,
            durationMs = 180_000,
            error = null,
            availability = LightAudioPlayerAvailability.Ready,
            windowSize = 3,
            intent = TransportIntent.PLAY,
            stalled = false,
        )

        assertEquals(PlaybackStatus.ENDED, state.status, "the SDK reports no end-of-queue of its own")
    }

    @Test
    fun `an earlier entry parked at its duration has not ended`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = 0,
            positionMs = 180_000,
            durationMs = 180_000,
            error = null,
            availability = LightAudioPlayerAvailability.Ready,
            windowSize = 3,
            intent = TransportIntent.PAUSE,
            stalled = false,
        )

        assertEquals(PlaybackStatus.PAUSED, state.status, "the session still has entries to play")
    }

    @Test
    fun `a stopped item mid-track is paused, not ended`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = 1,
            positionMs = 42_000,
            durationMs = 180_000,
            error = null,
            availability = LightAudioPlayerAvailability.Ready,
            windowSize = 3,
            intent = TransportIntent.PAUSE,
            stalled = false,
        )

        assertEquals(PlaybackStatus.PAUSED, state.status)
    }

    @Test
    fun `a failed stream reports what went wrong so the user can act on it`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = 1,
            positionMs = 0,
            durationMs = 0,
            error = LightAudioError(LightAudioErrorKind.Source, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", itemIndex = 1),
            availability = LightAudioPlayerAvailability.Ready,
            windowSize = 3,
            intent = TransportIntent.PLAY, // the handover never produced sound — the error wins over buffering
            stalled = false,
        )

        assertEquals(PlaybackStatus.ERROR, state.status)
        assertEquals(
            PlaybackError(PlaybackErrorKind.SOURCE, "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"),
            state.error,
        )
    }

    @Test
    fun `a released player handle is unavailable, not merely idle`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = NO_QUEUE_ITEM,
            positionMs = 0,
            durationMs = 0,
            error = null,
            availability = LightAudioPlayerAvailability.Released,
            windowSize = 0,
            intent = TransportIntent.PLAY,
            stalled = false,
        )

        assertEquals(PlaybackStatus.ERROR, state.status, "idle would invite a play() that can never be honoured")
        assertEquals(PlaybackErrorKind.UNAVAILABLE, state.error?.kind)
    }

    @Test
    fun `a window entry reaches the SDK under its track id so streamed bytes are cached by track`() {
        val url = "https://s.test/rest/stream?id=track-7&s=salt1&t=tok1"
        val entry = PlayableTrack(
            id = "track-7",
            source = AudioSource.Remote(url),
            title = "Nightswimming",
            artist = "R.E.M.",
            album = "Automatic",
            durationMs = 258_000,
        )

        val item = entry.toLightAudioItem()

        assertEquals("track-7", item.id, "keyed on the URL, every salted resolve would miss the cache")
        assertEquals(LightAudioSource.UrlSource(url), item.source)
        assertEquals("Nightswimming", item.metadata.title)
    }

    @Test
    fun `a refused detached connection is unavailable, not stuck buffering`() {
        val state = playbackStateOf(
            isPlaying = false,
            index = NO_QUEUE_ITEM,
            positionMs = 0,
            durationMs = 0,
            error = null,
            availability = LightAudioPlayerAvailability.Refused,
            windowSize = 3,
            intent = TransportIntent.PLAY,
            stalled = false, // a window was handed over, but the service never took it
        )

        assertEquals(PlaybackStatus.ERROR, state.status, "the SDK will never connect; buffering would spin forever")
        assertEquals(PlaybackErrorKind.UNAVAILABLE, state.error?.kind)
    }

    @Test
    fun `a not-playing gap inside a live window while intending to play is buffering`() {
        // media3 drops out of READY at every track boundary; that is not a pause.
        val state = playbackStateOf(
            isPlaying = false, index = 1, positionMs = 0, durationMs = 0, error = null,
            availability = LightAudioPlayerAvailability.Ready, windowSize = 3,
            intent = TransportIntent.PLAY, stalled = false,
        )

        assertEquals(PlaybackStatus.BUFFERING, state.status)
    }

    @Test
    fun `after a pause the same gap is paused`() {
        val state = playbackStateOf(
            isPlaying = false, index = 1, positionMs = 42_000, durationMs = 180_000, error = null,
            availability = LightAudioPlayerAvailability.Ready, windowSize = 3,
            intent = TransportIntent.PAUSE, stalled = false,
        )

        assertEquals(PlaybackStatus.PAUSED, state.status)
    }

    @Test
    fun `a stall past the grace period while intending to play reads as paused`() {
        // An external pause (car, headset) is invisible to the tool except as a stall.
        val state = playbackStateOf(
            isPlaying = false, index = 1, positionMs = 42_000, durationMs = 180_000, error = null,
            availability = LightAudioPlayerAvailability.Ready, windowSize = 3,
            intent = TransportIntent.PLAY, stalled = true,
        )

        assertEquals(PlaybackStatus.PAUSED, state.status)
    }

    @Test
    fun `the last entry parked at its duration still ends while intending to play`() {
        val state = playbackStateOf(
            isPlaying = false, index = 2, positionMs = 180_000, durationMs = 180_000, error = null,
            availability = LightAudioPlayerAvailability.Ready, windowSize = 3,
            intent = TransportIntent.PLAY, stalled = false,
        )

        assertEquals(PlaybackStatus.ENDED, state.status, "the queue must still finish")
    }

    @Test
    fun `the play-pause control shows pause whenever playback is meant to continue`() {
        assertTrue(PlaybackStatus.PLAYING.showsPause())
        assertTrue(PlaybackStatus.BUFFERING.showsPause())
        assertFalse(PlaybackStatus.PAUSED.showsPause())
        assertFalse(PlaybackStatus.IDLE.showsPause())
        assertFalse(PlaybackStatus.ENDED.showsPause())
        assertFalse(PlaybackStatus.ERROR.showsPause())
        assertFalse(PlaybackStatus.STOPPED.showsPause())
    }

    @Test
    fun `stop keeps the window loaded and reads as stopped, not idle`() {
        val state = playbackStateOf(
            isPlaying = false, index = 1, positionMs = 0, durationMs = 180_000, error = null,
            availability = LightAudioPlayerAvailability.Ready, windowSize = 3,
            intent = TransportIntent.STOP, stalled = false,
        )

        assertEquals(PlaybackStatus.STOPPED, state.status, "the track is still current, just parked")
    }
}

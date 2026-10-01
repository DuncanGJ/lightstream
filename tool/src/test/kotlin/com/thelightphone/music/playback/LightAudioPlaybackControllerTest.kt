package com.thelightphone.music.playback

import com.thelightphone.sdk.audio.LightAudioError
import com.thelightphone.sdk.audio.LightAudioErrorKind
import com.thelightphone.sdk.audio.LightAudioPlayerAvailability
import kotlin.test.Test
import kotlin.test.assertEquals

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
            awaitingStart = false,
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
            awaitingStart = false,
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
            awaitingStart = true,
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
            awaitingStart = false,
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
            awaitingStart = false,
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
            awaitingStart = false,
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
            awaitingStart = true, // the handover never produced sound — the error wins over buffering
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
            awaitingStart = false,
        )

        assertEquals(PlaybackStatus.ERROR, state.status, "idle would invite a play() that can never be honoured")
        assertEquals(PlaybackErrorKind.UNAVAILABLE, state.error?.kind)
    }
}

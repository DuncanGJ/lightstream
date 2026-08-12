package com.thelightphone.music.playback

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
            hasError = false,
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
            hasError = false,
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
            hasError = false,
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
            hasError = false,
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
            hasError = false,
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
            hasError = false,
            windowSize = 3,
            awaitingStart = false,
        )

        assertEquals(PlaybackStatus.PAUSED, state.status)
    }
}

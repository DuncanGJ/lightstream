package com.thelightphone.music.playback

import com.thelightphone.music.cache.TrackCache
import com.thelightphone.music.model.Track
import com.thelightphone.music.queue.Queue
import com.thelightphone.music.queue.RepeatMode
import com.thelightphone.music.queue.Source
import com.thelightphone.music.subsonic.SubsonicClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates the design-independent pieces: it drives the [Queue], resolves each track to a
 * cached file or a remote Stream URL via [TrackCache]/[SubsonicClient], plays it through the
 * [PlaybackController] seam, advances on completion, and keeps the rolling prefetch window full.
 * Swapping the seam for LightOS's future audio API leaves everything here untouched. See ADR-0002.
 */
class Player(
    private val subsonic: SubsonicClient,
    private val cache: TrackCache,
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
    private val prefetchDepth: () -> Int = { PREFETCH_DEPTH }, // supplier: Settings-tunable live
) {
    private val queue = Queue()

    val state: StateFlow<PlaybackState> = controller.state
    val current: StateFlow<Track?> = queue.current

    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeat

    private var prefetchJob: Job? = null

    init {
        controller.onCompletion = { skipNext() }
    }

    /** Begin playing [source] at [index]; the rest of the source becomes the up-next queue. */
    fun playFrom(source: Source, index: Int) {
        scope.launch {
            queue.start(source, index)
            playCurrent()
        }
    }

    fun togglePlayPause() {
        when (state.value.status) {
            PlaybackStatus.PLAYING -> controller.pause()
            PlaybackStatus.PAUSED -> controller.resume()
            else -> Unit
        }
    }

    fun skipNext() {
        scope.launch {
            if (queue.next() != null) {
                playCurrent()
            } else {
                queue.finish() // the song completed: History, not back into the queue
                controller.stop()
            }
        }
    }

    /** User stop: playback and the now-playing bar go away; the queue is fully retained. */
    fun stop() {
        prefetchJob?.cancel()
        queue.stop()
        controller.stop()
    }

    /** Start the front of the queue when nothing is playing (the queue page's play button). */
    fun playNextInQueue() {
        scope.launch {
            if (queue.next() != null) playCurrent()
        }
    }

    /**
     * Always steps back through History — no restart-after-3s rule. The queue is a temporary
     * playlist: navigation must never lose your place or drop songs (only ✕ removes).
     */
    fun skipPrevious() {
        scope.launch {
            queue.previous()
            playCurrent()
        }
    }

    /**
     * "Add to queue" == play immediately after the current track (CONTEXT.md: Play Next).
     * Never starts playback by itself — with nothing playing, the queue page offers a play
     * button on the first queued item instead.
     */
    fun addToQueue(track: Track) {
        queue.addPlayNext(track)
    }

    // Queue-page view + edits (window-scoped; see Queue).
    suspend fun upcomingItems(n: Int): List<Queue.UpcomingItem> = queue.upcomingItems(n)
    suspend fun removeUpcoming(index: Int) = queue.removeUpcoming(index)
    suspend fun promoteToNext(index: Int) = queue.promoteToNext(index)
    suspend fun moveUpcoming(from: Int, to: Int) = queue.moveUpcoming(from, to)

    fun cycleRepeat() {
        val next = when (_repeat.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _repeat.value = next
        queue.repeat = next
    }

    /** Null if the server answers; otherwise the failure reason (surfaced in Settings). */
    suspend fun pingError(): String? = subsonic.pingError()

    fun release() {
        prefetchJob?.cancel()
        controller.release()
    }

    private suspend fun playCurrent() {
        val track = queue.current.value ?: return
        controller.play(resolve(track))
        cache.touch(track.id)
        startPrefetch()
    }

    private suspend fun resolve(track: Track): AudioSource {
        val local = cache.localPath(track.id)
        return if (local != null) AudioSource.Local(local) else AudioSource.Remote(subsonic.streamUrl(track.id))
    }

    private fun startPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch { cache.prefetch(queue.upcoming(prefetchDepth())) }
    }

    private companion object {
        const val PREFETCH_DEPTH = 15
    }
}

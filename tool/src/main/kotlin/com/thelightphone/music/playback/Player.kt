package com.thelightphone.music.playback

import com.thelightphone.music.cache.TrackCache
import com.thelightphone.music.model.Track
import com.thelightphone.music.queue.Queue
import com.thelightphone.music.queue.RepeatMode
import com.thelightphone.music.queue.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The single owner of playback state. It drives the [Queue] (every queue call is confined to
 * [scope] — the queue itself is not thread-safe), resolves each track to a cached file or a remote
 * Stream URL via [TrackCache]/[SubsonicClient], plays it through the [PlaybackController] seam,
 * advances on completion, and keeps the rolling prefetch window full — re-aimed after every queue
 * edit, so it never downloads a track that was just removed. The UI never touches the queue
 * directly: it renders [current], [state], [upcoming] and [repeatMode], and calls the methods here.
 * Swapping the seam for LightOS's future audio API leaves everything here untouched. See ADR-0002.
 */
class Player(
    private val cache: TrackCache,
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
    private val prefetchDepth: () -> Int = { DEFAULT_PREFETCH_DEPTH }, // supplier: Settings-tunable live
    private val resolveRemote: (trackId: String) -> AudioSource.Remote,
) {
    private val _repeat = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeat

    // The queue reads repeat live from us — one copy of the mode, nothing to hand-sync.
    private val queue = Queue(repeat = { _repeat.value })

    val state: StateFlow<PlaybackState> = controller.state
    val current: StateFlow<Track?> = queue.current

    /** The queue page's window, re-emitted after every queue mutation — no manual invalidation. */
    private val _upcoming = MutableStateFlow<List<Queue.UpcomingItem>>(emptyList())
    val upcoming: StateFlow<List<Queue.UpcomingItem>> = _upcoming

    private var prefetchJob: Job? = null

    init {
        controller.onCompletion = { advance(userSkip = false) }
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

    /** The next button: always advances — Repeat ONE only bites on natural completion. */
    fun skipNext() = advance(userSkip = true)

    private fun advance(userSkip: Boolean) {
        scope.launch {
            if (queue.next(userSkip) != null) {
                playCurrent()
            } else {
                queue.finish() // the song completed: History, not back into the queue
                controller.stop()
                refreshUpcoming()
            }
        }
    }

    /** User stop: playback and the now-playing bar go away; the queue is fully retained. */
    fun stop() {
        prefetchJob?.cancel()
        scope.launch {
            queue.stop()
            controller.stop()
            refreshUpcoming()
        }
    }

    /** Start the front of the queue when nothing is playing (the queue page's play button). */
    fun playNextInQueue() {
        scope.launch {
            if (queue.next(userSkip = true) != null) playCurrent()
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
    fun addToQueue(track: Track) = editQueue { queue.addPlayNext(track) }

    // Queue-page edits. Each runs on our scope, then re-emits [upcoming] and re-aims the prefetch.
    fun removeUpcoming(index: Int) = editQueue { queue.removeUpcoming(index) }
    fun promoteToNext(index: Int) = editQueue { queue.promoteToNext(index) }
    fun moveUpcoming(from: Int, to: Int) = editQueue { queue.moveUpcoming(from, to) }

    fun cycleRepeat() {
        _repeat.value = when (_repeat.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun release() {
        prefetchJob?.cancel()
        controller.release()
    }

    private fun editQueue(edit: suspend () -> Unit) {
        scope.launch {
            edit()
            refreshUpcoming()
            refreshPrefetch()
        }
    }

    private suspend fun playCurrent() {
        val track = queue.current.value ?: return
        controller.play(resolve(track))
        cache.touch(track.id)
        refreshUpcoming()
        refreshPrefetch()
    }

    private suspend fun resolve(track: Track): AudioSource {
        val local = cache.localPath(track.id)
        return if (local != null) AudioSource.Local(local) else resolveRemote(track.id)
    }

    private suspend fun refreshUpcoming() {
        _upcoming.value = queue.upcomingItems(UPCOMING_WINDOW)
    }

    /** (Re)start the rolling prefetch against the current window — only while something plays. */
    private fun refreshPrefetch() {
        if (queue.current.value == null) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch { cache.prefetch(queue.upcoming(prefetchDepth())) }
    }

    companion object {
        const val DEFAULT_PREFETCH_DEPTH = 15
        const val UPCOMING_WINDOW = 50
    }
}

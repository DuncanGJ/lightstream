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
 * Stream URL via [TrackCache]/[SubsonicClient], and hands the [PlaybackController] seam a *window*
 * of tracks — the current one plus what follows it — so playback survives the tool screen being
 * gone (ADR-0003). The player then walks that window on its own and the queue cursor follows via
 * [PlaybackController.onAdvance]; the tool is a bookkeeper for playback, no longer its heartbeat.
 *
 * It also keeps the rolling prefetch window full — re-aimed after every queue edit, so it never
 * downloads a track that was just removed. The UI never touches the queue directly: it renders
 * [current], [state], [upcoming] and [repeatMode], and calls the methods here.
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

    /** Where the player is inside the window we pushed, and how big that window was. */
    private var windowIndex = 0
    private var windowSize = 0

    /**
     * A queue edit landed after the window was pushed. Re-pointing playback mid-song would restart
     * it, so the edited window is handed over at the next track boundary instead.
     */
    private var windowStale = false

    init {
        controller.onAdvance = { index -> scope.launch { followAdvance(index) } }
        controller.onWindowEnd = { scope.launch { finishQueue() } }
    }

    /** Begin playing [source] at [index]; the rest of the source becomes the up-next queue. */
    fun playFrom(source: Source, index: Int) {
        scope.launch {
            queue.start(source, index)
            pushWindow()
        }
    }

    /**
     * Play/pause, and on a failed track **retry**: the window is re-pushed from the current cursor,
     * which also re-resolves every entry — a track whose download finished since now plays from
     * the cache. Nothing skips on its own (unplayable content would loop); next is a button away.
     */
    fun togglePlayPause() {
        when (state.value.status) {
            PlaybackStatus.PLAYING -> controller.pause()
            PlaybackStatus.PAUSED -> controller.resume()
            PlaybackStatus.ERROR -> if (queue.current.value != null) scope.launch { pushWindow() }
            else -> Unit
        }
    }

    /** Scrub within the current track; a no-op with nothing playing. */
    fun seekTo(positionMs: Int) {
        if (queue.current.value == null) return
        controller.seekTo(positionMs)
    }

    /** The 15-second nudges. Clamped here, from the state the UI already shows, so the seam stays one `seekTo`. */
    fun skipForward() = nudge(NUDGE_MS)
    fun skipBack() = nudge(-NUDGE_MS)

    private fun nudge(deltaMs: Int) {
        val s = state.value
        seekTo(nudgedPosition(s.positionMs, s.durationMs, deltaMs))
    }

    /** The next button: always advances — Repeat ONE only bites on natural completion. */
    fun skipNext() {
        scope.launch {
            if (queue.next(userSkip = true) != null) pushWindow() else finishQueue()
        }
    }

    /**
     * The player finished `windowIndex` and moved on by itself — one natural completion per step,
     * so the queue records exactly what played (History, Repeat ONE) even while the tool screen was
     * gone. A stale or nearly-exhausted window is refilled here, at the boundary: the track that
     * just started restarts at zero, which is where it already is.
     */
    private suspend fun followAdvance(index: Int) {
        repeat(index - windowIndex) { queue.next(userSkip = false) }
        windowIndex = index
        queue.current.value?.let { cache.touch(it.id) }
        val remaining = windowSize - index // entries the player still holds, current included
        if (windowStale || remaining <= REFILL_THRESHOLD) {
            val window = queue.playbackWindow(WINDOW_DEPTH)
            // Re-point only when it changes what will play: a short queue running out is the
            // player correctly reaching its end, not a window that needs refilling.
            if (windowStale || window.size > remaining) {
                pushWindow(window)
                return
            }
        }
        refreshUpcoming()
        refreshPrefetch()
    }

    /** The queue ran out: the last song completed, so it belongs in History, not back in the queue. */
    private suspend fun finishQueue() {
        queue.finish()
        controller.stop()
        windowSize = 0
        refreshUpcoming()
    }

    /** User stop: playback and the now-playing bar go away; the queue is fully retained. */
    fun stop() {
        prefetchJob?.cancel()
        scope.launch {
            queue.stop()
            controller.stop()
            windowSize = 0
            refreshUpcoming()
        }
    }

    /** Start the front of the queue when nothing is playing (the queue page's play button). */
    fun playNextInQueue() {
        scope.launch {
            if (queue.next(userSkip = true) != null) pushWindow()
        }
    }

    /**
     * Always steps back through History — no restart-after-3s rule. The queue is a temporary
     * playlist: navigation must never lose your place or drop songs (only ✕ removes).
     */
    fun skipPrevious() {
        scope.launch {
            queue.previous()
            pushWindow()
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
        windowStale = true // the pushed window was built under the old mode
    }

    fun release() {
        prefetchJob?.cancel()
        controller.release()
    }

    private fun editQueue(edit: suspend () -> Unit) {
        scope.launch {
            edit()
            windowStale = true
            refreshUpcoming()
            refreshPrefetch()
        }
    }

    /**
     * Hand the player the current track plus what follows it, and start there. Called whenever
     * playback is (re-)pointed — a new source, a skip, or a refill at a track boundary.
     */
    private suspend fun pushWindow() = pushWindow(queue.playbackWindow(WINDOW_DEPTH))

    private suspend fun pushWindow(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        controller.play(tracks.map { PlayableTrack(resolve(it), it.title, it.artist, it.album, it.durationMs()) })
        windowIndex = 0
        windowSize = tracks.size
        windowStale = false
        cache.touch(tracks.first().id)
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

        /**
         * How many tracks the player is handed at once. Deep enough that a refill is rare (each one
         * restarts the track at the boundary it lands on), shallow enough that a queue edit reaches
         * the player without materializing a whole lazy [Source].
         */
        const val WINDOW_DEPTH = 50

        /** Refill once this few entries are left, so the player never runs the window dry. */
        const val REFILL_THRESHOLD = 10

        /** How far the skip-back / skip-forward buttons move, matching the SDK player's own nudge. */
        const val NUDGE_MS = 15_000
    }
}

/** Where a nudge lands: clamped to the track, and to zero while the duration is still unknown. */
internal fun nudgedPosition(positionMs: Int, durationMs: Int, deltaMs: Int): Int =
    (positionMs + deltaMs).coerceIn(0, durationMs.coerceAtLeast(0))

/** The library's duration, in the milliseconds the platform's now-playing surfaces expect. */
private fun Track.durationMs(): Long? = durationSec?.let { it * 1000L }

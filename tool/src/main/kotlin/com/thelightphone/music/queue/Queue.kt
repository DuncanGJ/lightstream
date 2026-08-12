package com.thelightphone.music.queue

import com.thelightphone.music.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class RepeatMode { OFF, ALL, ONE }

/**
 * A lazily-realized ordered sequence of tracks. Bounded sources (album, playlist, artist, search)
 * are backed by [ListSource]; a future paged "all songs A→Z" source can implement the same
 * interface without changing [Queue]. See CONTEXT.md ("Source").
 */
interface Source {
    val title: String

    /** Number of items, or null if not yet known / unbounded. */
    val size: Int?

    /** Realize the track at [index], fetching if necessary. Null if out of range. */
    suspend fun get(index: Int): Track?
}

class ListSource(
    override val title: String,
    private val tracks: List<Track>,
) : Source {
    override val size: Int get() = tracks.size
    override suspend fun get(index: Int): Track? = tracks.getOrNull(index)
}

/**
 * The playback queue (CONTEXT.md, ADR-0002): a cursor over a [Source] plus a Play-Next overlay.
 * [next] drains the overlay first, then advances the Source cursor, honouring [repeat] (read live
 * from the supplier — [Player] owns the mode, the queue never stores its own copy). Owned by the
 * tool, behind the playback seam. Not thread-safe: [Player] confines every call to its scope.
 */
class Queue(
    private val repeat: () -> RepeatMode = { RepeatMode.OFF },
) {

    private var source: Source = ListSource("", emptyList())
    private var cursor: Int = 0
    private val playNext = ArrayDeque<Track>()

    /** A snapshot of what was playing: restoring one restores the full queue context. */
    private data class HistoryEntry(
        val track: Track,
        val source: Source,
        val cursor: Int,
        val fromOverlay: Boolean,
    )
    private val history = ArrayDeque<HistoryEntry>()

    /** Whether the current track was drained from the Play-Next overlay (vs the Source cursor). */
    private var currentFromOverlay = false

    /** Track ids removed from the upcoming view; the cursor scans past them when advancing. */
    private val skippedIds = mutableSetOf<String>()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current

    /**
     * Begin playing [source] at [index], setting the item there as current. Hand-queued Play-Next
     * items survive the context switch — songs only leave the queue via [removeUpcoming].
     */
    suspend fun start(source: Source, index: Int) {
        _current.value?.let { pushHistory(it) }
        this.source = source
        this.cursor = index.coerceAtLeast(0)
        skippedIds.clear()
        currentFromOverlay = false
        _current.value = source.get(cursor)
    }

    /** Insert [track] to play immediately after the current one, then resume the Source. */
    fun addPlayNext(track: Track) {
        playNext.addLast(track)
    }

    /**
     * User stop: unload the current track back to the FRONT of up-next and clear now-playing.
     * The queue loses nothing — resuming plays the stopped song first.
     */
    fun stop() {
        _current.value?.let { playNext.addFirst(it) }
        currentFromOverlay = false
        _current.value = null
    }

    /** Natural end: the current track finished — move it to History and clear now-playing. */
    fun finish() {
        _current.value?.let { pushHistory(it) }
        currentFromOverlay = false
        _current.value = null
    }

    /**
     * Advance to the next track — overlay first, then the Source cursor. Null at the end.
     * Repeat ONE only bites on natural completion: a [userSkip] must always advance, or the
     * next button would trap the listener on the repeating track.
     */
    suspend fun next(userSkip: Boolean = false): Track? {
        if (!userSkip && repeat() == RepeatMode.ONE && _current.value != null) return _current.value
        if (playNext.isNotEmpty()) {
            _current.value?.let { pushHistory(it) }
            _current.value = playNext.removeFirst()
            currentFromOverlay = true
            return _current.value
        }
        val resolved = resolveNextIndex(source.size) ?: return null
        _current.value?.let { pushHistory(it) }
        cursor = resolved
        currentFromOverlay = false
        _current.value = source.get(cursor)
        return _current.value
    }

    /**
     * Next playable Source index: forward scan past skipped tracks, wrapping once on Repeat ALL.
     * With an unknown size (paged source), probes get() until it returns null (the end).
     */
    private suspend fun resolveNextIndex(size: Int?): Int? {
        var i = cursor + 1
        while (size == null || i < size) {
            val t = source.get(i) ?: break
            if (t.id !in skippedIds) return i
            i++
        }
        if (repeat() != RepeatMode.ALL) return null
        val bound = source.size ?: return 0 // may have become known while probing
        var j = 0
        while (j < bound) {
            val t = source.get(j) ?: return null
            if (t.id !in skippedIds) return j
            j++
        }
        return null
    }

    /**
     * Step back to the last *actually played* track (History), restoring its full queue context —
     * source and cursor — so next() resumes from there. A track we skipped INTO from the Play-Next
     * overlay is reinstated at the front of up-next: songs only leave the queue via
     * [removeUpcoming], never by navigation. With no history, stays on current.
     */
    suspend fun previous(): Track? {
        val entry = history.removeLastOrNull() ?: return _current.value
        if (currentFromOverlay) _current.value?.let { playNext.addFirst(it) }
        source = entry.source
        cursor = entry.cursor
        currentFromOverlay = entry.fromOverlay
        _current.value = entry.track
        return entry.track
    }

    private fun pushHistory(track: Track) {
        history.addLast(HistoryEntry(track, source, cursor, currentFromOverlay))
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    private companion object {
        const val MAX_HISTORY = 100
    }

    /** One visible upcoming item; [fromOverlay] marks Play-Next items vs Source-derived ones. */
    data class UpcomingItem(val track: Track, val fromOverlay: Boolean)

    /** The next [n] upcoming items (overlay, then Source) — what the queue page displays. */
    suspend fun upcomingItems(n: Int): List<UpcomingItem> {
        val out = ArrayList<UpcomingItem>(n)
        playNext.take(n).forEach { out.add(UpcomingItem(it, fromOverlay = true)) }
        var i = cursor + 1
        val size = source.size
        while (out.size < n && size != null && i < size) {
            source.get(i)?.let { if (it.id !in skippedIds) out.add(UpcomingItem(it, fromOverlay = false)) }
            i++
        }
        return out
    }

    /** The next [n] upcoming tracks — the rolling prefetch window. */
    suspend fun upcoming(n: Int): List<Track> = upcomingItems(n).map { it.track }

    /**
     * The next [n] tracks in the order they will actually play, current first — the window handed
     * to the detached player, which advances through it on its own once the tool screen is gone.
     */
    suspend fun playbackWindow(n: Int): List<Track> {
        val current = _current.value ?: return emptyList()
        if (repeat() == RepeatMode.ONE) return List(n) { current }
        val window = ArrayList<Track>(n)
        window.add(current)
        window.addAll(upcoming(n - 1))
        if (repeat() == RepeatMode.ALL && window.size < n) {
            val lap = sourceLap()
            var i = 0
            while (lap.isNotEmpty() && window.size < n) {
                window.add(lap[i % lap.size])
                i++
            }
        }
        return window
    }

    /** The source's playable tracks from the top — one wrap of Repeat ALL. Empty if unbounded. */
    private suspend fun sourceLap(): List<Track> {
        val size = source.size ?: return emptyList()
        val lap = ArrayList<Track>(size)
        for (i in 0 until size) {
            val t = source.get(i) ?: break
            if (t.id !in skippedIds) lap.add(t)
        }
        return lap
    }

    /** Remove the item at [index] of the [upcomingItems] view. Source items go to the skip-set. */
    suspend fun removeUpcoming(index: Int) {
        if (index < 0) return
        if (index < playNext.size) {
            playNext.removeAt(index)
            return
        }
        sourceItemAt(index - playNext.size)?.let { skippedIds.add(it.id) }
    }

    /** Pull the item at [index] of the [upcomingItems] view to the FRONT of the Play-Next overlay. */
    suspend fun promoteToNext(index: Int) {
        if (index < 0) return
        if (index < playNext.size) {
            val t = playNext.removeAt(index)
            playNext.addFirst(t)
            return
        }
        sourceItemAt(index - playNext.size)?.let {
            skippedIds.add(it.id) // won't replay at its source position
            playNext.addFirst(it)
        }
    }

    /**
     * Move the item at [from] in the [upcomingItems] view to position [to] (insert semantics).
     * The touched prefix is materialized into the explicit overlay first — after that the visible
     * queue IS a real list, while everything past it stays lazy.
     */
    suspend fun moveUpcoming(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0) return
        materializeUpcoming(maxOf(from, to) + 1)
        if (from >= playNext.size || to >= playNext.size) return
        val moved = playNext.removeAt(from)
        playNext.add(to, moved)
    }

    /** Pull view items into the overlay (in order, skip-setting their Source copies) until it holds [count]. */
    private suspend fun materializeUpcoming(count: Int) {
        while (playNext.size < count) {
            val head = sourceItemAt(0) ?: return
            skippedIds.add(head.id)
            playNext.addLast(head)
        }
    }

    /** The [n]-th not-yet-skipped Source track after the cursor (the source part of the view). */
    private suspend fun sourceItemAt(n: Int): Track? {
        var remaining = n
        var i = cursor + 1
        val size = source.size
        while (size != null && i < size) {
            val t = source.get(i) ?: return null
            if (t.id !in skippedIds) {
                if (remaining == 0) return t
                remaining--
            }
            i++
        }
        return null
    }
}

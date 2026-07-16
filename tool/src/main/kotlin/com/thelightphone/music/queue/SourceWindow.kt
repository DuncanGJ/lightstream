package com.thelightphone.music.queue

import com.thelightphone.music.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The realized prefix of a [Source], for scrolling lists over lazy data ("all songs A→Z"): call
 * [ensure] as the user scrolls and render [items]; the window keeps itself ahead of the scroll.
 *
 * The end is only declared when the source's size is known and reached — a null item with the
 * size still unknown is a transient failure (an offline, uncached page): realization pauses and
 * the next [ensure] retries. Safe to call [ensure] from overlapping coroutines; realization is
 * internally serialized.
 */
class SourceWindow(private val source: Source) {

    private val _items = MutableStateFlow<List<Track>>(emptyList())
    val items: StateFlow<List<Track>> = _items

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private val mutex = Mutex()

    /** Realize items until at least [count] are available, the source ends, or a page fails. */
    suspend fun ensure(count: Int) = mutex.withLock {
        if (_endReached.value) return@withLock
        val realized = _items.value.toMutableList()
        var changed = false
        while (realized.size < count) {
            val next = source.get(realized.size)
            if (next == null) {
                val size = source.size
                if (size != null && realized.size >= size) _endReached.value = true
                break
            }
            realized.add(next)
            changed = true
        }
        if (changed) _items.value = realized.toList()
    }
}

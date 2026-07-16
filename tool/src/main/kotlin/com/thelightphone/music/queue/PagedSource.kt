package com.thelightphone.music.queue

import com.thelightphone.music.model.Track

/**
 * A [Source] realized lazily in pages — the sliding-window backing for very large lists ("all
 * songs A→Z"). [size] is unknown (null) until a short or empty page reveals the end. Pages are
 * cached for the lifetime of the source; [fetchPage] is typically a Subsonic search3 page.
 */
class PagedSource(
    override val title: String,
    private val pageSize: Int = 100,
    private val fetchPage: suspend (offset: Int, count: Int) -> List<Track>,
) : Source {

    private val pages = mutableMapOf<Int, List<Track>>()
    private var knownSize: Int? = null

    override val size: Int? get() = knownSize

    override suspend fun get(index: Int): Track? {
        if (index < 0) return null
        knownSize?.let { if (index >= it) return null }
        val page = index / pageSize
        val tracks = pages[page] ?: fetchPage(page * pageSize, pageSize).also { fetched ->
            pages[page] = fetched
            if (fetched.size < pageSize) {
                val end = page * pageSize + fetched.size
                knownSize = knownSize?.coerceAtMost(end) ?: end
            }
        }
        return tracks.getOrNull(index - page * pageSize)
    }
}

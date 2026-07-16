package com.thelightphone.music.queue

import com.thelightphone.music.model.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private fun track(id: String) = Track(
    id = id,
    title = "Title $id",
    artist = null,
    album = null,
    albumId = null,
    trackNumber = null,
    durationSec = null,
)

class PagedSourceTest {

    @Test
    fun `get fetches the page containing the requested index`() = runBlocking {
        val fetchedOffsets = mutableListOf<Int>()
        val source = PagedSource("Songs", pageSize = 10) { offset, count ->
            fetchedOffsets.add(offset)
            (offset until offset + count).map { track("s$it") }
        }

        assertEquals("s25", source.get(25)?.id)
        assertEquals(listOf(20), fetchedOffsets, "only the page containing index 25 should be fetched")
    }

    @Test
    fun `pages are cached so repeated gets fetch once`() = runBlocking {
        val fetchedOffsets = mutableListOf<Int>()
        val source = PagedSource("Songs", pageSize = 10) { offset, count ->
            fetchedOffsets.add(offset)
            (offset until offset + count).map { track("s$it") }
        }

        source.get(5)
        source.get(7)
        source.get(5)

        assertEquals(listOf(0), fetchedOffsets)
    }

    @Test
    fun `a short page reveals the end of the library`() = runBlocking {
        val source = PagedSource("Songs", pageSize = 10) { offset, _ ->
            if (offset == 0) (0 until 7).map { track("s$it") } else emptyList()
        }

        assertEquals("s6", source.get(6)?.id)
        assertEquals(null, source.get(7), "past the end must be null")
        assertEquals(7, source.size, "size becomes known once the end is seen")
    }
}

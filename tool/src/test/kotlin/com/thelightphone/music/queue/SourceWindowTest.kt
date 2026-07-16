package com.thelightphone.music.queue

import com.thelightphone.music.model.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun track(id: String) = Track(
    id = id,
    title = "Title $id",
    artist = null,
    album = null,
    albumId = null,
    trackNumber = null,
    durationSec = null,
)

class SourceWindowTest {

    @Test
    fun `ensure realizes at least the requested count`() = runBlocking {
        val window = SourceWindow(ListSource("S", (0 until 10).map { track("s$it") }))

        window.ensure(4)

        assertEquals(listOf("s0", "s1", "s2", "s3"), window.items.value.map { it.id })
        assertFalse(window.endReached.value, "more items remain — the end must not be declared")
    }

    @Test
    fun `reaching the true end of the source sets endReached`() = runBlocking {
        val window = SourceWindow(
            PagedSource("S", pageSize = 2) { offset, count ->
                (0 until 3).map { track("s$it") }.drop(offset).take(count)
            },
        )

        window.ensure(10)

        assertEquals(listOf("s0", "s1", "s2"), window.items.value.map { it.id })
        assertTrue(window.endReached.value)
    }

    @Test
    fun `a transient page failure pauses realization without declaring the end`() = runBlocking {
        var failNext = true
        val window = SourceWindow(
            PagedSource("S", pageSize = 2) { offset, count ->
                if (offset > 0 && failNext) null // page 1 is offline on the first try
                else (0 until 6).map { track("s$it") }.drop(offset).take(count)
            },
        )

        window.ensure(4) // realizes page 0, then hits the failing page

        assertEquals(listOf("s0", "s1"), window.items.value.map { it.id })
        assertFalse(window.endReached.value, "a failed page is not the end of the library")

        failNext = false
        window.ensure(4) // back online: the same request now completes

        assertEquals(listOf("s0", "s1", "s2", "s3"), window.items.value.map { it.id })
    }

    @Test
    fun `an already-satisfied ensure does not refetch`() = runBlocking {
        var fetches = 0
        val window = SourceWindow(
            PagedSource("S", pageSize = 2) { offset, count ->
                fetches++
                (0 until 6).map { track("s$it") }.drop(offset).take(count)
            },
        )

        window.ensure(2)
        window.ensure(2)

        assertEquals(1, fetches, "a satisfied window must not touch the source again")
    }
}

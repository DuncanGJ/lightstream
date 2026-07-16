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

class QueueTest {

    @Test
    fun `previous returns the last actually played track across sources`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2"), track("a3")))
        val albumB = ListSource("Album B", (1..6).map { track("b$it") })

        queue.start(albumA, 0) // playing a1
        queue.start(albumB, 4) // user taps b5 mid-album

        val prev = queue.previous()

        assertEquals("a1", prev?.id, "previous should return the last played track, not the album neighbour")
        assertEquals("a1", queue.current.value?.id)
    }

    @Test
    fun `previous restores the source context so next resumes there`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2"), track("a3")))
        val albumB = ListSource("Album B", listOf(track("b1"), track("b2")))

        queue.start(albumA, 1) // a2
        queue.start(albumB, 0) // b1
        queue.previous()       // back to a2, in Album A's context

        assertEquals("a3", queue.next()?.id, "next after previous should resume the restored album")
    }

    @Test
    fun `previous with empty history stays on the current track`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2"), track("a3")))

        queue.start(albumA, 1) // a2, nothing played before

        assertEquals("a2", queue.previous()?.id, "no history: previous should not walk the album")
        assertEquals("a2", queue.current.value?.id)
    }

    @Test
    fun `tracks reached via next including play-next overlay enter history`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2"), track("a3")))

        queue.start(albumA, 0)          // a1
        queue.addPlayNext(track("x"))
        queue.next()                    // x (overlay)
        queue.next()                    // a2 (source resumes)

        assertEquals("x", queue.previous()?.id, "first previous should return the overlay track")
        assertEquals("a1", queue.previous()?.id, "second previous should return the album opener")
    }

    @Test
    fun `history is capped at the last 100 plays`() = runBlocking {
        val queue = Queue()
        val big = ListSource("Big", (0 until 130).map { track("t$it") })

        queue.start(big, 0)
        repeat(120) { queue.next() }     // current = t120
        repeat(150) { queue.previous() } // can pop at most the cap

        assertEquals("t20", queue.current.value?.id, "only the last 100 plays are retained")
    }

    @Test
    fun `removeUpcoming drops a play-next item`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2")))

        queue.start(albumA, 0)
        queue.addPlayNext(track("x"))
        queue.addPlayNext(track("y"))

        queue.removeUpcoming(0) // drop x

        assertEquals(listOf("y", "a2"), queue.upcomingItems(5).map { it.track.id })
        assertEquals("y", queue.next()?.id)
    }

    @Test
    fun `removeUpcoming skips a source track when advancing`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2"), track("a3")))

        queue.start(albumA, 0)     // a1; upcoming view = [a2, a3]
        queue.removeUpcoming(0)    // remove a2 (source-derived)

        assertEquals(listOf("a3"), queue.upcomingItems(5).map { it.track.id })
        assertEquals("a3", queue.next()?.id, "advancing must skip the removed track")
    }

    @Test
    fun `promoteToNext pulls a source track to the front of up-next without replaying it later`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2"), track("a3"), track("a4")))

        queue.start(albumA, 0) // a1; view [a2, a3, a4]
        queue.promoteToNext(2) // promote a4

        assertEquals(listOf("a4", "a2", "a3"), queue.upcomingItems(5).map { it.track.id })
        assertEquals("a4", queue.next()?.id)
        assertEquals("a2", queue.next()?.id)
        assertEquals("a3", queue.next()?.id)
        assertEquals(null, queue.next(), "a4 must not replay at its original source position")
    }

    @Test
    fun `skipping forward then back reinstates the skipped track in up-next`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2")))

        queue.start(albumA, 0)          // a1
        queue.addPlayNext(track("x"))
        queue.next()                    // x plays (drained from up-next)
        queue.previous()                // back to a1

        assertEquals("a1", queue.current.value?.id)
        assertEquals(
            listOf("x", "a2"),
            queue.upcomingItems(5).map { it.track.id },
            "the skipped-into track must return to the front of up-next — songs only leave via remove",
        )
        assertEquals("x", queue.next()?.id, "the forward path must replay identically")
    }

    @Test
    fun `starting a new source keeps hand-queued play-next items`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1")))
        val albumB = ListSource("Album B", listOf(track("b1"), track("b2")))

        queue.start(albumA, 0)
        queue.addPlayNext(track("x"))
        queue.start(albumB, 0)          // new context must not wipe the queue

        assertEquals("x", queue.upcomingItems(5).first().track.id)
    }

    @Test
    fun `stop unloads the current track to the front of up-next`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2")))
        queue.start(albumA, 0)
        queue.addPlayNext(track("x"))

        queue.stop()

        assertEquals(null, queue.current.value, "nothing is playing after stop")
        assertEquals(
            listOf("a1", "x", "a2"),
            queue.upcomingItems(5).map { it.track.id },
            "the stopped song returns to the front — the queue loses nothing",
        )
    }

    @Test
    fun `finish moves the current track to history and clears current`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2")))
        queue.start(albumA, 0)

        queue.finish()

        assertEquals(null, queue.current.value)
        assertEquals("a1", queue.previous()?.id, "a finished track must be reachable via history")
    }

    @Test
    fun `moveUpcoming swaps items across the overlay-source boundary`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", listOf(track("a1"), track("a2"), track("a3")))

        queue.start(albumA, 0)          // a1; view [a2, a3]
        queue.addPlayNext(track("x"))   // view [x, a2, a3]

        queue.moveUpcoming(0, 1)        // x moves below a2

        assertEquals(listOf("a2", "x", "a3"), queue.upcomingItems(5).map { it.track.id })
        assertEquals("a2", queue.next()?.id)
        assertEquals("x", queue.next()?.id)
        assertEquals("a3", queue.next()?.id)
    }

    @Test
    fun `moveUpcoming inserts a source track at an arbitrary earlier position`() = runBlocking {
        val queue = Queue()
        val albumA = ListSource("Album A", (1..5).map { track("a$it") })

        queue.start(albumA, 0)   // a1; view [a2, a3, a4, a5]
        queue.moveUpcoming(2, 0) // a4 jumps to the front

        assertEquals(listOf("a4", "a2", "a3", "a5"), queue.upcomingItems(9).map { it.track.id })
        assertEquals("a4", queue.next()?.id)
        assertEquals("a2", queue.next()?.id)
        assertEquals("a3", queue.next()?.id)
        assertEquals("a5", queue.next()?.id, "a4 must not replay at its source position")
    }

    @Test
    fun `queue advances through an unknown-size paged source to its end`() = runBlocking {
        val queue = Queue()
        val songs = PagedSource("Songs", pageSize = 3) { offset, count ->
            (0 until 7).map { track("s$it") }.drop(offset).take(count)
        }

        queue.start(songs, 0)
        val played = mutableListOf<String>()
        while (true) {
            played.add(queue.current.value!!.id)
            queue.next() ?: break
        }

        assertEquals((0 until 7).map { "s$it" }, played, "should walk the whole paged library then stop")
    }
}

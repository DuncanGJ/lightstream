package com.thelightphone.music.playback

import com.thelightphone.music.cache.CacheDao
import com.thelightphone.music.cache.CachedTrackEntity
import com.thelightphone.music.cache.TrackCache
import com.thelightphone.music.model.Track
import com.thelightphone.music.queue.ListSource
import com.thelightphone.music.subsonic.SubsonicClient
import com.thelightphone.music.subsonic.SubsonicCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
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

private class FakeCacheDao : CacheDao {
    val rows = LinkedHashMap<String, CachedTrackEntity>()
    override suspend fun get(id: String): CachedTrackEntity? = rows[id]
    override suspend fun upsert(entity: CachedTrackEntity) { rows[entity.trackId] = entity }
    override suspend fun touch(id: String, t: Long) { rows[id]?.let { rows[id] = it.copy(lastAccessedAt = t) } }
    override suspend fun totalBytes(): Long? = if (rows.isEmpty()) null else rows.values.sumOf { it.bytes }
    override suspend fun lruOrder(): List<CachedTrackEntity> = rows.values.sortedBy { it.lastAccessedAt }
    override suspend fun deleteById(id: String) { rows.remove(id) }
    override suspend fun count(): Int = rows.size
    override suspend fun all(): List<CachedTrackEntity> = rows.values.toList()
    override suspend fun clearAll() { rows.clear() }
}

/** The playback seam, faked — records what the Player asks of it. */
private class FakePlaybackController : PlaybackController {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state
    override var onCompletion: (() -> Unit)? = null

    val played = mutableListOf<AudioSource>()
    var stopped = false

    override fun play(source: AudioSource) {
        played.add(source)
        _state.value = PlaybackState(PlaybackStatus.PLAYING, positionMs = 0, durationMs = 100_000)
    }
    override fun pause() { _state.value = _state.value.copy(status = PlaybackStatus.PAUSED) }
    override fun resume() { _state.value = _state.value.copy(status = PlaybackStatus.PLAYING) }
    override fun stop() { stopped = true; _state.value = PlaybackState() }
    override fun release() {}

    fun setPosition(ms: Int) { _state.value = _state.value.copy(positionMs = ms) }
    fun completeCurrent() { onCompletion?.invoke() }
}

private class Fixture(scope: CoroutineScope) {
    val engine = MockEngine { respond("12345678", HttpStatusCode.OK) }
    private val http = HttpClient(engine)
    val subsonic = SubsonicClient(SubsonicCredentials("https://s.test", "u", "p"), http)
    val cache = TrackCache(FakeCacheDao(), http, subsonic, createTempDirectory("player-test").toFile())
    val controller = FakePlaybackController()
    val player = Player(cache, controller, scope, resolveRemote = { AudioSource.Remote(subsonic.streamUrl(it)) })
}

/** The observable queue window, as ids — the queue page's exact view of the Player. */
private fun Player.upcomingIds(): List<String> = upcoming.value.map { it.track.id }

private suspend fun awaitUntil(what: String, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 2_000
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
        delay(10)
    }
}

private fun playedId(source: AudioSource): String? = when (source) {
    is AudioSource.Remote -> Url(source.url).parameters["id"]
    is AudioSource.Local -> source.path.substringAfterLast('/').removeSuffix(".mp3")
}

class PlayerTest {

    @Test
    fun `an uncached track streams via its self-authenticating URL`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)

        awaitUntil("first play") { f.controller.played.size == 1 }

        val played = f.controller.played.single()
        assertTrue(played is AudioSource.Remote, "uncached tracks must stream")
        assertEquals("a1", playedId(played))
        f.player.release()
    }

    @Test
    fun `a cached track plays from the local file`() = runBlocking {
        val f = Fixture(this)
        f.cache.prefetch(listOf(track("a1")))

        f.player.playFrom(ListSource("A", listOf(track("a1"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }

        assertTrue(f.controller.played.single() is AudioSource.Local, "cached tracks must play from disk")
        f.player.release()
    }

    @Test
    fun `completion advances to the next track automatically`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }

        f.controller.completeCurrent()
        awaitUntil("auto-advance") { f.controller.played.size == 2 }

        assertEquals("a2", playedId(f.controller.played.last()))
        f.player.release()
    }

    @Test
    fun `skipPrevious always steps back through history regardless of position`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }
        f.controller.completeCurrent()
        awaitUntil("auto-advance") { f.controller.played.size == 2 }

        f.controller.setPosition(5_000) // deep into the song — must still go BACK, never restart
        f.player.skipPrevious()
        awaitUntil("previous") { f.controller.played.size == 3 }

        assertEquals("a1", playedId(f.controller.played.last()))
        f.player.release()
    }

    @Test
    fun `skipPrevious steps back through history when just started`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }
        f.controller.completeCurrent()
        awaitUntil("auto-advance") { f.controller.played.size == 2 }

        f.controller.setPosition(1_000)
        f.player.skipPrevious()
        awaitUntil("previous") { f.controller.played.size == 3 }

        assertEquals("a1", playedId(f.controller.played.last()))
        f.player.release()
    }

    @Test
    fun `adding to the queue while idle queues without starting playback`() = runBlocking {
        val f = Fixture(this)

        f.player.addToQueue(track("x")) // nothing is playing
        awaitUntil("queued") { f.player.upcomingIds() == listOf("x") }

        assertTrue(f.controller.played.isEmpty(), "nothing may start on its own")
        assertTrue(f.engine.requestHistory.isEmpty(), "idle queueing must not prefetch or stream")
        f.player.release()
    }

    @Test
    fun `stop kills playback but retains the queue with the stopped song at front`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }
        f.player.addToQueue(track("x"))

        f.player.stop()
        awaitUntil("stopped") { f.controller.stopped }

        assertEquals(null, f.player.current.value, "now-playing must clear so the bar hides")
        awaitUntil("queue retained") { f.player.upcomingIds() == listOf("a1", "x", "a2") }
        f.player.release()
    }

    @Test
    fun `adding to the queue while playing only queues`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }

        f.player.addToQueue(track("x"))
        awaitUntil("queued") { f.player.upcomingIds().firstOrNull() == "x" }
        delay(100) // give any (wrong) playback a chance to happen

        assertEquals(1, f.controller.played.size, "the current song must keep playing")
        f.player.release()
    }

    @Test
    fun `the queue resumes via playNextInQueue after it ends`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }
        f.controller.completeCurrent()
        awaitUntil("stop at end") { f.controller.stopped }

        f.player.addToQueue(track("x"))
        delay(100)
        assertEquals(1, f.controller.played.size, "adding must not auto-play")
        assertEquals(null, f.player.current.value, "a finished song leaves now-playing")

        f.player.playNextInQueue()
        awaitUntil("resume") { f.controller.played.size == 2 }
        assertEquals("x", playedId(f.controller.played.last()))

        f.player.skipPrevious()
        awaitUntil("previous") { f.controller.played.size == 3 }
        assertEquals("a1", playedId(f.controller.played.last()), "the finished song must be in history")
        f.player.release()
    }

    @Test
    fun `repeat ONE replays on completion but the next button still advances`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }

        f.player.cycleRepeat() // OFF → ALL
        f.player.cycleRepeat() // ALL → ONE
        f.controller.completeCurrent()
        awaitUntil("replay") { f.controller.played.size == 2 }
        assertEquals("a1", playedId(f.controller.played.last()), "natural completion must replay")

        f.player.skipNext()
        awaitUntil("user skip") { f.controller.played.size == 3 }
        assertEquals("a2", playedId(f.controller.played.last()), "the next button must escape Repeat One")
        f.player.release()
    }

    @Test
    fun `queue edits re-emit the observable upcoming window`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"), track("a3"))), 0)
        awaitUntil("initial window") { f.player.upcomingIds() == listOf("a2", "a3") }

        f.player.moveUpcoming(0, 1)
        awaitUntil("move re-emits") { f.player.upcomingIds() == listOf("a3", "a2") }

        f.player.removeUpcoming(1)
        awaitUntil("remove re-emits") { f.player.upcomingIds() == listOf("a3") }
        f.player.release()
    }

    @Test
    fun `queue edits re-aim the rolling prefetch window`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }

        f.player.addToQueue(track("x")) // now up next — the prefetch window must include it
        awaitUntil("x prefetched") {
            f.engine.requestHistory.any { it.url.parameters["id"] == "x" }
        }
        f.player.release()
    }

    @Test
    fun `reaching the end of the queue stops playback`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"))), 0)
        awaitUntil("first play") { f.controller.played.size == 1 }

        f.controller.completeCurrent()
        awaitUntil("stop") { f.controller.stopped }

        assertEquals(1, f.controller.played.size, "nothing further must play")
        f.player.release()
    }
}

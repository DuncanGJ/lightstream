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

/**
 * The playback seam, faked — a detached player that owns a pushed window and walks it by itself,
 * exactly as media3 does inside the SDK's audio service once the tool screen is gone.
 */
private class FakePlaybackController : PlaybackController {
    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state
    override var onAdvance: ((Int) -> Unit)? = null
    override var onWindowEnd: (() -> Unit)? = null

    /** Every window handed over — a new entry means playback was re-pointed, not just advanced. */
    val windows = mutableListOf<List<PlayableTrack>>()
    var index = 0
        private set
    var stopped = false

    override fun play(window: List<PlayableTrack>) {
        windows.add(window)
        index = 0
        stopped = false
        _state.value = PlaybackState(PlaybackStatus.PLAYING, positionMs = 0, durationMs = 100_000)
    }
    override fun pause() { _state.value = _state.value.copy(status = PlaybackStatus.PAUSED) }
    override fun resume() { _state.value = _state.value.copy(status = PlaybackStatus.PLAYING) }
    override fun stop() { stopped = true; _state.value = PlaybackState() }
    override fun seekTo(positionMs: Int) {
        seeks.add(positionMs)
        _state.value = _state.value.copy(positionMs = positionMs.coerceIn(0, _state.value.durationMs))
    }
    override fun release() {}

    /** Every seek the player asked for, unclamped — the seam hands over what the tool decided. */
    val seeks = mutableListOf<Int>()

    fun setPosition(ms: Int) { _state.value = _state.value.copy(positionMs = ms) }

    /** The current entry failed: like the SDK, the player stops rather than advancing. */
    fun fail(kind: PlaybackErrorKind) {
        _state.value = _state.value.copy(status = PlaybackStatus.ERROR, error = PlaybackError(kind, "TEST"))
    }

    /** The current entry finishes: the player steps through its window, or runs out of it. */
    fun completeCurrent() {
        val window = windows.lastOrNull() ?: return
        if (index + 1 < window.size) {
            index++
            onAdvance?.invoke(index)
        } else {
            _state.value = _state.value.copy(status = PlaybackStatus.ENDED)
            onWindowEnd?.invoke()
        }
    }

    /** What is coming out of the speaker right now. */
    val nowPlaying: PlayableTrack? get() = windows.lastOrNull()?.getOrNull(index)
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

/** The track ids of the window the detached player currently holds. */
private fun FakePlaybackController.windowIds(): List<String?> =
    windows.lastOrNull().orEmpty().map { playedId(it.source) }

private fun FakePlaybackController.nowPlayingId(): String? = nowPlaying?.let { playedId(it.source) }

class PlayerTest {

    @Test
    fun `playing hands the detached player the whole upcoming window, not one track`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"), track("a3"))), 0)

        awaitUntil("window pushed") { f.controller.windows.isNotEmpty() }

        assertEquals(
            listOf("a1", "a2", "a3"),
            f.controller.windowIds(),
            "the player must be able to keep going without the tool screen",
        )
        f.player.release()
    }

    @Test
    fun `the window carries metadata for the platform's now-playing surfaces`() = runBlocking {
        val f = Fixture(this)
        val song = track("a1").copy(title = "Nightswimming", artist = "R.E.M.", album = "Automatic")

        f.player.playFrom(ListSource("A", listOf(song)), 0)
        awaitUntil("window pushed") { f.controller.windows.isNotEmpty() }

        val entry = f.controller.windows.last().single()
        assertEquals("Nightswimming", entry.title)
        assertEquals("R.E.M.", entry.artist)
        assertEquals("Automatic", entry.album)
        f.player.release()
    }

    @Test
    fun `an uncached track streams via its self-authenticating URL`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)

        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        val played = f.controller.nowPlaying!!.source
        assertTrue(played is AudioSource.Remote, "uncached tracks must stream")
        assertEquals("a1", playedId(played))
        f.player.release()
    }

    @Test
    fun `a cached track plays from the local file`() = runBlocking {
        val f = Fixture(this)
        f.cache.prefetch(listOf(track("a1")))

        f.player.playFrom(ListSource("A", listOf(track("a1"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        assertTrue(f.controller.nowPlaying!!.source is AudioSource.Local, "cached tracks must play from disk")
        f.player.release()
    }

    @Test
    fun `the queue cursor follows the player advancing through the window on its own`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.controller.completeCurrent() // the detached player moved on by itself

        awaitUntil("cursor follows") { f.player.current.value?.id == "a2" }
        assertEquals("a2", f.controller.nowPlayingId())
        assertEquals(1, f.controller.windows.size, "an ordinary advance must not re-point playback")
        f.player.release()
    }

    @Test
    fun `skipPrevious always steps back through history regardless of position`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }
        f.controller.completeCurrent()
        awaitUntil("auto-advance") { f.player.current.value?.id == "a2" }

        f.controller.setPosition(5_000) // deep into the song — must still go BACK, never restart
        f.player.skipPrevious()
        awaitUntil("previous") { f.controller.nowPlayingId() == "a1" }

        assertEquals("a1", f.player.current.value?.id)
        f.player.release()
    }

    @Test
    fun `skipPrevious steps back through history when just started`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }
        f.controller.completeCurrent()
        awaitUntil("auto-advance") { f.player.current.value?.id == "a2" }

        f.controller.setPosition(1_000)
        f.player.skipPrevious()
        awaitUntil("previous") { f.controller.nowPlayingId() == "a1" }
        f.player.release()
    }

    @Test
    fun `adding to the queue while idle queues without starting playback`() = runBlocking {
        val f = Fixture(this)

        f.player.addToQueue(track("x")) // nothing is playing
        awaitUntil("queued") { f.player.upcomingIds() == listOf("x") }

        assertTrue(f.controller.windows.isEmpty(), "nothing may start on its own")
        assertTrue(f.engine.requestHistory.isEmpty(), "idle queueing must not prefetch or stream")
        f.player.release()
    }

    @Test
    fun `stop kills playback but retains the queue with the stopped song at front`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }
        f.player.addToQueue(track("x"))

        f.player.stop()
        awaitUntil("stopped") { f.controller.stopped }

        assertEquals(null, f.player.current.value, "now-playing must clear so the bar hides")
        awaitUntil("queue retained") { f.player.upcomingIds() == listOf("a1", "x", "a2") }
        f.player.release()
    }

    @Test
    fun `adding to the queue while playing never restarts the current song`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.player.addToQueue(track("x"))
        awaitUntil("queued") { f.player.upcomingIds().firstOrNull() == "x" }
        delay(100) // give any (wrong) re-point a chance to happen

        assertEquals(1, f.controller.windows.size, "re-pointing mid-song would restart it")
        assertEquals("a1", f.controller.nowPlayingId(), "the current song must keep playing")
        f.player.release()
    }

    @Test
    fun `a queue edit reaches the detached player at the next track boundary`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.player.addToQueue(track("x")) // Play Next: x must play before a2
        awaitUntil("queued") { f.player.upcomingIds().firstOrNull() == "x" }
        f.controller.completeCurrent()

        awaitUntil("edited window pushed") { f.controller.nowPlayingId() == "x" }
        assertEquals("x", f.player.current.value?.id)
        f.player.release()
    }

    @Test
    fun `the queue resumes via playNextInQueue after it ends`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }
        f.controller.completeCurrent()
        awaitUntil("stop at end") { f.controller.stopped }

        f.player.addToQueue(track("x"))
        delay(100)
        assertEquals(1, f.controller.windows.size, "adding must not auto-play")
        assertEquals(null, f.player.current.value, "a finished song leaves now-playing")

        f.player.playNextInQueue()
        awaitUntil("resume") { f.controller.nowPlayingId() == "x" }

        f.player.skipPrevious()
        awaitUntil("previous") { f.controller.nowPlayingId() == "a1" }
        f.player.release()
    }

    @Test
    fun `repeat ONE replays on completion but the next button still advances`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.player.cycleRepeat() // OFF → ALL
        f.player.cycleRepeat() // ALL → ONE
        f.controller.completeCurrent()
        awaitUntil("replay") { f.controller.nowPlayingId() == "a1" }
        assertEquals("a1", f.player.current.value?.id, "natural completion must replay")

        f.player.skipNext()
        awaitUntil("user skip") { f.controller.nowPlayingId() == "a2" }
        assertEquals("a2", f.player.current.value?.id, "the next button must escape Repeat One")
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
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

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
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.controller.completeCurrent()
        awaitUntil("stop") { f.controller.stopped }

        assertEquals(1, f.controller.windows.size, "nothing further must play")
        assertEquals(null, f.player.current.value, "a finished queue leaves now-playing")
        f.player.release()
    }

    @Test
    fun `a long queue is refilled before the detached player runs out of window`() = runBlocking {
        val f = Fixture(this)
        val songs = (1..Player.WINDOW_DEPTH + 10).map { track("a$it") }
        f.player.playFrom(ListSource("A", songs), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }
        assertEquals(Player.WINDOW_DEPTH, f.controller.windows.last().size, "the window is bounded")

        repeat(Player.WINDOW_DEPTH - Player.REFILL_THRESHOLD) { f.controller.completeCurrent() }

        awaitUntil("refilled") { f.controller.windows.size == 2 }
        assertEquals(
            "a${Player.WINDOW_DEPTH - Player.REFILL_THRESHOLD + 1}",
            f.controller.nowPlayingId(),
            "the refilled window must open on the track that is playing",
        )
        f.player.release()
    }

    @Test
    fun `seeking moves within the current track without re-pointing the window`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.player.seekTo(42_000)
        awaitUntil("seek reached the player") { f.player.state.value.positionMs == 42_000 }

        assertEquals(listOf(42_000), f.controller.seeks)
        assertEquals(1, f.controller.windows.size, "a seek must never restart the window")
        assertEquals("a1", f.controller.nowPlayingId())
        f.player.release()
    }

    @Test
    fun `skip forward and back nudge the position by fifteen seconds`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }
        f.controller.setPosition(30_000)

        f.player.skipForward()
        awaitUntil("forward") { f.player.state.value.positionMs == 45_000 }
        f.player.skipBack()
        awaitUntil("back") { f.player.state.value.positionMs == 30_000 }

        assertEquals(listOf(45_000, 30_000), f.controller.seeks)
        assertEquals(1, f.controller.windows.size, "nudging must never restart the window")
        f.player.release()
    }

    @Test
    fun `nudges clamp to the track bounds`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"))), 0) // fake duration: 100s
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.controller.setPosition(5_000)
        f.player.skipBack()
        awaitUntil("clamped at start") { f.controller.seeks == listOf(0) }

        f.controller.setPosition(95_000)
        f.player.skipForward()
        awaitUntil("clamped at end") { f.controller.seeks == listOf(0, 100_000) }
        f.player.release()
    }

    @Test
    fun `play on a failed track retries it rather than skipping`() = runBlocking {
        val f = Fixture(this)
        f.player.playFrom(ListSource("A", listOf(track("a1"), track("a2"))), 0)
        awaitUntil("first play") { f.controller.windows.isNotEmpty() }

        f.controller.fail(PlaybackErrorKind.SOURCE) // the stream URL did not answer
        awaitUntil("error surfaced") { f.player.state.value.error?.kind == PlaybackErrorKind.SOURCE }
        f.player.togglePlayPause()

        awaitUntil("retried") { f.controller.windows.size == 2 }
        assertEquals("a1", f.controller.nowPlayingId(), "the failed track is retried, not skipped")
        assertEquals(PlaybackStatus.PLAYING, f.player.state.value.status)
        f.player.release()
    }
}

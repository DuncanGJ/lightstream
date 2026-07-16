package com.thelightphone.music.app

import com.thelightphone.music.subsonic.SubsonicCredentials
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MusicSettingsTest {

    @Test
    fun `saved credentials load back identically`() = runBlocking {
        val store = FakeDataStore()
        val creds = SubsonicCredentials("https://music.example.com", "alice", "sw0rdfish")

        MusicSettings.save(store, creds)

        assertEquals(creds, MusicSettings.load(store))
    }

    @Test
    fun `load returns null until credentials are complete`() = runBlocking {
        assertNull(MusicSettings.load(FakeDataStore()), "no stored creds must mean not configured")
    }

    @Test
    fun `tunables round-trip and default sensibly`() = runBlocking {
        val store = FakeDataStore()

        assertEquals(Tunables(cacheBudgetMb = 500, prefetchDepth = 15, streamBitrateKbps = 256), MusicSettings.loadTunables(store))

        val custom = Tunables(cacheBudgetMb = 1000, prefetchDepth = 5, streamBitrateKbps = 128)
        MusicSettings.saveTunables(store, custom)
        assertEquals(custom, MusicSettings.loadTunables(store))
    }

    @Test
    fun `queued flash duration round-trips and defaults to 800ms`() = runBlocking {
        val store = FakeDataStore()

        assertEquals(800L, MusicSettings.loadQueuedFlashMs(store))

        MusicSettings.saveQueuedFlashMs(store, 400L)
        assertEquals(400L, MusicSettings.loadQueuedFlashMs(store))
    }
}

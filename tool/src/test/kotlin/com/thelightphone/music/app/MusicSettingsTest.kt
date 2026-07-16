package com.thelightphone.music.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.thelightphone.music.subsonic.SubsonicCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(emptyPreferences())
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.value = next
        return next
    }
}

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

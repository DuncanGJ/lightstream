package com.thelightphone.music.ui

import com.thelightphone.music.app.AppControl
import com.thelightphone.music.app.FakeDataStore
import com.thelightphone.music.app.MusicSettings
import com.thelightphone.music.app.Session
import com.thelightphone.music.app.Tunables
import com.thelightphone.music.subsonic.SubsonicCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The [AppControl] seam, faked — records what Settings asks of the running app. */
private class FakeAppControl : AppControl {
    override val session = MutableStateFlow<Session?>(null)
    var credentials: SubsonicCredentials? = null
    var pingResult: String? = null // what setCredentials reports back
    var appliedFlashMs: Long? = null
    var appliedTunables: Tunables? = null
    var metadataCleared = false

    override suspend fun setCredentials(creds: SubsonicCredentials): String? {
        credentials = creds
        return pingResult
    }

    override suspend fun setQueuedFlashMs(ms: Long) { appliedFlashMs = ms }
    override suspend fun setTunables(tunables: Tunables) { appliedTunables = tunables }
    override suspend fun clearMetadataCache() { metadataCleared = true }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `save applies trimmed credentials and reports Connected when the server answers`() = runTest {
        val app = FakeAppControl()
        val vm = SettingsViewModel(FakeDataStore(), app)
        vm.setUrl(" https://m.example.com ")
        vm.setUser(" alice ")
        vm.setPass("pw")

        vm.save()

        assertEquals(SubsonicCredentials("https://m.example.com", "alice", "pw"), app.credentials)
        assertEquals("Connected ✓", vm.status.value)
    }

    @Test
    fun `save surfaces the connection failure reason`() = runTest {
        val app = FakeAppControl().apply { pingResult = "Wrong username or password." }
        val vm = SettingsViewModel(FakeDataStore(), app)

        vm.save()

        assertEquals("Saved — Wrong username or password.", vm.status.value)
    }

    @Test
    fun `stored settings load into the form`() = runTest {
        val store = FakeDataStore()
        MusicSettings.save(store, SubsonicCredentials("https://m.example.com", "alice", "pw"))

        val vm = SettingsViewModel(store, FakeAppControl())

        assertEquals("https://m.example.com", vm.url.value)
        assertEquals("alice", vm.user.value)
        assertEquals("pw", vm.pass.value)
    }

    @Test
    fun `flash duration is clamped to its bounds and applied live`() = runTest {
        val app = FakeAppControl()
        val vm = SettingsViewModel(FakeDataStore(), app)

        vm.setFlashMs("50") // below the 100ms floor

        assertEquals(100L, vm.flashMs.value)
        assertEquals(100L, app.appliedFlashMs)

        vm.setFlashMs("not a number") // ignored, keeps the last valid value
        assertEquals(100L, vm.flashMs.value)
    }

    @Test
    fun `tunables are clamped and applied live`() = runTest {
        val app = FakeAppControl()
        val vm = SettingsViewModel(FakeDataStore(), app)

        vm.setCacheBudgetMb("999999") // above the 20000 MB ceiling

        assertEquals(20_000, vm.tunables.value.cacheBudgetMb)
        assertEquals(20_000, app.appliedTunables?.cacheBudgetMb)
    }

    @Test
    fun `clearing the metadata cache reports status`() = runTest {
        val app = FakeAppControl()
        val vm = SettingsViewModel(FakeDataStore(), app)

        vm.clearMetadataCache()

        assertTrue(app.metadataCleared)
        assertEquals("Metadata cache cleared", vm.status.value)
    }
}

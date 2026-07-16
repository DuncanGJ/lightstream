package com.thelightphone.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.viewModelScope
import com.thelightphone.music.app.AppControl
import com.thelightphone.music.app.MusicApp
import com.thelightphone.music.app.MusicSettings
import com.thelightphone.music.app.Tunables
import com.thelightphone.music.cache.CacheUsage
import com.thelightphone.music.subsonic.SubsonicCredentials
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Empty VM: exists so [TextEntryScreen] is a ViewModelStoreOwner (see class kdoc). */
class TextEntryViewModel : LightViewModel<String>()

/**
 * Full-screen LP3 keyboard editor — the canonical Light text-input pattern (see the ui-demo).
 * Uses [rememberKeyboardOptions] so the keyboard is wired to LightOS. Returns the entered text via
 * goBack; goBack(null) cancels (no result delivered). Because the caller's composition is disposed
 * while this is on screen, callers must hold the received value in a ViewModel, not `remember` —
 * use [TextEntryField]/[editText], which own that round-trip.
 *
 * Deliberately a [LightScreen] (ViewModelStoreOwner), NOT a SimpleLightScreen: the embedded LP3
 * keyboard creates its own ViewModel keyed by editor title. On a SimpleLightScreen that VM lands in
 * the Activity's store and outlives the editor, so every REOPEN of a same-titled editor gets a
 * keyboard bound to the previous session's dead TextFieldState — keystrokes go nowhere. Scoping the
 * store to this screen (cleared on goBack via LightScreen.destroy) gives a fresh keyboard VM per
 * session. (The SDK ui-demo editor has this same latent bug.)
 */
class TextEntryScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
) : LightScreen<String, TextEntryViewModel>(sealedActivity) {

    override val viewModelClass: Class<TextEntryViewModel>
        get() = TextEntryViewModel::class.java

    override fun createViewModel() = TextEntryViewModel()

    @Composable
    override fun Content() {
        val textState = rememberTextFieldState(initial)
        val themeColors by LightThemeController.colors.collectAsState()
        val keyboardOptionsFlow = rememberKeyboardOptions()
        val clipboard = LocalClipboardManager.current
        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                // The LP3 keyboard has no paste key, so surface the device clipboard here.
                // With emulator clipboard sharing on, this is the host (macOS) clipboard.
                LightText(
                    text = "PASTE",
                    variant = LightTextVariant.Fine,
                    modifier = Modifier
                        .align(Alignment.End)
                        .lightClickable {
                            clipboard.getText()?.text?.let { pasted ->
                                textState.edit { replace(0, length, pasted) }
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                )
                LightTextInputEditor(
                    title = title,
                    state = textState,
                    keyboardOptionsFlow = keyboardOptionsFlow,
                    singleLine = true,
                    onSubmit = { result -> goBack(result.toString()) },
                    onBack = { goBack(null) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

class SettingsViewModel(
    private val dataStore: DataStore<Preferences>,
    private val app: AppControl,
) : LightViewModel<Unit>() {
    private val _url = MutableStateFlow("")
    val url: StateFlow<String> = _url.asStateFlow()
    private val _user = MutableStateFlow("")
    val user: StateFlow<String> = _user.asStateFlow()
    private val _pass = MutableStateFlow("")
    val pass: StateFlow<String> = _pass.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _flashMs = MutableStateFlow(MusicSettings.DEFAULT_QUEUED_FLASH_MS)
    val flashMs: StateFlow<Long> = _flashMs.asStateFlow()

    // --- dev/debug tunables ----------------------------------------------------
    // Declared BEFORE init: the load coroutine can run eagerly (Main.immediate / test
    // dispatchers), and a `val` below the init block would still be null when it writes.
    private val _tunables = MutableStateFlow(Tunables())
    val tunables: StateFlow<Tunables> = _tunables.asStateFlow()
    private val _cacheUsage = MutableStateFlow<CacheUsage?>(null)
    val cacheUsage: StateFlow<CacheUsage?> = _cacheUsage.asStateFlow()

    init {
        viewModelScope.launch {
            MusicSettings.load(dataStore)?.let {
                _url.value = it.baseUrl
                _user.value = it.username
                _pass.value = it.password
            }
            _flashMs.value = MusicSettings.loadQueuedFlashMs(dataStore)
            _tunables.value = MusicSettings.loadTunables(dataStore)
            _cacheUsage.value = app.session.value?.trackCache?.usage()
        }
    }

    fun setFlashMs(raw: String) {
        val ms = raw.trim().toLongOrNull()?.coerceIn(100L, 5_000L) ?: return
        _flashMs.value = ms
        viewModelScope.launch { app.setQueuedFlashMs(ms) }
    }

    fun setCacheBudgetMb(raw: String) {
        raw.trim().toIntOrNull()?.coerceIn(50, 20_000)?.let { update(_tunables.value.copy(cacheBudgetMb = it)) }
    }

    fun setPrefetchDepth(raw: String) {
        raw.trim().toIntOrNull()?.coerceIn(0, 100)?.let { update(_tunables.value.copy(prefetchDepth = it)) }
    }

    fun setBitrateKbps(raw: String) {
        raw.trim().toIntOrNull()?.coerceIn(32, 1_024)?.let { update(_tunables.value.copy(streamBitrateKbps = it)) }
    }

    fun refreshUsage() {
        viewModelScope.launch { _cacheUsage.value = app.session.value?.trackCache?.usage() }
    }

    fun clearAudioCache() {
        viewModelScope.launch {
            app.session.value?.trackCache?.clear()
            _cacheUsage.value = app.session.value?.trackCache?.usage()
            _status.value = "Audio cache cleared"
        }
    }

    fun clearMetadataCache() {
        viewModelScope.launch {
            app.clearMetadataCache()
            _status.value = "Metadata cache cleared"
        }
    }

    private fun update(tunables: Tunables) {
        _tunables.value = tunables
        viewModelScope.launch { app.setTunables(tunables) }
    }

    fun setUrl(value: String) { _url.value = value }
    fun setUser(value: String) { _user.value = value }
    fun setPass(value: String) { _pass.value = value }

    fun save() {
        viewModelScope.launch {
            _status.value = "Saving…"
            val error = app.setCredentials(
                SubsonicCredentials(_url.value.trim(), _user.value.trim(), _pass.value),
            )
            _status.value = error?.let { "Saved — $it" } ?: "Connected ✓"
        }
    }
}

class SettingsScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, SettingsViewModel>(sealedActivity) {

    override val viewModelClass: Class<SettingsViewModel>
        get() = SettingsViewModel::class.java

    override fun createViewModel() = SettingsViewModel(lightContext.dataStore, MusicApp)

    @Composable
    override fun Content() {
        val url by viewModel.url.collectAsState()
        val user by viewModel.user.collectAsState()
        val pass by viewModel.pass.collectAsState()
        val status by viewModel.status.collectAsState()
        val flashMs by viewModel.flashMs.collectAsState()
        val tunables by viewModel.tunables.collectAsState()
        val cacheUsage by viewModel.cacheUsage.collectAsState()

        MusicScaffold(title = "Settings", onBack = { goBack() }) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                TextEntryField(
                    label = "Server URL",
                    value = url,
                    placeholder = "https://music.example.com",
                    modifier = Modifier.padding(top = 8.dp),
                    onResult = { viewModel.setUrl(it) },
                )
                TextEntryField(
                    label = "Username",
                    value = user,
                    placeholder = "username",
                    modifier = Modifier.padding(top = 16.dp),
                    onResult = { viewModel.setUser(it) },
                )
                TextEntryField(
                    label = "Password",
                    value = pass,
                    display = if (pass.isBlank()) "" else "••••••",
                    placeholder = "password",
                    modifier = Modifier.padding(top = 16.dp),
                    onResult = { viewModel.setPass(it) },
                )
                TextEntryField(
                    label = "Queued flash (ms)",
                    value = flashMs.toString(),
                    placeholder = MusicSettings.DEFAULT_QUEUED_FLASH_MS.toString(),
                    modifier = Modifier.padding(top = 16.dp),
                    onResult = { viewModel.setFlashMs(it) },
                )
                TextEntryField(
                    label = "Cache budget (MB)",
                    value = tunables.cacheBudgetMb.toString(),
                    placeholder = Tunables().cacheBudgetMb.toString(),
                    modifier = Modifier.padding(top = 16.dp),
                    onResult = { viewModel.setCacheBudgetMb(it) },
                )
                TextEntryField(
                    label = "Prefetch depth (tracks)",
                    value = tunables.prefetchDepth.toString(),
                    placeholder = Tunables().prefetchDepth.toString(),
                    modifier = Modifier.padding(top = 16.dp),
                    onResult = { viewModel.setPrefetchDepth(it) },
                )
                TextEntryField(
                    label = "Stream bitrate (kbps)",
                    value = tunables.streamBitrateKbps.toString(),
                    placeholder = Tunables().streamBitrateKbps.toString(),
                    modifier = Modifier.padding(top = 16.dp),
                    onResult = { viewModel.setBitrateKbps(it) },
                )
                LightText(
                    text = "Save",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .padding(vertical = 28.dp)
                        .lightClickable { viewModel.save() },
                )
                status?.let {
                    LightText(text = it, variant = LightTextVariant.Detail, lighten = true)
                }

                SectionHeader("Cache (debug)")
                val usage = cacheUsage
                LightText(
                    text = if (usage == null) {
                        "Audio cache: — (tap to refresh)"
                    } else {
                        "Audio cache: ${usage.bytes / (1024 * 1024)} MB · ${usage.tracks} tracks (tap to refresh)"
                    },
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .lightClickable { viewModel.refreshUsage() },
                )
                LightText(
                    text = "Clear audio cache",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .padding(top = 20.dp)
                        .lightClickable { viewModel.clearAudioCache() },
                )
                LightText(
                    text = "Clear metadata cache",
                    variant = LightTextVariant.Button,
                    modifier = Modifier
                        .padding(top = 20.dp, bottom = 24.dp)
                        .lightClickable { viewModel.clearMetadataCache() },
                )
            }
        }
    }
}

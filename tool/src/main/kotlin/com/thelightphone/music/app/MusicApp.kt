package com.thelightphone.music.app

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.music.cache.CacheDatabase
import com.thelightphone.music.cache.MetadataDatabase
import com.thelightphone.music.cache.TrackCache
import com.thelightphone.music.library.LibraryRepository
import com.thelightphone.music.playback.MediaPlayerController
import com.thelightphone.music.playback.Player
import com.thelightphone.music.subsonic.SubsonicClient
import com.thelightphone.music.subsonic.SubsonicCredentials
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Process-wide singletons for the Music tool. There is no DI container in the SDK, so this object
 * owns the shared HTTP client, the Room-backed cache, and the current [Player]. It is (re)configured
 * whenever server credentials change. [start] is idempotent and safe to call from any screen.
 */
object MusicApp {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    private lateinit var http: HttpClient
    private lateinit var db: CacheDatabase
    private lateinit var metadataDb: MetadataDatabase
    private lateinit var cacheDir: File

    private val _subsonic = MutableStateFlow<SubsonicClient?>(null)
    val subsonic: StateFlow<SubsonicClient?> = _subsonic

    private val _library = MutableStateFlow<LibraryRepository?>(null)
    val library: StateFlow<LibraryRepository?> = _library

    private val _player = MutableStateFlow<Player?>(null)
    val player: StateFlow<Player?> = _player

    /** How long the queued indicator (＋ → dot) shows; Settings-tunable for on-device dialing. */
    private val _queuedFlashMs = MutableStateFlow(MusicSettings.DEFAULT_QUEUED_FLASH_MS)
    val queuedFlashMs: StateFlow<Long> = _queuedFlashMs

    suspend fun setQueuedFlashMs(dataStore: DataStore<Preferences>, ms: Long) {
        MusicSettings.saveQueuedFlashMs(dataStore, ms)
        _queuedFlashMs.value = ms
    }

    /** Dev/debug tunables, applied live via suppliers — changing one never rebuilds the Player. */
    private val _tunables = MutableStateFlow(Tunables())
    val tunables: StateFlow<Tunables> = _tunables

    suspend fun setTunables(dataStore: DataStore<Preferences>, tunables: Tunables) {
        MusicSettings.saveTunables(dataStore, tunables)
        _tunables.value = tunables
    }

    /** The live audio cache (usage readout + clear), null until configured. */
    var trackCache: TrackCache? = null
        private set

    suspend fun clearMetadataCache() {
        metadataDb.metadataDao().clearAll()
    }

    fun start(context: SealedLightContext) {
        if (started) return
        started = true
        http = HttpClient(OkHttp)
        db = context.buildDatabase(CacheDatabase::class.java, "trackcache.db")
        metadataDb = context.buildDatabase(MetadataDatabase::class.java, "metadata.db")
        cacheDir = File(context.filesDir, "trackcache")
        appScope.launch {
            _queuedFlashMs.value = MusicSettings.loadQueuedFlashMs(context.dataStore)
            _tunables.value = MusicSettings.loadTunables(context.dataStore)
            MusicSettings.load(context.dataStore)?.let { configure(it) }
        }
    }

    suspend fun setCredentials(dataStore: DataStore<Preferences>, creds: SubsonicCredentials) {
        MusicSettings.save(dataStore, creds)
        configure(creds)
    }

    private fun configure(creds: SubsonicCredentials) {
        _player.value?.release()
        val client = SubsonicClient(
            creds,
            http,
            bitrateKbps = { _tunables.value.streamBitrateKbps },
        )
        val cache = TrackCache(
            db.cacheDao(),
            http,
            client,
            cacheDir,
            budgetBytes = { _tunables.value.cacheBudgetMb * 1024L * 1024L },
        )
        trackCache = cache
        _subsonic.value = client
        _library.value = LibraryRepository(client, metadataDb.metadataDao())
        _player.value = Player(
            client,
            cache,
            MediaPlayerController(),
            appScope,
            prefetchDepth = { _tunables.value.prefetchDepth },
        )
    }
}

/** Dev/debug tunables surfaced in Settings; see MusicApp.tunables for the live values. */
data class Tunables(
    val cacheBudgetMb: Int = 500,
    val prefetchDepth: Int = 15,
    val streamBitrateKbps: Int = 256,
)

/** Server credentials + tunables persisted in the tool's shared Preferences DataStore. */
object MusicSettings {
    const val DEFAULT_QUEUED_FLASH_MS = 800L

    private val BASE_URL = stringPreferencesKey("subsonic_base_url")
    private val USERNAME = stringPreferencesKey("subsonic_username")
    private val PASSWORD = stringPreferencesKey("subsonic_password")
    private val QUEUED_FLASH_MS = longPreferencesKey("queued_flash_ms")
    private val CACHE_BUDGET_MB = intPreferencesKey("cache_budget_mb")
    private val PREFETCH_DEPTH = intPreferencesKey("prefetch_depth")
    private val STREAM_BITRATE_KBPS = intPreferencesKey("stream_bitrate_kbps")

    suspend fun loadTunables(dataStore: DataStore<Preferences>): Tunables {
        val prefs = dataStore.data.first()
        val defaults = Tunables()
        return Tunables(
            cacheBudgetMb = prefs[CACHE_BUDGET_MB] ?: defaults.cacheBudgetMb,
            prefetchDepth = prefs[PREFETCH_DEPTH] ?: defaults.prefetchDepth,
            streamBitrateKbps = prefs[STREAM_BITRATE_KBPS] ?: defaults.streamBitrateKbps,
        )
    }

    suspend fun saveTunables(dataStore: DataStore<Preferences>, tunables: Tunables) {
        dataStore.edit {
            it[CACHE_BUDGET_MB] = tunables.cacheBudgetMb
            it[PREFETCH_DEPTH] = tunables.prefetchDepth
            it[STREAM_BITRATE_KBPS] = tunables.streamBitrateKbps
        }
    }

    suspend fun loadQueuedFlashMs(dataStore: DataStore<Preferences>): Long =
        dataStore.data.first()[QUEUED_FLASH_MS] ?: DEFAULT_QUEUED_FLASH_MS

    suspend fun saveQueuedFlashMs(dataStore: DataStore<Preferences>, ms: Long) {
        dataStore.edit { it[QUEUED_FLASH_MS] = ms }
    }

    suspend fun load(dataStore: DataStore<Preferences>): SubsonicCredentials? {
        val prefs = dataStore.data.first()
        val url = prefs[BASE_URL]
        val user = prefs[USERNAME]
        val pass = prefs[PASSWORD]
        return if (!url.isNullOrBlank() && !user.isNullOrBlank() && pass != null) {
            SubsonicCredentials(url, user, pass)
        } else {
            null
        }
    }

    suspend fun save(dataStore: DataStore<Preferences>, creds: SubsonicCredentials) {
        dataStore.edit {
            it[BASE_URL] = creds.baseUrl
            it[USERNAME] = creds.username
            it[PASSWORD] = creds.password
        }
    }
}

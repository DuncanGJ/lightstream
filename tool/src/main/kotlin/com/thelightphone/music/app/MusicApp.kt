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
import com.thelightphone.music.playback.AudioSource
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
 * One configured connection to the server and everything wired to it (CONTEXT.md: Session).
 * Rebuilt as a unit whenever credentials change. Screens beyond Home capture it at construction —
 * safe, because Settings (the only place a session is rebuilt) is reachable only from Home, so no
 * browse screen ever outlives a session swap.
 */
class Session(
    val subsonic: SubsonicClient,
    val library: LibraryRepository,
    val player: Player,
    val trackCache: TrackCache,
)

/**
 * What Settings can do to the running app — the seam that makes [SettingsViewModel] testable
 * without the real singletons behind it. [MusicApp] is the only production adapter.
 */
interface AppControl {
    val session: StateFlow<Session?>

    /** Persist + apply new credentials; null if the new server answers, else the failure reason. */
    suspend fun setCredentials(creds: SubsonicCredentials): String?
    suspend fun setQueuedFlashMs(ms: Long)
    suspend fun setTunables(tunables: Tunables)
    suspend fun clearMetadataCache()
}

/**
 * The composition root: owns the process-wide resources (HTTP client, Room databases, DataStore)
 * and wires each [Session] from them. There is no DI container in the SDK, so screens reach this
 * object exactly once — in their constructors / `createViewModel()` factories — and hand real
 * dependencies to their ViewModels; nothing below the UI layer knows MusicApp exists.
 * [start] is idempotent and safe to call from any screen.
 */
object MusicApp : AppControl {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    private lateinit var http: HttpClient
    private lateinit var db: CacheDatabase
    private lateinit var metadataDb: MetadataDatabase
    private lateinit var cacheDir: File
    private lateinit var dataStore: DataStore<Preferences>

    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session

    /** How long the queued indicator (＋ → dot) shows; Settings-tunable for on-device dialing. */
    private val _queuedFlashMs = MutableStateFlow(MusicSettings.DEFAULT_QUEUED_FLASH_MS)
    val queuedFlashMs: StateFlow<Long> = _queuedFlashMs

    /** Dev/debug tunables, applied live via suppliers — changing one never rebuilds the Player. */
    private val _tunables = MutableStateFlow(Tunables())
    val tunables: StateFlow<Tunables> = _tunables

    fun start(context: SealedLightContext) {
        if (started) return
        started = true
        http = HttpClient(OkHttp)
        db = context.buildDatabase(CacheDatabase::class.java, "trackcache.db")
        metadataDb = context.buildDatabase(MetadataDatabase::class.java, "metadata.db")
        cacheDir = File(context.filesDir, "trackcache")
        dataStore = context.dataStore
        appScope.launch {
            _queuedFlashMs.value = MusicSettings.loadQueuedFlashMs(dataStore)
            _tunables.value = MusicSettings.loadTunables(dataStore)
            MusicSettings.load(dataStore)?.let { configure(it) }
        }
    }

    /**
     * The current session, for screens that are only reachable AFTER configuration (everything
     * Home links to besides Settings — Home itself gates on [session] being non-null).
     */
    fun requireSession(): Session =
        checkNotNull(_session.value) { "No session: this screen must only be reachable from a configured Home" }

    override suspend fun setCredentials(creds: SubsonicCredentials): String? {
        MusicSettings.save(dataStore, creds)
        val session = configure(creds)
        return session.subsonic.pingError()
    }

    override suspend fun setQueuedFlashMs(ms: Long) {
        MusicSettings.saveQueuedFlashMs(dataStore, ms)
        _queuedFlashMs.value = ms
    }

    override suspend fun setTunables(tunables: Tunables) {
        MusicSettings.saveTunables(dataStore, tunables)
        _tunables.value = tunables
    }

    override suspend fun clearMetadataCache() {
        metadataDb.metadataDao().clearAll()
    }

    private fun configure(creds: SubsonicCredentials): Session {
        _session.value?.player?.release()
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
        val player = Player(
            cache,
            MediaPlayerController(),
            appScope,
            prefetchDepth = { _tunables.value.prefetchDepth },
            resolveRemote = { AudioSource.Remote(client.streamUrl(it)) },
        )
        val session = Session(client, LibraryRepository(client, metadataDb.metadataDao()), player, cache)
        _session.value = session
        return session
    }
}

/**
 * Dev/debug tunables surfaced in Settings. Defaults live with the modules that consume them
 * ([TrackCache], [Player], [SubsonicClient]) — this class only mirrors them, so they can't drift.
 */
data class Tunables(
    val cacheBudgetMb: Int = (TrackCache.DEFAULT_BUDGET_BYTES / (1024L * 1024L)).toInt(),
    val prefetchDepth: Int = Player.DEFAULT_PREFETCH_DEPTH,
    val streamBitrateKbps: Int = SubsonicClient.STREAM_BITRATE_KBPS,
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

package com.thelightphone.music.playback

import android.media.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Disposable, foreground-only playback shim built on android.media.MediaPlayer — the one audio
 * API the SDK plugin does not block (it accepts a URL/path with no Context). Reliable background
 * and lockscreen playback are a known gap until LightOS ships its audio API; see ADR-0002.
 *
 * Construct on the main thread: MediaPlayer delivers its callbacks on the thread that created it,
 * and the position ticker runs on Dispatchers.Main.
 */
class MediaPlayerController : PlaybackController {

    private val player = MediaPlayer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state

    override var onCompletion: (() -> Unit)? = null

    init {
        player.setOnPreparedListener { mp ->
            mp.start()
            _state.update { it.copy(status = PlaybackStatus.PLAYING, durationMs = mp.duration) }
            startTicker()
        }
        player.setOnCompletionListener {
            stopTicker()
            _state.update { it.copy(status = PlaybackStatus.ENDED) }
            onCompletion?.invoke()
        }
        player.setOnErrorListener { _, _, _ ->
            stopTicker()
            _state.update { it.copy(status = PlaybackStatus.ERROR) }
            true
        }
    }

    override fun play(source: AudioSource) {
        stopTicker()
        player.reset()
        val data = when (source) {
            is AudioSource.Remote -> source.url
            is AudioSource.Local -> source.path
        }
        _state.value = PlaybackState(status = PlaybackStatus.BUFFERING)
        player.setDataSource(data)
        player.prepareAsync()
    }

    override fun pause() {
        if (_state.value.status == PlaybackStatus.PLAYING) {
            player.pause()
            stopTicker()
            _state.update { it.copy(status = PlaybackStatus.PAUSED) }
        }
    }

    override fun resume() {
        if (_state.value.status == PlaybackStatus.PAUSED) {
            player.start()
            _state.update { it.copy(status = PlaybackStatus.PLAYING) }
            startTicker()
        }
    }

    override fun stop() {
        stopTicker()
        player.reset()
        _state.value = PlaybackState(status = PlaybackStatus.IDLE)
    }

    override fun release() {
        stopTicker()
        scope.cancel()
        player.release()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                if (_state.value.status == PlaybackStatus.PLAYING) {
                    _state.update { it.copy(positionMs = player.currentPosition) }
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        const val POSITION_POLL_MS = 500L
    }
}

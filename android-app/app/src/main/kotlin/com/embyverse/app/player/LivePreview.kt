package com.embyverse.app.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.di.AppContainer
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Vista previa de TV en vivo: un único reproductor pequeño dentro de la pantalla de canales.
 * Primer OK en un canal → se ve en la vista previa; segundo OK (o tocar la vista previa) → pantalla completa.
 * Se libera al salir de la pantalla y antes de abrir la pantalla completa, para no gastar una
 * de las conexiones simultáneas que permite el proveedor.
 */
class LivePreview(private val context: Context, private val c: AppContainer, @Suppress("UNUSED_PARAMETER") ignored: CoroutineScope? = null) {
    // ExoPlayer solo se puede usar desde el hilo en que se creó: todo va por el hilo principal.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player
    private val _channel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = _channel
    val buffering = MutableStateFlow(false)
    val error = MutableStateFlow(false)
    private var job: Job? = null
    private var lastUrl: String? = null
    private var altIndex = -1
    /** Reconexión automática: intentos seguidos sin lograr imagen y vigilante de congelado / carga sin fin. */
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var watchdog: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { error.value = false; attempts = 0; PlaybackProbe.readyCount++ }
            if (state == Player.STATE_ENDED) reconnect("la transmisión se cortó")
        }
        override fun onPlayerError(e: PlaybackException) {
            // Mismo rescate que en pantalla completa: si el formato no es el anunciado, se prueba otro.
            val alts = lastUrl?.let { StreamSource.alternatives(it) }.orEmpty()
            val ch = _channel.value
            val formatProblem = e.errorCode in 3000..3999 || e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            if (ch != null && formatProblem && altIndex + 1 < alts.size) {
                altIndex++
                val (u, m) = alts[altIndex]
                _player.value?.let { p -> p.setMediaSource(StreamSource.mediaSource(c.okHttp, u, ch.userAgent, ch.referrer, ch.name, forcedMime = m)); p.prepare(); p.playWhenReady = true }
                return
            }
            // Se sigue intentando solo; tras 3 intentos se avisa, pero los intentos continúan.
            if (attempts >= 3) { buffering.value = false; error.value = true; PlaybackProbe.lastError = e.errorCodeName }
            altIndex = -1
            reconnect("error ${e.errorCodeName}")
        }
    }

    fun isPreviewing(ch: ChannelEntity) = _channel.value?.let { it.sourceId == ch.sourceId && it.providerId == ch.providerId } == true

    fun play(ch: ChannelEntity) {
        _channel.value = ch
        error.value = false; buffering.value = true
        job?.cancel()
        job = scope.launch {
            val url = c.sources.resolveStreamUrl(ch.sourceId, ch.streamUrl)
            lastUrl = url; altIndex = -1
            val p = _player.value ?: StreamSource.player(context).also { it.addListener(listener); it.volume = 1f; _player.value = it }
            p.setMediaSource(StreamSource.mediaSource(c.okHttp, url, ch.userAgent, ch.referrer, ch.name))
            p.prepare(); p.playWhenReady = true
        }
        attempts = 0; reconnectJob?.cancel()
        startWatchdog()
    }

    private fun prepareAgain() {
        val ch = _channel.value ?: return
        val url = lastUrl ?: return
        _player.value?.let { p -> p.setMediaSource(StreamSource.mediaSource(c.okHttp, url, ch.userAgent, ch.referrer, ch.name)); p.prepare(); p.playWhenReady = true }
    }

    private fun reconnect(reason: String) {
        if (reconnectJob?.isActive == true || _player.value == null) return
        attempts++
        PlaybackProbe.reconnects++; PlaybackProbe.lastReconnectReason = reason
        val wait = when (attempts) { 1 -> 0L; 2 -> 2_000L; 3 -> 4_000L; 4 -> 8_000L; else -> 15_000L }
        reconnectJob = scope.launch { delay(wait); if (!error.value) buffering.value = true; prepareAgain() }
    }

    /** Igual que en pantalla completa: si se queda cargando más de 12 s o la imagen se congela 8 s, se reconecta. */
    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            var lastPos = -1L; var lastFrames = -1; var stuck = 0L; var buf = 0L
            while (isActive) {
                delay(2_000)
                val p = _player.value
                if (p == null || !p.playWhenReady || reconnectJob?.isActive == true) { stuck = 0; buf = 0; lastPos = -1; continue }
                val now = android.os.SystemClock.elapsedRealtime()
                when (p.playbackState) {
                    Player.STATE_BUFFERING -> { stuck = 0; if (buf == 0L) buf = now else if (now - buf > 12_000) { buf = 0; reconnect("cargando sin fin") } }
                    Player.STATE_READY -> {
                        buf = 0
                        val pos = p.currentPosition
                        val frames = p.videoDecoderCounters?.let { it.ensureUpdated(); it.renderedOutputBufferCount } ?: -1
                        val moving = lastPos < 0 || pos != lastPos || (frames >= 0 && frames != lastFrames)
                        lastPos = pos; lastFrames = frames
                        if (moving) stuck = 0 else if (stuck == 0L) stuck = now else if (now - stuck > 8_000) { stuck = 0; lastPos = -1; reconnect("imagen congelada") }
                    }
                    else -> { stuck = 0; buf = 0; lastPos = -1 }
                }
            }
        }
    }

    /** Detiene y libera (al salir de la pantalla o antes de pasar a pantalla completa). */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { release() }; return }
        job?.cancel(); reconnectJob?.cancel(); watchdog?.cancel()
        _player.value?.let { it.removeListener(listener); it.release() }
        _player.value = null
        buffering.value = false
    }

    fun clear() { release(); _channel.value = null }

    /** Al salir definitivamente de la pantalla. */
    fun dispose() { clear(); scope.cancel() }
}

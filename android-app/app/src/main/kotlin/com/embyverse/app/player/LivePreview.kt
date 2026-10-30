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

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { error.value = false; PlaybackProbe.readyCount++ }
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
            buffering.value = false; error.value = true; PlaybackProbe.lastError = e.errorCodeName
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
    }

    /** Detiene y libera (al salir de la pantalla o antes de pasar a pantalla completa). */
    fun release() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { release() }; return }
        job?.cancel()
        _player.value?.let { it.removeListener(listener); it.release() }
        _player.value = null
        buffering.value = false
    }

    fun clear() { release(); _channel.value = null }

    /** Al salir definitivamente de la pantalla. */
    fun dispose() { clear(); scope.cancel() }
}

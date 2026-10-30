package com.embyverse.app.player

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.di.AppContainer
import kotlinx.coroutines.CoroutineScope
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
class LivePreview(private val context: Context, private val c: AppContainer, private val scope: CoroutineScope) {
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player
    private val _channel = MutableStateFlow<ChannelEntity?>(null)
    val channel: StateFlow<ChannelEntity?> = _channel
    val buffering = MutableStateFlow(false)
    val error = MutableStateFlow(false)
    private var job: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering.value = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) { error.value = false; PlaybackProbe.readyCount++ }
        }
        override fun onPlayerError(e: PlaybackException) { buffering.value = false; error.value = true; PlaybackProbe.lastError = e.errorCodeName }
    }

    fun isPreviewing(ch: ChannelEntity) = _channel.value?.let { it.sourceId == ch.sourceId && it.providerId == ch.providerId } == true

    fun play(ch: ChannelEntity) {
        _channel.value = ch
        error.value = false; buffering.value = true
        job?.cancel()
        job = scope.launch {
            val url = c.sources.resolveStreamUrl(ch.sourceId, ch.streamUrl)
            val p = _player.value ?: StreamSource.player(context).also { it.addListener(listener); it.volume = 1f; _player.value = it }
            p.setMediaSource(StreamSource.mediaSource(c.okHttp, url, ch.userAgent, ch.referrer, ch.name))
            p.prepare(); p.playWhenReady = true
        }
    }

    /** Detiene y libera (al salir de la pantalla o antes de pasar a pantalla completa). */
    fun release() {
        job?.cancel()
        _player.value?.let { it.removeListener(listener); it.release() }
        _player.value = null
        buffering.value = false
    }

    fun clear() { release(); _channel.value = null }
}

package com.embyverse.app.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.embyverse.app.data.net.Network
import com.embyverse.core.m3u.M3uCatalogBuilder
import okhttp3.OkHttpClient

/** Construcción común del reproductor y de la fuente de video (pantalla completa y vista previa). */
@OptIn(UnstableApi::class)
object StreamSource {
    fun player(context: Context, quickStart: Boolean = true): ExoPlayer {
        // Arranque rápido: empieza con 1 s de búfer (2 s tras un corte).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(if (quickStart) 8_000 else 15_000, 50_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        // Audio: primero los decodificadores del equipo y, si no puede (AC3/E-AC3/DTS/TrueHD en muchos
        // celulares y TV), el de FFmpeg incluido en la app. Así se oyen todas las películas.
        val renderers = WithSoftwareVideo(context.applicationContext)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            // Solo pruebas: simular un equipo sin decodificador de video MPEG-2 (muchos Fire TV y celulares).
            .apply { if (com.embyverse.app.BuildConfig.DEBUG) setMediaCodecSelector { mime, secure, tunneling ->
                if (PlaybackProbe.hideMpeg2Decoder && mime == MimeTypes.VIDEO_MPEG2) emptyList()
                else androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            } }
        return ExoPlayer.Builder(context.applicationContext, renderers).setLoadControl(loadControl)
            // Mantiene CPU y Wi-Fi activos mientras se reproduce, aunque no se toque el control.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /**
     * Formas alternativas de abrir un canal en vivo cuando el proveedor entrega otro formato del que
     * anuncia (p. ej. pide .ts y responde una lista HLS → ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED).
     * Devuelve (url, mime forzado) en el orden en que conviene probarlas.
     */
    fun alternatives(url: String): List<Pair<String, String?>> {
        val ext = M3uCatalogBuilder.extension(url)
        val swap = when (ext) {
            "ts" -> url.substring(0, url.length - 2) + "m3u8"
            "m3u8" -> url.substring(0, url.length - 4) + "ts"
            else -> null
        }
        return buildList {
            if (ext != "m3u8" && ext != "m3u") add(url to MimeTypes.APPLICATION_M3U8) // misma dirección, leída como HLS
            if (swap != null) add(swap to null)
        }
    }

    fun mediaSource(client: OkHttpClient, url: String, userAgent: String?, referrer: String?, title: String? = null, subtitle: String? = null, forcedMime: String? = null): MediaSource {
        // Cabeceras por stream (User-Agent / Referer declarados en la lista M3U).
        val ds = OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent ?: Network.USER_AGENT)
            .setDefaultRequestProperties(buildMap { referrer?.let { put("Referer", it) } })
        val mime = forcedMime ?: when (M3uCatalogBuilder.extension(url)) {
            "m3u8", "m3u" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val item = MediaItem.Builder().setUri(url).apply { mime?.let { setMimeType(it) } }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).build())
            .build()
        return DefaultMediaSourceFactory(ds).createMediaSource(item)
    }
}

/**
 * Video: primero el decodificador del equipo y, si no tiene uno para ese formato (MPEG-2 de muchos canales de TV,
 * DivX 3, VC-1… en muchos Fire TV y celulares), el de FFmpeg incluido en la app (por software). Si la app se
 * compiló sin FFmpeg con video, queda como antes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class WithSoftwareVideo(context: android.content.Context) : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: android.content.Context, extensionRendererMode: Int, mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: android.os.Handler, eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) return
        // Solo pruebas: equipo sin ningún decodificador de ese video (ni el de la app).
        if (com.embyverse.app.BuildConfig.DEBUG && PlaybackProbe.hideSoftwareVideo) { out.removeAll { it.javaClass.name == FFMPEG_VIDEO }; return }
        if (out.any { it.javaClass.name == FFMPEG_VIDEO }) { PlaybackProbe.softwareVideo = true; return }
        runCatching {
            val r = Class.forName(FFMPEG_VIDEO)
                .getConstructor(java.lang.Long.TYPE, android.os.Handler::class.java, androidx.media3.exoplayer.video.VideoRendererEventListener::class.java, Integer.TYPE)
                .newInstance(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50) as androidx.media3.exoplayer.Renderer
            out.add(r) // después del del equipo: solo se usa cuando el equipo no puede
            PlaybackProbe.softwareVideo = true
        }
    }
    companion object { const val FFMPEG_VIDEO = "androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer" }
}

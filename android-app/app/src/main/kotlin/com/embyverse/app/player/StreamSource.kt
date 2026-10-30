package com.embyverse.app.player

import android.content.Context
import androidx.annotation.OptIn
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
        return ExoPlayer.Builder(context.applicationContext).setLoadControl(loadControl).build()
    }

    fun mediaSource(client: OkHttpClient, url: String, userAgent: String?, referrer: String?, title: String? = null, subtitle: String? = null): MediaSource {
        // Cabeceras por stream (User-Agent / Referer declarados en la lista M3U).
        val ds = OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent ?: Network.USER_AGENT)
            .setDefaultRequestProperties(buildMap { referrer?.let { put("Referer", it) } })
        val mime = when (M3uCatalogBuilder.extension(url)) {
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

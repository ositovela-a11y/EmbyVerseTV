package com.embyverse.app.player

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage

/** Configuración de Chromecast: receptor multimedia predeterminado de Google (sin servidor propio). */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()
    override fun getAdditionalSessionProviders(context: Context): MutableList<SessionProvider>? = null
}

/**
 * Enviar a la TV con Chromecast desde el celular. Al conectarse se manda lo que se está viendo (en la
 * misma posición) y el celular se pausa; al desconectar sigue en el celular.
 * Nota: el Chromecast reproduce HLS (.m3u8) y MP4; algunos canales .ts pueden no ser compatibles.
 */
object CastSupport {
    fun available(context: Context): Boolean =
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            runCatching { CastContext.getSharedInstance(context); true }.getOrDefault(false)

    fun mime(url: String): String = when {
        url.contains(".m3u8", true) -> "application/x-mpegURL"
        url.contains(".ts", true) -> "video/mp2t"
        url.contains(".mkv", true) -> "video/x-matroska"
        else -> "video/mp4"
    }

    fun listener(onStart: (CastSession) -> Unit, onEnd: () -> Unit) = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(s: CastSession, id: String) = onStart(s)
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) = onStart(s)
        override fun onSessionEnded(s: CastSession, error: Int) = onEnd()
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStartFailed(s: CastSession, error: Int) {}
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumeFailed(s: CastSession, error: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }

    fun load(session: CastSession, url: String, title: String, subtitle: String?, image: String?, live: Boolean, positionMs: Long) {
        val meta = MediaMetadata(if (live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            image?.let { addImage(WebImage(android.net.Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(url).setContentType(mime(url))
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(meta).build()
        session.remoteMediaClient?.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
            .setCurrentTime(if (live) 0 else positionMs).build())
    }
}

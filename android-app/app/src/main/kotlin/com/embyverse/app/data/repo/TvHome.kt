package com.embyverse.app.data.repo

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.embyverse.app.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * EmbyVerse en la pantalla de inicio de Google TV / Android TV: fila «Continuar viendo» del sistema
 * (Watch Next) con lo que el perfil dejó a medias, el siguiente episodio y sus favoritos.
 * Al elegir una tarjeta se abre directo en la app (enlace embyverse://play).
 */
object TvHome {
    private const val PREFS = "tvhome"

    fun playUri(kind: String, sourceId: String, id: String): Uri = Uri.Builder().scheme("embyverse").authority("play")
        .appendQueryParameter("kind", kind).appendQueryParameter("sid", sourceId).appendQueryParameter("id", id).build()

    suspend fun sync(context: Context, c: AppContainer) = withContext(Dispatchers.IO) {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return@withContext
        runCatching {
            val cr = context.contentResolver
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Se quitan las tarjetas que publicamos la vez anterior y se ponen las actuales.
            sp.getStringSet("ids", emptySet()).orEmpty().forEach { id ->
                runCatching { cr.delete(TvContractCompat.buildWatchNextProgramUri(id.toLong()), null, null) }
            }
            val ids = mutableSetOf<String>()
            val now = System.currentTimeMillis()
            val cont = c.library.continueWatchingSmart(8).first()
            val hist = c.library.historySnapshot(60).associateBy { "${it.kind}|${it.sourceId}|${it.itemId}" }
            cont.forEachIndexed { i, card ->
                val p = hist["${card.ref.kind}|${card.ref.sourceId}|${card.ref.id}"]
                val b = WatchNextProgram.Builder()
                    .setType(if (card.ref.kind.name == "MOVIE") TvContractCompat.WatchNextPrograms.TYPE_MOVIE else TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE)
                    .setWatchNextType(if (p != null) TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE else TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT)
                    .setLastEngagementTimeUtcMillis(p?.updatedAt ?: (now - i))
                    .setTitle(card.title)
                    .setInternalProviderId("${card.ref.kind}|${card.ref.sourceId}|${card.ref.id}")
                    .setIntentUri(playUri(card.ref.kind.name, card.ref.sourceId, card.ref.id))
                card.subtitle?.let { b.setDescription(it) }
                (card.backdropUrl ?: card.imageUrl)?.let { b.setPosterArtUri(Uri.parse(it)) }
                if (p != null && p.durationMs > 0) { b.setDurationMillis(p.durationMs.toInt()); b.setLastPlaybackPositionMillis(p.positionMs.toInt()) }
                cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())?.lastPathSegment?.let(ids::add)
            }
            c.library.favorites().first().take(8).forEachIndexed { i, f ->
                val b = WatchNextProgram.Builder()
                    .setType(when (f.kind) { "MOVIE" -> TvContractCompat.WatchNextPrograms.TYPE_MOVIE; "SERIES" -> TvContractCompat.WatchNextPrograms.TYPE_TV_SERIES; else -> TvContractCompat.WatchNextPrograms.TYPE_CHANNEL })
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST)
                    .setLastEngagementTimeUtcMillis(f.addedAt - i)
                    .setTitle(f.title)
                    .setInternalProviderId("fav|${f.kind}|${f.sourceId}|${f.itemId}")
                    .setIntentUri(playUri(f.kind, f.sourceId, f.itemId))
                f.imageUrl?.let { b.setPosterArtUri(Uri.parse(it)) }
                cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues())?.lastPathSegment?.let(ids::add)
            }
            sp.edit().putStringSet("ids", ids).apply()
        }.onFailure { Log.i("TvHome", "no se pudo publicar en el inicio de la TV: ${it.message}") }
    }
}

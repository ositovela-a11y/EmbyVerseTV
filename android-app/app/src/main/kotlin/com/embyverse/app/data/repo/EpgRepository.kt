package com.embyverse.app.data.repo

import androidx.room.withTransaction
import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.app.data.db.EpgEntity
import com.embyverse.app.data.net.await
import com.embyverse.core.epg.XmltvParser
import com.embyverse.core.net.Outcome
import com.embyverse.core.net.SourceError
import com.embyverse.core.net.SourceException
import com.embyverse.core.net.UrlSafety
import com.embyverse.core.net.classify
import com.embyverse.core.source.LimitedInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

/**
 * Guía de programación (EPG) en formato XMLTV. Sólo se muestra si la fuente ofrece una guía
 * válida; si no existe o falla, la app sigue funcionando sin guía.
 */
class EpgRepository(private val db: EmbyDatabase, private val client: OkHttpClient, private val sources: SourceRepository) {

    fun upcoming(sourceId: String, channelId: String, limit: Int = 12) =
        db.epg().observeUpcoming(sourceId, channelId, System.currentTimeMillis() / 1000, limit)

    suspend fun nowPlaying(sourceId: String, channelIds: List<String>) =
        if (channelIds.isEmpty()) emptyList() else db.epg().nowPlaying(sourceId, channelIds.take(900), System.currentTimeMillis() / 1000)

    suspend fun refresh(sourceId: String): Outcome<Int> = classify {
        val url = sources.epgUrlFor(sourceId) ?: return@classify 0
        if (!UrlSafety.isHttpUrl(url)) throw SourceException(SourceError.InvalidUrl)
        val wanted = db.channels().epgIds(sourceId).toSet()
        if (wanted.isEmpty()) return@classify 0
        val now = System.currentTimeMillis() / 1000
        val result = withContext(Dispatchers.IO) {
            client.newCall(Request.Builder().url(url).build()).await().use { resp ->
                if (resp.code == 401 || resp.code == 403) throw SourceException(SourceError.AuthFailed)
                if (!resp.isSuccessful) throw SourceException(SourceError.Http(resp.code))
                val raw = BufferedInputStream(LimitedInputStream(resp.body.byteStream(), 300L * 1024 * 1024))
                raw.mark(2)
                val gz = raw.read() == 0x1f && raw.read() == 0x8b
                raw.reset()
                val stream = if (gz) GZIPInputStream(raw, 64 * 1024) else raw
                XmltvParser().parse(InputStreamReader(stream, Charsets.UTF_8), now - 2 * 3600, now + 36 * 3600, wanted)
            }
        }
        db.withTransaction {
            db.epg().deleteForSource(sourceId)
            result.programs.chunked(500).forEach { chunk ->
                db.epg().upsert(chunk.map { EpgEntity(sourceId, it.channelId, it.startEpochSeconds, it.endEpochSeconds, it.title.take(200), it.description?.take(1000)) })
            }
            db.epg().prune(now - 6 * 3600)
        }
        result.programs.size
    }
}

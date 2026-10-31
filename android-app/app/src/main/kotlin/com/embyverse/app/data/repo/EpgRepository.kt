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
class EpgRepository(private val db: EmbyDatabase, private val client: OkHttpClient, private val sources: SourceRepository,
                    private val configText: () -> String? = { null }) {

    fun upcoming(sourceId: String, channelId: String, limit: Int = 12) =
        db.epg().observeUpcoming(sourceId, channelId, System.currentTimeMillis() / 1000, limit)

    suspend fun nowPlaying(sourceId: String, channelIds: List<String>) =
        if (channelIds.isEmpty()) emptyList() else db.epg().nowPlaying(sourceId, channelIds.take(900), System.currentTimeMillis() / 1000)

    /**
     * Guía de la fuente + guías EXTRA que define el administrador en el panel (texto `provider.epgUrl`,
     * una o varias URL XMLTV separadas por espacios o saltos de línea). Los canales se emparejan por id
     * y, si no coincide, por NOMBRE (sin acentos, prefijos de país ni "HD/FHD"). Así se "inyecta"
     * programación a canales que el proveedor entrega sin guía.
     */
    suspend fun refresh(sourceId: String): Outcome<Int> = classify {
        val extra = extraGuides()
        val provider = sources.epgUrlFor(sourceId)?.takeIf { UrlSafety.isHttpUrl(it) }
        val urls = listOfNotNull(provider) + extra.filter { it != provider }
        if (urls.isEmpty()) return@classify 0
        val cands = db.channels().epgCandidates(sourceId)
        if (cands.isEmpty()) return@classify 0
        val wanted = cands.mapNotNull { it.epgChannelId }.toHashSet()
        val byName = HashMap<String, MutableList<com.embyverse.app.data.db.EpgCandidate>>()
        cands.forEach { c -> normalizeName(c.name).takeIf { it.length >= 2 }?.let { byName.getOrPut(it) { ArrayList() } += c } }
        val assign = HashMap<String, MutableList<String>>() // id de guía -> canales sin id
        val now = System.currentTimeMillis() / 1000
        val all = HashMap<String, MutableList<com.embyverse.core.model.EpgProgram>>()
        for (url in urls) {
            val r = runCatching { download(url, now, wanted) { xc ->
                val list = byName[normalizeName(xc.displayName ?: xc.id)] ?: return@download null
                val key = list.firstNotNullOfOrNull { it.epgChannelId } ?: "name:${normalizeName(xc.displayName ?: xc.id)}"
                list.filter { it.epgChannelId == null }.forEach { assign.getOrPut(key) { ArrayList() } += it.providerId }
                key
            } }
            if (r.isFailure && url == provider && extra.isEmpty()) throw r.exceptionOrNull()!!
            // La primera guía que trae un canal gana (la del proveedor va primero).
            r.getOrNull()?.programs?.groupBy { it.channelId }?.forEach { (k, v) -> if (k !in all) all[k] = v.toMutableList() }
        }
        db.withTransaction {
            assign.forEach { (key, ids) -> ids.chunked(500).forEach { db.channels().assignEpgId(sourceId, it, key) } }
            db.epg().deleteForSource(sourceId)
            all.values.flatten().chunked(500).forEach { chunk ->
                db.epg().upsert(chunk.map { EpgEntity(sourceId, it.channelId, it.startEpochSeconds, it.endEpochSeconds, it.title.take(200), it.description?.take(1000)) })
            }
            db.epg().prune(now - 6 * 3600)
        }
        all.values.sumOf { it.size }
    }

    private fun extraGuides(): List<String> =
        configText()?.split(Regex("[\\s,]+"))?.map { it.trim() }?.filter { UrlSafety.isHttpUrl(it) }?.distinct()?.take(3).orEmpty()

    private suspend fun download(url: String, now: Long, wanted: Set<String>, map: (com.embyverse.core.epg.XmltvChannel) -> String?) = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).await().use { resp ->
            if (resp.code == 401 || resp.code == 403) throw SourceException(SourceError.AuthFailed)
            if (!resp.isSuccessful) throw SourceException(SourceError.Http(resp.code))
            val raw = BufferedInputStream(LimitedInputStream(resp.body.byteStream(), 300L * 1024 * 1024))
            raw.mark(2)
            val gz = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val stream = if (gz) GZIPInputStream(raw, 64 * 1024) else raw
            XmltvParser().parse(InputStreamReader(stream, Charsets.UTF_8), now - 2 * 3600, now + 36 * 3600, wanted, map)
        }
    }

    companion object {
        private val PREFIX = Regex("^\\s*[a-z]{2,3}\\s*[|:\\-]\\s*")
        private val QUALITY = Regex("\\b(fhd|uhd|hd|sd|4k|hevc|h265|720p|1080p|backup|alt)\\b")
        /** "MX | Canal 5 HD" y "Canal 5 (MX)" → "canal5". */
        fun normalizeName(s: String): String {
            var n = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            n = n.replace(PREFIX, "").replace(Regex("\\(.*?\\)|\\[.*?]"), " ").replace(QUALITY, " ")
            return n.replace(Regex("[^a-z0-9]+"), "")
        }
    }
}

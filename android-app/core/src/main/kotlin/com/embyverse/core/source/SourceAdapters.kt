package com.embyverse.core.source

import com.embyverse.core.m3u.M3uCatalogBuilder
import com.embyverse.core.m3u.M3uParser
import com.embyverse.core.model.Catalog
import com.embyverse.core.model.ContentKind
import com.embyverse.core.model.SourceSpec
import com.embyverse.core.model.SourceType
import com.embyverse.core.net.HttpFetcher
import com.embyverse.core.net.Outcome
import com.embyverse.core.net.SourceError
import com.embyverse.core.net.SourceException
import com.embyverse.core.net.UrlSafety
import com.embyverse.core.net.classify
import com.embyverse.core.xtream.LiveFormat
import com.embyverse.core.xtream.XtreamAccount
import com.embyverse.core.xtream.XtreamCapabilities
import com.embyverse.core.xtream.XtreamClient
import com.embyverse.core.xtream.XtreamCredentials
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

data class ConnectionReport(
    val liveCount: Int,
    val movieCount: Int,
    val seriesCount: Int,
    val account: XtreamAccount? = null,
    val capabilities: XtreamCapabilities? = null,
    val warnings: List<String> = emptyList(),
)

/** Cada tipo de fuente tiene su adaptador. La contraseña se recibe aparte y nunca se guarda en [SourceSpec]. */
/** Recibe cada sección (canales, películas o series) en cuanto está lista, para mostrarla sin esperar al resto. */
typealias SectionSink = suspend (ContentKind, Catalog) -> Unit

interface SourceAdapter {
    suspend fun test(spec: SourceSpec, password: String?): Outcome<ConnectionReport>
    suspend fun sync(spec: SourceSpec, password: String?, onSection: SectionSink? = null): Outcome<Catalog>
}

class M3uSourceAdapter(
    private val http: HttpFetcher,
    /** Abre un archivo local elegido por el usuario (en Android: ContentResolver.openInputStream). */
    private val openLocal: suspend (String) -> InputStream,
    private val parser: M3uParser = M3uParser(),
    private val builder: M3uCatalogBuilder = M3uCatalogBuilder(),
    private val maxBytes: Long = 150L * 1024 * 1024,
) : SourceAdapter {

    override suspend fun test(spec: SourceSpec, password: String?): Outcome<ConnectionReport> =
        when (val r = sync(spec, password, null)) {
            is Outcome.Ok -> Outcome.Ok(ConnectionReport(r.value.live.size, r.value.movies.size, r.value.series.size, warnings = r.value.warnings))
            is Outcome.Fail -> r
        }

    // La lectura y el análisis van en Dispatchers.IO: nunca en el hilo de la pantalla.
    override suspend fun sync(spec: SourceSpec, password: String?, onSection: SectionSink?): Outcome<Catalog> = classify { withContext(Dispatchers.IO) {
        val catalog = when (spec.type) {
            SourceType.M3U_URL -> {
                if (!UrlSafety.isHttpUrl(spec.location)) throw SourceException(SourceError.InvalidUrl)
                val headers = buildMap { spec.userAgent?.let { put("User-Agent", it) } }
                http.get(spec.location, headers).use { r ->
                    if (r.code !in 200..299) throw SourceException(if (r.code == 401 || r.code == 403) SourceError.AuthFailed else SourceError.Http(r.code))
                    val playlist = parser.parse(InputStreamReader(LimitedInputStream(r.body, maxBytes), Charsets.UTF_8), r.finalUrl)
                    builder.build(spec.id, playlist)
                }
            }
            SourceType.M3U_FILE -> openLocal(spec.location).use { input ->
                builder.build(spec.id, parser.parse(InputStreamReader(LimitedInputStream(input, maxBytes), Charsets.UTF_8), null))
            }
            SourceType.XTREAM -> throw IllegalArgumentException("Tipo de fuente no soportado por M3uSourceAdapter")
        }
        if (catalog.isEmpty) throw SourceException(SourceError.Empty)
        if (spec.epgUrl != null) catalog.copy(epgUrl = spec.epgUrl) else catalog
    } }
}

class XtreamSourceAdapter(
    private val client: XtreamClient,
    private val liveFormat: () -> LiveFormat = { LiveFormat.TS },
) : SourceAdapter {
    private fun creds(spec: SourceSpec, password: String?): XtreamCredentials {
        if (!UrlSafety.isHttpUrl(XtreamClient.normalizeBaseUrl(spec.location))) throw SourceException(SourceError.InvalidUrl)
        val user = spec.username?.trim().orEmpty()
        if (user.isEmpty() || password.isNullOrEmpty()) throw SourceException(SourceError.AuthFailed)
        return XtreamCredentials(spec.location, user, password)
    }

    override suspend fun test(spec: SourceSpec, password: String?): Outcome<ConnectionReport> = classify {
        val c = creds(spec, password)
        val account = client.authenticate(c)
        val caps = client.detectCapabilities(c)
        ConnectionReport(0, 0, 0, account, caps,
            warnings = buildList {
                if (!caps.live && !caps.vod && !caps.series) add("La cuenta es válida pero el servidor no devolvió ninguna sección.")
                account.expiresAtEpochSeconds?.let { exp ->
                    val days = (exp - System.currentTimeMillis() / 1000) / 86400
                    if (days in 0..7) add("La suscripción caduca en $days días.")
                }
            })
    }

    override suspend fun sync(spec: SourceSpec, password: String?, onSection: SectionSink?): Outcome<Catalog> = classify {
        val c = creds(spec, password)
        val account = client.authenticate(c)
        val catalog = client.loadCatalog(spec.id, c, account, liveFormat(), onSection)
        if (spec.epgUrl != null) catalog.copy(epgUrl = spec.epgUrl) else catalog
    }
}

/** Corta la lectura si se supera el tamaño máximo, evitando que una lista gigante agote la memoria. */
class LimitedInputStream(private val inner: InputStream, private val max: Long) : InputStream() {
    private var count = 0L
    private fun check(n: Long) { count += n; if (count > max) throw com.embyverse.core.net.ResponseTooLargeException(max.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
    override fun read(): Int = inner.read().also { if (it >= 0) check(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) check(it.toLong()) }
    override fun close() = inner.close()
}

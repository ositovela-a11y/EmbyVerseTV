package com.embyverse.core.xtream

import com.embyverse.core.model.Catalog
import com.embyverse.core.model.Category
import com.embyverse.core.model.ContentKind
import com.embyverse.core.model.EpgProgram
import com.embyverse.core.model.Episode
import com.embyverse.core.model.LiveChannel
import com.embyverse.core.model.Movie
import com.embyverse.core.model.Series
import com.embyverse.core.net.HttpFetcher
import com.embyverse.core.net.SourceError
import com.embyverse.core.net.SourceException
import com.embyverse.core.net.UrlSafety
import com.embyverse.core.util.arr
import com.embyverse.core.util.bool
import com.embyverse.core.util.double
import com.embyverse.core.util.firstString
import com.embyverse.core.util.int
import com.embyverse.core.util.long
import com.embyverse.core.util.obj
import com.embyverse.core.util.objects
import com.embyverse.core.util.parseDurationSeconds
import com.embyverse.core.util.splitGenres
import com.embyverse.core.util.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import com.embyverse.core.net.readLimited
import com.embyverse.core.source.LimitedInputStream
import java.io.BufferedInputStream
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import com.embyverse.core.util.Base64Compat

data class XtreamCredentials(val baseUrl: String, val username: String, val password: String) {
    override fun toString() = "XtreamCredentials(baseUrl=$baseUrl, username=$username, password=***)"
}

data class XtreamAccount(
    val status: String,
    val expiresAtEpochSeconds: Long?,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val isTrial: Boolean,
    val allowedOutputFormats: List<String>,
    val serverTimezone: String?,
)

/** Endpoints que el proveedor realmente respondió con datos válidos. */
data class XtreamCapabilities(
    val live: Boolean,
    val vod: Boolean,
    val series: Boolean,
) {
    val supportedEndpoints: List<String> get() = buildList {
        add("player_api.php (autenticación)")
        if (live) { add("get_live_categories"); add("get_live_streams"); add("get_short_epg") }
        if (vod) { add("get_vod_categories"); add("get_vod_streams"); add("get_vod_info") }
        if (series) { add("get_series_categories"); add("get_series"); add("get_series_info") }
    }
}

enum class LiveFormat(val ext: String) { TS("ts"), HLS("m3u8") }

/**
 * Adaptador para servidores que implementan la API "player_api.php" de Xtream Codes
 * (y paneles compatibles). Sólo se considera compatible si la autenticación devuelve
 * un objeto JSON con `user_info`.
 */
class XtreamClient(
    private val http: HttpFetcher,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val maxBodyBytes: Int = 64 * 1024 * 1024,
    private val maxStreamBytes: Long = 512L * 1024 * 1024,
) {
    companion object {
        /** Normaliza la URL base: quita barras finales y endpoints pegados por el usuario. */
        fun normalizeBaseUrl(input: String): String {
            var u = input.trim()
            if (!u.contains("://")) u = "http://$u"
            u = u.substringBefore('?').substringBefore('#')
            for (suffix in listOf("/player_api.php", "/get.php", "/xmltv.php", "/panel_api.php")) {
                if (u.endsWith(suffix, true)) u = u.dropLast(suffix.length)
            }
            return u.trimEnd('/')
        }

        /**
         * Extrae servidor, usuario y contraseña de una URL "get.php?username=..&password=.."
         * o "player_api.php?..." pegada por el usuario. Devuelve null si no lo es.
         */
        fun parseCredentialUrl(input: String): XtreamCredentials? {
            val u = input.trim()
            if (!UrlSafety.isHttpUrl(u)) return null
            val uri = runCatching { java.net.URI(u) }.getOrNull() ?: return null
            val q = uri.rawQuery ?: return null
            val params = q.split('&').mapNotNull {
                val i = it.indexOf('='); if (i <= 0) null else
                    it.substring(0, i).lowercase() to java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }.toMap()
            val user = params["username"] ?: return null
            val pass = params["password"] ?: return null
            return XtreamCredentials(normalizeBaseUrl(u), user, pass)
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun pathSeg(s: String) = enc(s).replace("+", "%20")
    }

    private fun api(c: XtreamCredentials, action: String? = null, extra: String = ""): String {
        val base = "${normalizeBaseUrl(c.baseUrl)}/player_api.php?username=${enc(c.username)}&password=${enc(c.password)}"
        return if (action == null) base else "$base&action=$action$extra"
    }

    private fun checkStatus(code: Int, allowNotXtream: Boolean) {
        when {
            code == 401 || code == 403 -> throw SourceException(SourceError.AuthFailed)
            code == 404 -> throw SourceException(if (allowNotXtream) SourceError.Http(404) else SourceError.NotXtream)
            code !in 200..299 -> throw SourceException(SourceError.Http(code))
        }
    }

    // IMPORTANTE: la descarga y lectura se hacen SIEMPRE en Dispatchers.IO. Antes el cuerpo se leía en
    // el hilo de quien llamaba (la pantalla) y Android cerraba la app (NetworkOnMainThreadException)
    // al abrir series o sincronizar desde la lista de fuentes.
    private suspend fun getJson(url: String, allowNotXtream: Boolean = false): JsonElement = withContext(Dispatchers.IO) {
        if (!UrlSafety.isHttpUrl(url)) throw SourceException(SourceError.InvalidUrl)
        http.get(url, mapOf("Accept" to "application/json")).use { r ->
            checkStatus(r.code, allowNotXtream)
            val text = r.readText(maxBodyBytes).trim()
            if (text.isEmpty()) return@withContext JsonArray(emptyList())
            if (!(text.startsWith("{") || text.startsWith("["))) throw SourceException(SourceError.NotXtream)
            try { json.parseToJsonElement(text) } catch (e: Exception) {
                throw SourceException(SourceError.InvalidResponse("JSON no válido"))
            }
        }
    }

    /**
     * Listas grandes (canales, películas, series): se leen EN STREAMING objeto a objeto y se convierten
     * al momento con [map]. Así 80 000 películas no ocupan cientos de MB de texto y árbol JSON a la vez
     * (eso agotaba la memoria de muchos teléfonos y la app se cerraba al cargar la lista).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> streamObjects(url: String, beforeParse: suspend () -> Unit = {}, map: (JsonObject) -> T?): List<T> = withContext(Dispatchers.IO) {
        if (!UrlSafety.isHttpUrl(url)) throw SourceException(SourceError.InvalidUrl)
        http.get(url, mapOf("Accept" to "application/json")).use { r ->
            checkStatus(r.code, false)
            beforeParse()
            val input = BufferedInputStream(LimitedInputStream(r.body, maxStreamBytes), 128 * 1024)
            // Saltar BOM y espacios para ver si es un array (lo normal) o un objeto (algunos paneles).
            input.mark(64)
            var skip = 0; var first = -1
            while (skip < 64) {
                val b = input.read()
                if (b < 0) break
                if (b == 0xEF || b == 0xBB || b == 0xBF || b == ' '.code || b == '\n'.code || b == '\r'.code || b == '\t'.code) { skip++; continue }
                first = b; break
            }
            input.reset(); input.skip(skip.toLong())
            val out = ArrayList<T>()
            when (first) {
                -1 -> Unit
                '['.code -> try {
                    json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).forEach { el ->
                        (el as? JsonObject)?.let(map)?.let(out::add)
                    }
                } catch (e: SerializationException) { if (out.isEmpty()) throw SourceException(SourceError.InvalidResponse("JSON no válido")) }
                '{'.code -> {
                    val el = try { json.parseToJsonElement(String(readLimited(input, maxBodyBytes), Charsets.UTF_8)) } catch (e: SerializationException) {
                        throw SourceException(SourceError.InvalidResponse("JSON no válido"))
                    }
                    el.objects().forEach { o -> map(o)?.let(out::add) }
                }
                else -> throw SourceException(SourceError.NotXtream)
            }
            out
        }
    }

    /** Prueba de conexión: valida credenciales y estado de la cuenta. */
    suspend fun authenticate(c: XtreamCredentials): XtreamAccount {
        if (c.username.isBlank() || c.password.isBlank()) throw SourceException(SourceError.AuthFailed)
        val root = getJson(api(c)) as? JsonObject ?: throw SourceException(SourceError.AuthFailed)
        val ui = root.obj("user_info") ?: throw SourceException(
            if (root.containsKey("server_info")) SourceError.AuthFailed else SourceError.NotXtream
        )
        if (ui.int("auth") == 0 || ui.str("auth") == null && ui.str("status") == null) throw SourceException(SourceError.AuthFailed)
        val status = ui.str("status") ?: "Active"
        if (!status.equals("Active", true)) throw SourceException(SourceError.AccountInactive(status))
        val formats = (ui["allowed_output_formats"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
        return XtreamAccount(
            status = status,
            expiresAtEpochSeconds = ui.long("exp_date"),
            maxConnections = ui.int("max_connections"),
            activeConnections = ui.int("active_cons"),
            isTrial = ui.bool("is_trial") ?: false,
            allowedOutputFormats = formats,
            serverTimezone = root.obj("server_info")?.str("timezone"),
        )
    }

    /** Detecta qué secciones ofrece el proveedor (las que fallan no se consideran disponibles). */
    suspend fun detectCapabilities(c: XtreamCredentials): XtreamCapabilities {
        suspend fun probe(action: String) = runCatching { getJson(api(c, action)) is JsonArray }.getOrDefault(false)
        return coroutineScope {
            val l = async { probe("get_live_categories") }; val v = async { probe("get_vod_categories") }; val sr = async { probe("get_series_categories") }
            XtreamCapabilities(l.await(), v.await(), sr.await())
        }
    }

    private suspend fun categories(c: XtreamCredentials, action: String): Map<String, String> =
        getJson(api(c, action)).objects().mapNotNull { o ->
            val id = o.str("category_id") ?: return@mapNotNull null
            id to (o.str("category_name") ?: "Sin categoría")
        }.toMap()

    fun liveUrl(c: XtreamCredentials, streamId: String, format: LiveFormat) =
        "${normalizeBaseUrl(c.baseUrl)}/live/${pathSeg(c.username)}/${pathSeg(c.password)}/$streamId.${format.ext}"

    fun movieUrl(c: XtreamCredentials, streamId: String, ext: String?) =
        "${normalizeBaseUrl(c.baseUrl)}/movie/${pathSeg(c.username)}/${pathSeg(c.password)}/$streamId.${ext ?: "mp4"}"

    fun episodeUrl(c: XtreamCredentials, episodeId: String, ext: String?) =
        "${normalizeBaseUrl(c.baseUrl)}/series/${pathSeg(c.username)}/${pathSeg(c.password)}/$episodeId.${ext ?: "mp4"}"

    fun xmltvUrl(c: XtreamCredentials) =
        "${normalizeBaseUrl(c.baseUrl)}/xmltv.php?username=${enc(c.username)}&password=${enc(c.password)}"

    /** Descarga el catálogo completo. Las secciones no disponibles se omiten con un aviso. */
    suspend fun loadCatalog(
        sourceId: String,
        c: XtreamCredentials,
        account: XtreamAccount,
        preferred: LiveFormat = LiveFormat.TS,
        onSection: (suspend (ContentKind, Catalog) -> Unit)? = null,
    ): Catalog {
        val format = pickFormat(account.allowedOutputFormats, preferred)

        // Las tres secciones (y dentro de cada una, categorías y contenido) se piden EN PARALELO:
        // de ~10 peticiones seguidas se pasa a 2 "rondas", lo que reduce mucho la primera carga.
        // Si las categorías de una sección fallan, el proveedor no la ofrece y se omite (igual que antes).
        class Part<T>(val items: List<T>, val cats: List<Category>, val warning: String?, val available: Boolean)
        suspend fun <T> section(kind: ContentKind, catAction: String, action: String, label: String, map: (JsonObject, Map<String, String>) -> T?, id: (T) -> String, gate: Deferred<Unit>? = null): Part<T> =
            coroutineScope {
                val namesD = async { runCatching { categories(c, catAction) } }
                // La lista se pide a la vez que las categorías; se espera a éstas solo antes de leerla.
                val streamsD = async {
                    runCatching {
                        var names: Map<String, String> = emptyMap()
                        streamObjects(api(c, action), beforeParse = { names = namesD.await().getOrDefault(emptyMap()); gate?.await() }) { o -> map(o, names) }
                    }
                }
                val names = namesD.await().getOrElse { streamsD.cancel(); return@coroutineScope Part(emptyList(), emptyList(), null, false) }
                val streams = streamsD.await().getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    return@coroutineScope Part(emptyList(), emptyList(),
                        "No se pudieron cargar $label: ${(it as? SourceException)?.error?.userMessage ?: it.javaClass.simpleName}", true)
                }
                val items = streams.distinctBy(id)
                val cats = names.map { (cid, n) -> Category(sourceId, kind, cid, n) }
                // Entrega inmediata: los canales (rápidos) se guardan y muestran sin esperar a las películas.
                if (onSection != null) {
                    @Suppress("UNCHECKED_CAST")
                    val part = when (kind) {
                        ContentKind.LIVE -> Catalog(sourceId, live = items as List<LiveChannel>, categories = cats)
                        ContentKind.MOVIE -> Catalog(sourceId, movies = items as List<Movie>, categories = cats)
                        else -> Catalog(sourceId, series = items as List<Series>, categories = cats)
                    }
                    onSection(kind, part)
                }
                Part(items, cats, null, true)
            }

        // Prioridad a los canales: películas y series se DESCARGAN a la vez, pero se leen y guardan
        // cuando los canales ya están listos (en teléfonos modestos aparecen en segundos, no en un minuto).
        val liveReady = CompletableDeferred<Unit>()
        val (liveP, movieP, seriesP) = coroutineScope {
            val l = async {
                try { section(ContentKind.LIVE, "get_live_categories", "get_live_streams", "los canales en vivo", { o, names ->
                    val id = o.str("stream_id") ?: return@section null
                    LiveChannel(
                        sourceId = sourceId, providerId = id, name = o.str("name") ?: "Canal $id",
                        streamUrl = liveUrl(c, id, format),
                        logoUrl = o.str("stream_icon")?.takeIf { UrlSafety.isHttpUrl(it) },
                        categoryName = o.str("category_id")?.let { names[it] },
                        epgChannelId = o.str("epg_channel_id"), number = o.int("num"),
                        catchupDays = if (o.bool("tv_archive") == true) o.int("tv_archive_duration") ?: 0 else 0,
                    )
                }, { it.providerId }) } finally { liveReady.complete(Unit) }
            }
            val m = async {
                section(ContentKind.MOVIE, "get_vod_categories", "get_vod_streams", "las películas", { o, names ->
                    val id = o.str("stream_id") ?: return@section null
                    val ext = o.str("container_extension")
                    Movie(
                        sourceId = sourceId, providerId = id, title = o.str("name") ?: "Película $id",
                        streamUrl = movieUrl(c, id, ext),
                        posterUrl = o.str("stream_icon")?.takeIf { UrlSafety.isHttpUrl(it) },
                        categoryName = o.str("category_id")?.let { names[it] },
                        plot = o.str("plot"), year = o.int("year"),
                        rating = o.double("rating")?.takeIf { it in 0.0..10.0 },
                        genres = splitGenres(o.str("genre")),
                        addedAtEpochSeconds = o.long("added"), containerExtension = ext,
                    )
                }, { it.providerId }, liveReady)
            }
            val sr = async {
                section(ContentKind.SERIES, "get_series_categories", "get_series", "las series", { o, names ->
                    val id = o.str("series_id") ?: return@section null
                    Series(
                        sourceId = sourceId, providerId = id, title = o.str("name") ?: "Serie $id",
                        posterUrl = o.str("cover")?.takeIf { UrlSafety.isHttpUrl(it) },
                        backdropUrl = o.firstString("backdrop_path")?.takeIf { UrlSafety.isHttpUrl(it) },
                        categoryName = o.str("category_id")?.let { names[it] },
                        plot = o.str("plot"), year = (o.str("releaseDate") ?: o.str("release_date"))?.take(4)?.toIntOrNull(),
                        rating = o.double("rating")?.takeIf { it in 0.0..10.0 },
                        genres = splitGenres(o.str("genre")),
                        addedAtEpochSeconds = o.long("last_modified"),
                    )
                }, { it.providerId }, liveReady)
            }
            Triple(l.await(), m.await(), sr.await())
        }
        if (!liveP.available && !movieP.available && !seriesP.available) throw SourceException(SourceError.Empty)
        val live = liveP.items; val movies = movieP.items; val series = seriesP.items
        val cats = liveP.cats + movieP.cats + seriesP.cats
        val warnings = listOfNotNull(liveP.warning, movieP.warning, seriesP.warning)

        val catalog = Catalog(sourceId, live, movies, series, cats, epgUrl = xmltvUrl(c), warnings = warnings)
        if (catalog.isEmpty) throw SourceException(SourceError.Empty)
        return catalog
    }

    /** Temporadas y episodios de una serie (bajo demanda). Acepta `episodes` como objeto o como array. */
    suspend fun seriesEpisodes(sourceId: String, c: XtreamCredentials, seriesId: String): List<Episode> {
        val root = getJson(api(c, "get_series_info", "&series_id=${enc(seriesId)}")) as? JsonObject
            ?: throw SourceException(SourceError.InvalidResponse("get_series_info"))
        val eps = root["episodes"] ?: return emptyList()
        val list = when (eps) {
            is JsonObject -> eps.entries.flatMap { (season, v) -> (v as? JsonArray)?.objects().orEmpty().map { season.toIntOrNull() to it } }
            is JsonArray -> eps.flatMap { el -> when (el) { is JsonArray -> el.objects().map { null to it }; is JsonObject -> listOf(null to el); else -> emptyList() } }
            else -> emptyList()
        }
        return list.mapNotNull { (seasonKey, o) ->
            val id = o.str("id") ?: return@mapNotNull null
            val info = o.obj("info")
            Episode(
                sourceId = sourceId, providerId = id, seriesProviderId = seriesId,
                season = o.int("season") ?: seasonKey ?: 1,
                episode = o.int("episode_num") ?: 0,
                title = o.str("title") ?: "Episodio",
                streamUrl = episodeUrl(c, id, o.str("container_extension")),
                plot = info?.str("plot"),
                thumbnailUrl = info?.str("movie_image")?.takeIf { UrlSafety.isHttpUrl(it) },
                durationSeconds = info?.long("duration_secs") ?: parseDurationSeconds(info?.str("duration")),
            )
        }.distinctBy { it.providerId }.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** Ficha ampliada de una película (sinopsis, géneros, fondo). */
    suspend fun movieDetails(movie: Movie, c: XtreamCredentials): Movie {
        val root = getJson(api(c, "get_vod_info", "&vod_id=${enc(movie.providerId)}")) as? JsonObject ?: return movie
        val info = root.obj("info") ?: return movie
        return movie.copy(
            plot = info.str("plot") ?: info.str("description") ?: movie.plot,
            genres = splitGenres(info.str("genre")).ifEmpty { movie.genres },
            rating = info.double("rating")?.takeIf { it in 0.0..10.0 } ?: movie.rating,
            durationSeconds = info.long("duration_secs") ?: parseDurationSeconds(info.str("duration")) ?: movie.durationSeconds,
            backdropUrl = info.firstString("backdrop_path")?.takeIf { UrlSafety.isHttpUrl(it) } ?: movie.backdropUrl,
            posterUrl = movie.posterUrl ?: info.str("movie_image")?.takeIf { UrlSafety.isHttpUrl(it) },
            year = movie.year ?: (info.str("releasedate") ?: info.str("release_date"))?.take(4)?.toIntOrNull(),
        )
    }

    /** EPG corta de un canal. Los títulos vienen en Base64 en la mayoría de paneles. */
    suspend fun shortEpg(c: XtreamCredentials, streamId: String, channelId: String, limit: Int = 8): List<EpgProgram> {
        val root = getJson(api(c, "get_short_epg", "&stream_id=${enc(streamId)}&limit=$limit")) as? JsonObject ?: return emptyList()
        return root.arr("epg_listings")?.objects().orEmpty().mapNotNull { o ->
            val start = o.long("start_timestamp") ?: return@mapNotNull null
            val end = o.long("stop_timestamp") ?: o.long("end_timestamp") ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            EpgProgram(channelId, start, end, decodeMaybeBase64(o.str("title")) ?: "Sin título", decodeMaybeBase64(o.str("description")))
        }
    }

    private fun pickFormat(allowed: List<String>, preferred: LiveFormat): LiveFormat {
        if (allowed.isEmpty()) return preferred
        val a = allowed.map { it.lowercase() }
        return when {
            preferred.ext in a -> preferred
            "m3u8" in a -> LiveFormat.HLS
            "ts" in a -> LiveFormat.TS
            else -> preferred
        }
    }

    private fun decodeMaybeBase64(s: String?): String? {
        if (s.isNullOrBlank()) return null
        if (!s.matches(Regex("^[A-Za-z0-9+/=\\r\\n]+$")) || s.length % 4 != 0) return s
        return runCatching { String(Base64Compat.decode(s), Charsets.UTF_8) }
            .getOrNull()?.takeIf { d -> d.isNotBlank() && '�' !in d && d.none { it.code < 9 || it.code in 14..31 } } ?: s
    }
}

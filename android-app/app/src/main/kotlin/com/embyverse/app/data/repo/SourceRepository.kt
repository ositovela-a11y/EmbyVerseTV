package com.embyverse.app.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.embyverse.app.data.db.CategoryEntity
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.app.data.db.EpisodeEntity
import com.embyverse.app.data.db.MovieEntity
import com.embyverse.app.data.db.SeriesEntity
import com.embyverse.app.data.db.SourceEntity
import com.embyverse.app.data.prefs.PreferencesRepository
import com.embyverse.app.data.secure.CredentialStore
import com.embyverse.core.catalog.CatalogDiff
import com.embyverse.core.m3u.M3uCatalogBuilder
import android.util.Log
import com.embyverse.core.model.Catalog
import com.embyverse.core.model.ContentKind
import com.embyverse.core.model.Episode
import com.embyverse.core.model.Movie
import com.embyverse.core.model.SourceSpec
import com.embyverse.core.model.SourceType
import com.embyverse.core.net.HttpFetcher
import com.embyverse.core.net.Outcome
import com.embyverse.core.net.SourceError
import com.embyverse.core.net.classify
import com.embyverse.core.source.ConnectionReport
import com.embyverse.core.source.M3uSourceAdapter
import com.embyverse.core.source.SourceAdapter
import com.embyverse.core.source.XtreamSourceAdapter
import com.embyverse.core.xtream.XtreamClient
import com.embyverse.core.xtream.XtreamCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.UUID

/** Datos introducidos en el formulario de fuente (la contraseña sólo vive en memoria). */
data class SourceDraft(
    val id: String? = null,
    val name: String,
    val type: SourceType,
    val location: String,
    val username: String? = null,
    val password: String? = null,
    val userAgent: String? = null,
    val epgUrl: String? = null,
)

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val live: Int, val movies: Int, val series: Int, val warnings: List<String>) : SyncState
    data class Failed(val error: SourceError) : SyncState
}

private const val TAG = "EmbyVerse"

class SourceRepository(
    private val context: Context,
    private val db: EmbyDatabase,
    private val credentials: CredentialStore,
    http: HttpFetcher,
    private val prefs: PreferencesRepository,
) {
    private val xtreamClient = XtreamClient(http)
    private val m3uAdapter = M3uSourceAdapter(http, openLocal = { uri ->
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(Uri.parse(uri)) ?: throw java.io.FileNotFoundException("No se puede abrir el archivo")
        }
    })

    private val _syncState = MutableStateFlow<Map<String, SyncState>>(emptyMap())
    val syncState: StateFlow<Map<String, SyncState>> = _syncState

    val sources: Flow<List<SourceEntity>> = db.sources().observeAll()

    private suspend fun adapter(type: SourceType): SourceAdapter = when (type) {
        SourceType.M3U_URL, SourceType.M3U_FILE -> m3uAdapter
        SourceType.XTREAM -> {
            val format = prefs.playback.first().liveFormat
            XtreamSourceAdapter(xtreamClient) { format }
        }
    }

    fun specOf(e: SourceEntity) = SourceSpec(
        id = e.id, name = e.name, type = SourceType.valueOf(e.type), location = e.location,
        username = e.username, userAgent = e.userAgent, epgUrl = e.epgUrl,
    )

    private fun specOf(d: SourceDraft) = SourceSpec(
        d.id ?: "draft", d.name.trim(), d.type, d.location.trim(), d.username?.trim()?.ifEmpty { null },
        d.userAgent?.trim()?.ifEmpty { null }, d.epgUrl?.trim()?.ifEmpty { null },
    )

    /** Prueba la conexión sin guardar nada. Si se edita una fuente sin reescribir la contraseña, usa la guardada. */
    suspend fun test(draft: SourceDraft): Outcome<ConnectionReport> = withContext(Dispatchers.IO) {
        val pw = draft.password?.takeIf { it.isNotEmpty() } ?: draft.id?.let { credentials.get(it) }
        adapter(draft.type).test(specOf(draft), pw)
    }

    suspend fun save(draft: SourceDraft): String = withContext(Dispatchers.IO) {
        val id = draft.id ?: UUID.randomUUID().toString()
        val existing = db.sources().get(id)
        val spec = specOf(draft.copy(id = id))
        db.sources().upsert(
            SourceEntity(
                id = id, name = spec.name.ifBlank { "Fuente" }, type = spec.type.name, location = spec.location,
                username = spec.username, userAgent = spec.userAgent, epgUrl = spec.epgUrl,
                detectedEpgUrl = existing?.detectedEpgUrl, createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                lastSyncAt = existing?.lastSyncAt, lastError = existing?.lastError,
                liveCount = existing?.liveCount ?: 0, movieCount = existing?.movieCount ?: 0, seriesCount = existing?.seriesCount ?: 0,
                sortOrder = existing?.sortOrder ?: db.sources().count(),
            )
        )
        if (draft.type == SourceType.XTREAM && !draft.password.isNullOrEmpty()) credentials.put(id, draft.password)
        if (draft.type != SourceType.XTREAM) credentials.remove(id)
        // Si el usuario eligió un archivo local, conservar el permiso de lectura tras reiniciar.
        if (draft.type == SourceType.M3U_FILE) runCatching {
            context.contentResolver.takePersistableUriPermission(Uri.parse(spec.location), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        id
    }

    suspend fun delete(id: String) = db.withTransaction {
        db.channels().fingerprints(id).map { it.providerId }.chunked(500).forEach { db.channels().delete(id, it) }
        db.movies().fingerprints(id).map { it.providerId }.chunked(500).forEach { db.movies().delete(id, it) }
        db.series().fingerprints(id).map { it.providerId }.chunked(500).forEach { db.series().delete(id, it) }
        db.episodes().deleteOrphans(id)
        db.categories().deleteForSource(id)
        db.epg().deleteForSource(id)
        db.library().deleteFavoritesForSource(id)
        db.library().deleteProgressForSource(id)
        db.sources().delete(id)
        credentials.remove(id)
    }

    suspend fun syncAll(olderThanMs: Long = 0) {
        val now = System.currentTimeMillis()
        db.sources().all().filter { (it.lastSyncAt ?: 0) < now - olderThanMs }.forEach { sync(it.id) }
    }

    /** Descarga el catálogo y lo aplica a la base local sin duplicar (altas, cambios y bajas). */
    suspend fun sync(id: String): SyncState = withContext(Dispatchers.IO) { syncInternal(id) }

    private suspend fun syncInternal(id: String): SyncState {
        val src = db.sources().get(id) ?: return SyncState.Failed(SourceError.InvalidUrl)
        _syncState.update { it + (id to SyncState.Running) }
        val spec = specOf(src)
        val password = credentials.get(id)
        if (spec.type == SourceType.XTREAM && password == null) {
            return fail(id, SourceError.AuthFailed)
        }
        val t0 = System.currentTimeMillis()
        // Cada sección se guarda en cuanto llega: los canales aparecen en segundos aunque las
        // películas (decenas de miles) sigan descargándose.
        val applied = java.util.Collections.synchronizedSet(HashSet<ContentKind>())
        val onSection: suspend (ContentKind, Catalog) -> Unit = { kind, part ->
            Log.i(TAG, "sincronización: sección $kind descargada y leída en ${System.currentTimeMillis() - t0} ms")
            applyKind(spec, password, kind, part)
            applied += kind
            Log.i(TAG, "sincronización: sección $kind lista en ${System.currentTimeMillis() - t0} ms")
        }
        val state = when (val r = adapter(spec.type).sync(spec, password, onSection)) {
            is Outcome.Fail -> fail(id, r.error)
            is Outcome.Ok -> {
                val c = r.value
                val done = classify {
                    // M3U: todo llega a la vez. Xtream: una sección que falló NO borra lo que ya había.
                    for (k in listOf(ContentKind.LIVE, ContentKind.MOVIE, ContentKind.SERIES)) {
                        if (k !in applied && spec.type != SourceType.XTREAM) applyKind(spec, password, k, c)
                    }
                    val detectedEpg = if (spec.type == SourceType.XTREAM) null else c.epgUrl
                    db.sources().setSynced(spec.id, System.currentTimeMillis(), c.live.size, c.movies.size, c.series.size, detectedEpg)
                }
                Log.i(TAG, "sincronización completa en ${System.currentTimeMillis() - t0} ms")
                when (done) {
                    is Outcome.Ok -> SyncState.Done(c.live.size, c.movies.size, c.series.size, c.warnings)
                    is Outcome.Fail -> fail(id, done.error)
                }
            }
        }
        _syncState.update { it + (id to state) }
        return state
    }

    private suspend fun fail(id: String, e: SourceError): SyncState {
        db.sources().setError(id, e.userMessage)
        val s = SyncState.Failed(e)
        _syncState.update { it + (id to s) }
        return s
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Sustituye las credenciales de la URL por marcadores para no guardarlas en la base de datos. */
    private fun tokenize(url: String, spec: SourceSpec, password: String?): String {
        if (spec.type != SourceType.XTREAM || spec.username == null || password == null) return url
        return url.replace("/${enc(spec.username!!)}/${enc(password)}/", "/{u}/{p}/")
    }

    /** Inversa de [tokenize], en memoria y justo antes de reproducir. */
    suspend fun resolveStreamUrl(sourceId: String, stored: String): String {
        if (!stored.contains("/{u}/{p}/")) return stored
        val src = db.sources().get(sourceId) ?: return stored
        val pw = credentials.get(sourceId) ?: return stored
        return stored.replace("/{u}/{p}/", "/${enc(src.username.orEmpty())}/${enc(pw)}/")
    }

    /** Aplica una sección a la base local sin duplicar (altas, cambios y bajas) y sus categorías. */
    private suspend fun applyKind(spec: SourceSpec, password: String?, kind: ContentKind, c: Catalog) = withContext(Dispatchers.IO) {
        val sid = spec.id
        val t0 = System.currentTimeMillis()
        db.withTransaction {
            Log.i(TAG, "guardar $kind: inicio (espera de la base ${System.currentTimeMillis() - t0} ms)")
            when (kind) {
                ContentKind.LIVE -> {
                    val ch = c.live.mapIndexed { i, x ->
                        ChannelEntity(sid, x.providerId, x.name, M3uCatalogBuilder.normalize(x.name), tokenize(x.streamUrl, spec, password),
                            x.logoUrl, x.categoryName, x.epgChannelId, x.number, x.headers.userAgent, x.headers.referrer, x.catchupDays,
                            i, 31 * x.hashCode() + i)
                    }
                    val plan = CatalogDiff.plan(db.channels().fingerprints(sid).associate { it.providerId to it.fingerprint }, ch, { it.providerId }, { it.fingerprint })
                    plan.upsert.chunked(500).forEach { db.channels().upsert(it) }
                    plan.deleteIds.chunked(500).forEach { db.channels().delete(sid, it) }
                }
                ContentKind.MOVIE -> {
                    val mv = c.movies.mapIndexed { i, x -> movieEntity(sid, x, spec, password, i) }
                    val plan = CatalogDiff.plan(db.movies().fingerprints(sid).associate { it.providerId to it.fingerprint }, mv, { it.providerId }, { it.fingerprint })
                    plan.upsert.chunked(500).forEach { db.movies().upsert(it) }
                    plan.deleteIds.chunked(500).forEach { db.movies().delete(sid, it) }
                }
                else -> {
                    // Series (y episodios cuando la lista ya los trae, como en M3U)
                    val se = c.series.mapIndexed { i, x ->
                        SeriesEntity(sid, x.providerId, x.title, M3uCatalogBuilder.normalize(x.title), x.posterUrl, x.backdropUrl, x.categoryName,
                            x.plot, x.year, x.rating, x.genres.joinToString("|"), x.addedAtEpochSeconds, i, 31 * x.hashCode() + i,
                            episodesLoaded = x.episodes.isNotEmpty())
                    }
                    val plan = CatalogDiff.plan(db.series().fingerprints(sid).associate { it.providerId to it.fingerprint }, se, { it.providerId }, { it.fingerprint })
                    plan.upsert.chunked(500).forEach { db.series().upsert(it) }
                    plan.deleteIds.chunked(500).forEach { db.series().delete(sid, it) }
                    val changed = plan.upsert.map { it.providerId }.toSet()
                    c.series.filter { it.providerId in changed && it.episodes.isNotEmpty() }.forEach { s ->
                        db.episodes().deleteForSeries(sid, s.providerId)
                        db.episodes().upsert(s.episodes.map { episodeEntity(sid, it, spec, password) }.distinctBy { it.providerId })
                    }
                    db.episodes().deleteOrphans(sid)
                }
            }
            val k = if (kind == ContentKind.EPISODE) ContentKind.SERIES else kind
            db.categories().deleteForSourceKind(sid, k.name)
            db.categories().upsert(c.categories.filter { it.kind == k }.mapIndexed { i, cat -> CategoryEntity(sid, cat.kind.name, cat.name, i) }.distinctBy { it.name })
        }
        Log.i(TAG, "guardar $kind: ${System.currentTimeMillis() - t0} ms")
    }

    private fun movieEntity(sid: String, x: Movie, spec: SourceSpec, pw: String?, i: Int) = MovieEntity(
        sid, x.providerId, x.title, M3uCatalogBuilder.normalize(x.title), tokenize(x.streamUrl, spec, pw), x.posterUrl, x.backdropUrl,
        x.categoryName, x.plot, x.year, x.rating, x.genres.joinToString("|"), x.durationSeconds, x.addedAtEpochSeconds,
        x.containerExtension, x.headers.userAgent, x.headers.referrer, i, 31 * x.hashCode() + i,
    )

    private fun episodeEntity(sid: String, e: Episode, spec: SourceSpec, pw: String?) = EpisodeEntity(
        sid, e.providerId, e.seriesProviderId, e.season, e.episode, e.title, tokenize(e.streamUrl, spec, pw), e.plot,
        e.thumbnailUrl, e.durationSeconds, e.headers.userAgent, e.headers.referrer,
    )

    private suspend fun xtreamCreds(sourceId: String): Pair<SourceSpec, XtreamCredentials>? {
        val src = db.sources().get(sourceId) ?: return null
        if (src.type != SourceType.XTREAM.name) return null
        val pw = credentials.get(sourceId) ?: return null
        return specOf(src) to XtreamCredentials(src.location, src.username.orEmpty(), pw)
    }

    /** Temporadas y episodios bajo demanda (proveedores Xtream). */
    suspend fun loadEpisodes(sourceId: String, seriesId: String): Outcome<Int> = classify { withContext(Dispatchers.IO) {
        val (spec, creds) = xtreamCreds(sourceId) ?: return@withContext db.episodes().forSeries(sourceId, seriesId).size
        val eps = xtreamClient.seriesEpisodes(sourceId, creds, seriesId)
        db.withTransaction {
            db.episodes().deleteForSeries(sourceId, seriesId)
            db.episodes().upsert(eps.map { episodeEntity(sourceId, it, spec, creds.password) })
            db.series().setEpisodesLoaded(sourceId, seriesId, true)
        }
        eps.size
    } }

    /** Sinopsis y metadatos ampliados de una película (proveedores Xtream). */
    suspend fun loadMovieDetails(sourceId: String, movieId: String) { classify { withContext(Dispatchers.IO) { loadMovieDetailsInternal(sourceId, movieId) } } }

    private suspend fun loadMovieDetailsInternal(sourceId: String, movieId: String) {
        val (_, creds) = xtreamCreds(sourceId) ?: return
        val m = db.movies().get(sourceId, movieId) ?: return
        if (m.detailsLoaded) return
        val base = Movie(sourceId, m.providerId, m.title, m.streamUrl, m.posterUrl, m.backdropUrl, m.category, m.plot, m.year, m.rating,
            m.genres.split('|').filter { it.isNotBlank() }, m.durationSeconds, m.addedAt, m.containerExtension)
        val r = classify { xtreamClient.movieDetails(base, creds) }
        if (r is Outcome.Ok) {
            val d = r.value
            db.movies().upsert(listOf(m.copy(plot = d.plot, genres = d.genres.joinToString("|"), rating = d.rating,
                durationSeconds = d.durationSeconds, backdropUrl = d.backdropUrl, posterUrl = d.posterUrl, year = d.year,
                detailsLoaded = true)))
        }
    }

    /** URL de la guía XMLTV de una fuente: la indicada por el usuario, la de la lista o la del servidor Xtream. */
    suspend fun epgUrlFor(sourceId: String): String? {
        val src = db.sources().get(sourceId) ?: return null
        src.epgUrl?.let { return it }
        if (src.type == SourceType.XTREAM.name) {
            val pw = credentials.get(sourceId) ?: return null
            return xtreamClient.xmltvUrl(XtreamCredentials(src.location, src.username.orEmpty(), pw))
        }
        return src.detectedEpgUrl
    }

    fun parseCredentialUrl(text: String) = XtreamClient.parseCredentialUrl(text)
}

package com.embyverse.app.data.repo

import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.app.data.db.FavoriteEntity
import com.embyverse.app.data.db.MovieEntity
import com.embyverse.app.data.db.ProfileEntity
import com.embyverse.app.data.db.ProgressEntity
import com.embyverse.app.data.db.SeriesEntity
import com.embyverse.app.data.prefs.PreferencesRepository
import com.embyverse.core.catalog.RecItem
import com.embyverse.core.catalog.Recommender
import com.embyverse.core.m3u.M3uCatalogBuilder
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Referencia ligera a un elemento reproducible o navegable. */
data class ItemRef(val kind: ContentKind, val sourceId: String, val id: String)

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) { val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() }

class CatalogRepository(private val db: EmbyDatabase, private val hidden: Flow<Set<String>> = kotlinx.coroutines.flow.flowOf(emptySet())) {
    /** Quita lo que el control parental del perfil activo oculta. */
    private fun <T> Flow<List<T>>.visible(category: (T) -> String?): Flow<List<T>> =
        kotlinx.coroutines.flow.combine(this, hidden) { l, h -> if (h.isEmpty()) l else l.filter { category(it) !in h } }

    fun channels(category: String?, limit: Int = 5000) = (if (category == null) db.channels().observeAll(limit) else db.channels().observeIn(category, limit)).visible { it.category }
    fun movies(category: String?, limit: Int = 5000) = (if (category == null) db.movies().observeAll(limit) else db.movies().observeIn(category, limit)).visible { it.category }
    fun series(category: String?, limit: Int = 5000) = (if (category == null) db.series().observeAll(limit) else db.series().observeIn(category, limit)).visible { it.category }
    fun recentMovies(limit: Int) = db.movies().observeRecent(limit).visible { it.category }
    fun recentSeries(limit: Int) = db.series().observeRecent(limit).visible { it.category }
    fun categories(kind: ContentKind) = kotlinx.coroutines.flow.combine(db.categories().observeNames(kind.name), hidden) { l, h -> if (h.isEmpty()) l else l.filter { it !in h } }
    fun hiddenNow(): Flow<Set<String>> = hidden
    fun eventCandidates() = db.channels().observeEventCandidates().visible { it.category }
    fun movie(sourceId: String, id: String) = db.movies().observeOne(sourceId, id)
    fun seriesOne(sourceId: String, id: String) = db.series().observeOne(sourceId, id)
    fun episodes(sourceId: String, seriesId: String) = db.episodes().observeForSeries(sourceId, seriesId)
    val channelCount = db.channels().observeCount()

    /**
     * Búsqueda inteligente: sin acentos, sin importar guiones o espacios («spiderman» = «Spider-Man»), palabras en
     * cualquier orden, por género («terror», «comedia») y, si no hay nada, con faltas de ortografía («batmn»).
     */
    suspend fun search(query: String, limit: Int = 60): SearchResults {
        val q = M3uCatalogBuilder.normalize(query)
        if (q.length < 2) return SearchResults()
        val h = hidden.first()
        val words = q.split(' ').filter { it.length >= 2 }
        val longest = words.maxByOrNull { it.length } ?: q
        val compact = q.replace(" ", "")
        fun matches(key: String, genres: String = ""): Boolean {
            val k = key.replace(" ", "")
            return k.contains(compact) || words.all { key.contains(it) } || (genres.isNotEmpty() && M3uCatalogBuilder.normalize(genres).contains(q))
        }
        fun rank(key: String): Int = when { key == q -> 0; key.startsWith(q) -> 1; key.contains(" $q") -> 2; else -> 3 }
        val ch = (db.channels().search(longest, 300) + db.channels().searchCompact(compact, 120))
            .distinctBy { it.sourceId + it.providerId }.filter { it.category !in h && matches(it.searchKey) }.sortedBy { rank(it.searchKey) }.take(limit)
        var mv = (db.movies().search(longest, 400) + db.movies().searchCompact(compact, 150) + db.movies().searchGenre(query.trim(), 80))
            .distinctBy { it.sourceId + it.providerId }.filter { it.category !in h && matches(it.searchKey, it.genres) }.sortedBy { rank(it.searchKey) }.take(limit)
        var se = (db.series().search(longest, 400) + db.series().searchCompact(compact, 150) + db.series().searchGenre(query.trim(), 80))
            .distinctBy { it.sourceId + it.providerId }.filter { it.category !in h && matches(it.searchKey, it.genres) }.sortedBy { rank(it.searchKey) }.take(limit)
        // Sin resultados: se toleran 1–2 letras de diferencia por palabra.
        if (ch.isEmpty() && mv.isEmpty() && se.isEmpty() && longest.length >= 4) {
            val p = longest.take(2)
            fun fuzzy(key: String) = words.all { w -> key.split(' ').any { kw -> kw.length >= 2 && editDistance(kw, w) <= (if (w.length >= 7) 2 else 1) } }
            mv = db.movies().searchPrefix(p, 3000).filter { it.category !in h && fuzzy(it.searchKey) }.take(limit)
            se = db.series().searchPrefix(p, 2000).filter { it.category !in h && fuzzy(it.searchKey) }.take(limit)
        }
        return SearchResults(ch, mv, se)
    }

    // ------------------------------------------------------------------ sagas
    data class Saga(val key: String, val name: String, val imageUrl: String?, val count: Int)

    /** Clave de saga: título sin año, sin subtítulo y sin número de parte («Toy Story 3» → «toy story»). */
    fun sagaKey(title: String): String {
        var t = title.substringBefore(':').substringBefore(" - ").substringBefore(" – ")
        t = M3uCatalogBuilder.normalize(t)
        t = t.replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
        t = t.replace(Regex("\\b(parte|part|capitulo|chapter|vol|volumen|episodio)\\s*\\w*\\s*$"), " ")
        t = t.replace(Regex("\\s+(\\d{1,2}|ii|iii|iv|v|vi|vii|viii|ix|x)\\s*$"), " ")
        return t.trim().replace(Regex("\\s+"), " ")
    }

    @Volatile private var sagaCache: Pair<Long, List<Saga>>? = null

    /** Sagas automáticas: 2+ películas distintas con la misma clave (Harry Potter, Rápidos y furiosos…). */
    suspend fun sagas(limit: Int = 24): List<Saga> {
        sagaCache?.let { (at, l) -> if (System.currentTimeMillis() - at < 30 * 60_000L) return l }
        val h = hidden.first()
        val rows = db.movies().allCards(25_000).filter { it.category !in h }
        val groups = rows.groupBy { sagaKey(it.title) }.filterKeys { it.length >= 4 }
            .mapValues { (_, l) -> l.distinctBy { M3uCatalogBuilder.normalize(it.title) } }
            // Una saga real tiene de 2 a 12 películas (más de eso suele ser un nombre genérico, p. ej. «Película 1…300»).
            .filter { (k, l) -> l.size in 2..12 && l.any { M3uCatalogBuilder.normalize(it.title) != k } }
        val out = groups.entries.sortedByDescending { it.value.size }.take(limit).map { (k, l) ->
            val first = l.sortedBy { it.year ?: 9999 }.first()
            Saga(k, first.title.substringBefore(':').replace(Regex("\\s*\\(?(19|20)\\d{2}\\)?"), "").replace(Regex("\\s+(\\d{1,2}|II|III|IV)\\s*$"), "").trim(),
                first.posterUrl ?: first.backdropUrl, l.size)
        }
        sagaCache = System.currentTimeMillis() to out
        return out
    }

    suspend fun sagaMovies(key: String): List<MediaCard> {
        val h = hidden.first()
        val first = key.split(' ').maxByOrNull { it.length } ?: key
        return db.movies().search(first, 2000).filter { it.category !in h && sagaKey(it.title) == key }
            .distinctBy { M3uCatalogBuilder.normalize(it.title) }.sortedBy { it.year ?: 9999 }.map { it.toCard() }
    }

    private fun editDistance(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    suspend fun recommendations(history: List<ProgressEntity>, favorites: List<FavoriteEntity>, limit: Int): List<RecItem> {
        val movies = db.movies().candidates(1500)
        val series = db.series().candidates(1500)
        val byKey = HashMap<String, RecItem>()
        movies.forEach { m -> byKey["MOVIE|${m.sourceId}|${m.providerId}"] = RecItem("MOVIE|${m.sourceId}|${m.providerId}", ContentKind.MOVIE, m.title, m.genres.split('|').filter { it.isNotBlank() }, m.category, m.rating, m.addedAt) }
        series.forEach { s -> byKey["SERIES|${s.sourceId}|${s.providerId}"] = RecItem("SERIES|${s.sourceId}|${s.providerId}", ContentKind.SERIES, s.title, s.genres.split('|').filter { it.isNotBlank() }, s.category, s.rating, s.addedAt) }
        suspend fun toRec(kind: String, sourceId: String, itemId: String, seriesId: String?): RecItem? {
            val k = if (kind == "EPISODE" && seriesId != null) "SERIES|$sourceId|$seriesId" else "$kind|$sourceId|$itemId"
            byKey[k]?.let { return it }
            return when (kind) {
                "MOVIE" -> db.movies().get(sourceId, itemId)?.let { RecItem(k, ContentKind.MOVIE, it.title, it.genres.split('|'), it.category, it.rating, it.addedAt) }
                "SERIES" -> db.series().get(sourceId, itemId)?.let { RecItem(k, ContentKind.SERIES, it.title, it.genres.split('|'), it.category, it.rating, it.addedAt) }
                "EPISODE" -> seriesId?.let { sid -> db.series().get(sourceId, sid) }?.let { RecItem(k, ContentKind.SERIES, it.title, it.genres.split('|'), it.category, it.rating, it.addedAt) }
                else -> null
            }
        }
        val h = history.mapNotNull { toRec(it.kind, it.sourceId, it.itemId, it.seriesId) }
        val f = favorites.mapNotNull { toRec(it.kind, it.sourceId, it.itemId, null) }
        return Recommender.recommend(h, f, byKey.values.toList(), System.currentTimeMillis() / 1000, limit)
    }

    /**
     * «Porque viste X»: para lo último que vio el perfil (máx. [rows] títulos distintos), títulos del
     * mismo género (o de la misma carpeta si no hay géneros) que aún no ha visto.
     */
    suspend fun becauseYouWatched(history: List<ProgressEntity>, rows: Int = 2, perRow: Int = 15): List<Pair<String, List<MediaCard>>> {
        if (history.isEmpty()) return emptyList()
        val movies = db.movies().candidates(1500)
        val series = db.series().candidates(1500)
        val all = movies.map { RecItem("MOVIE|${it.sourceId}|${it.providerId}", ContentKind.MOVIE, it.title, it.genres.split('|').filter(String::isNotBlank), it.category, it.rating, it.addedAt) } +
            series.map { RecItem("SERIES|${it.sourceId}|${it.providerId}", ContentKind.SERIES, it.title, it.genres.split('|').filter(String::isNotBlank), it.category, it.rating, it.addedAt) }
        val byKey = all.associateBy { it.key }
        val seen = history.map { if (it.kind == "EPISODE" && it.seriesId != null) "SERIES|${it.sourceId}|${it.seriesId}" else "${it.kind}|${it.sourceId}|${it.itemId}" }
        val seeds = seen.distinct().mapNotNull { byKey[it] }.take(rows)
        val used = HashSet(seen)
        return seeds.mapNotNull { seed ->
            val g = seed.genres.map { it.lowercase().trim() }.toSet()
            val similar = all.asSequence().filter { it.key !in used && it.kind == seed.kind }
                .map { c -> c to (if (g.isNotEmpty()) c.genres.count { it.lowercase().trim() in g } * 2 else 0) + (if (c.category == seed.category) 1 else 0) }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<RecItem, Int>> { it.second }.thenByDescending { it.first.rating ?: 0.0 }.thenByDescending { it.first.addedAtEpochSeconds ?: 0 })
                .take(perRow).map { it.first }.toList()
            similar.forEach { used += it.key }
            val cards = cardsFor(similar)
            if (cards.size >= 4) seed.title to cards else null
        }
    }

    suspend fun cardsFor(recs: List<RecItem>): List<MediaCard> = recs.mapNotNull { r ->
        val (kind, src, id) = r.key.split('|', limit = 3)
        val h = hidden.first()
        if (kind == "MOVIE") db.movies().get(src, id)?.takeIf { it.category !in h }?.toCard() else db.series().get(src, id)?.takeIf { it.category !in h }?.toCard()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(private val db: EmbyDatabase, private val prefs: PreferencesRepository) {
    val profiles: Flow<List<ProfileEntity>> = db.profiles().observeAll()

    /** Garantiza que exista al menos un perfil y que el activo sea válido. */
    suspend fun ensureProfile(defaultName: String) {
        val all = db.profiles().all()
        if (all.isEmpty()) {
            val id = db.profiles().upsert(ProfileEntity(name = defaultName, colorArgb = 0xFF7BE52F.toInt(), createdAt = System.currentTimeMillis()))
            prefs.setActiveProfile(id)
        } else if (all.none { it.id == prefs.activeProfile() }) prefs.setActiveProfile(all.first().id)
    }

    suspend fun createProfile(name: String, color: Int): Long =
        db.profiles().upsert(ProfileEntity(name = name.trim().take(30), colorArgb = color, createdAt = System.currentTimeMillis()))

    suspend fun renameProfile(p: ProfileEntity, name: String) { db.profiles().upsert(p.copy(name = name.trim().take(30))) }

    suspend fun deleteProfile(id: Long) {
        if (db.profiles().all().size <= 1) return
        db.library().clearHistory(id); db.library().clearFavorites(id)
        db.profiles().delete(id)
        prefs.deleteProfile(id)
        if (prefs.activeProfile() == id) prefs.setActiveProfile(db.profiles().all().first().id)
    }

    fun favorites(): Flow<List<FavoriteEntity>> = prefs.activeProfileId.flatMapLatest { db.library().observeFavorites(it) }
    fun continueWatching(limit: Int = 20) = prefs.activeProfileId.flatMapLatest { db.library().observeContinueWatching(it, limit) }

    /**
     * «Continuar viendo» inteligente: lo que quedó a medias (sin lo ya terminado) y, en series cuyo
     * último episodio se terminó, el SIGUIENTE episodio. Una sola tarjeta por serie.
     */
    fun continueWatchingSmart(limit: Int = 30): Flow<List<MediaCard>> = prefs.activeProfileId.flatMapLatest { db.library().observeHistory(it, 120) }
        .map { hist ->
            val out = ArrayList<MediaCard>(); val seenSeries = HashSet<String>()
            for (p in hist) {
                if (out.size >= limit) break
                when (p.kind) {
                    "MOVIE" -> if (!p.completed && p.positionMs > 15_000 && p.fraction < 0.95f) out += p.toCard()
                    "EPISODE" -> {
                        val sid = p.seriesId ?: continue
                        if (!seenSeries.add("${p.sourceId}|$sid")) continue
                        if (!p.completed && p.positionMs > 15_000 && p.fraction < 0.95f) { out += p.toCard(); continue }
                        val eps = db.episodes().forSeries(p.sourceId, sid)
                        val i = eps.indexOfFirst { it.providerId == p.itemId }
                        val next = eps.getOrNull(if (i >= 0) i + 1 else -1) ?: continue
                        val title = db.series().get(p.sourceId, sid)?.title ?: p.title
                        out += MediaCard(ItemRef(ContentKind.EPISODE, p.sourceId, next.providerId), title, next.thumbnailUrl ?: p.imageUrl,
                            subtitle = "▶ T${next.season} · E${next.episode}", seriesId = sid)
                    }
                }
            }
            out
        }

    /** Mueve un favorito una posición (se reordena intercambiando su fecha, sin tocar la base de datos). */
    suspend fun moveFavorite(kind: String, sourceId: String, itemId: String, delta: Int) {
        val p = prefs.activeProfile()
        val list = db.library().favorites(p).sortedByDescending { it.addedAt }
        val i = list.indexOfFirst { it.kind == kind && it.sourceId == sourceId && it.itemId == itemId }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val a = list[i]; val b = list[j]
        // Fechas distintas aunque se hayan guardado en el mismo milisegundo.
        val ta = if (a.addedAt == b.addedAt) b.addedAt + (if (delta < 0) 1 else -1) else b.addedAt
        db.library().addFavorite(a.copy(addedAt = ta)); db.library().addFavorite(b.copy(addedAt = a.addedAt))
    }
    fun history(limit: Int = 50) = prefs.activeProfileId.flatMapLatest { db.library().observeHistory(it, limit) }
    fun isFavorite(ref: ItemRef) = prefs.activeProfileId.flatMapLatest { db.library().observeIsFavorite(it, ref.kind.name, ref.sourceId, ref.id) }
    fun episodeProgress(sourceId: String, seriesId: String) = prefs.activeProfileId.flatMapLatest { db.library().observeEpisodeProgress(it, sourceId, seriesId) }

    suspend fun historySnapshot(limit: Int = 100) = db.library().history(prefs.activeProfile(), limit)
    suspend fun favoritesSnapshot() = db.library().favorites(prefs.activeProfile())

    suspend fun toggleFavorite(ref: ItemRef, title: String, imageUrl: String?) {
        val p = prefs.activeProfile()
        val lib = db.library()
        if (lib.observeIsFavorite(p, ref.kind.name, ref.sourceId, ref.id).first()) lib.removeFavorite(p, ref.kind.name, ref.sourceId, ref.id)
        else lib.addFavorite(FavoriteEntity(p, ref.kind.name, ref.sourceId, ref.id, title, imageUrl, System.currentTimeMillis()))
    }

    suspend fun progress(ref: ItemRef) = db.library().progress(prefs.activeProfile(), ref.kind.name, ref.sourceId, ref.id)

    suspend fun saveProgress(
        ref: ItemRef, title: String, imageUrl: String?, positionMs: Long, durationMs: Long,
        seriesId: String? = null, season: Int? = null, episode: Int? = null,
    ) {
        val completed = durationMs > 0 && positionMs >= durationMs * 0.95
        db.library().saveProgress(
            ProgressEntity(prefs.activeProfile(), ref.kind.name, ref.sourceId, ref.id, title, imageUrl,
                positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), completed, System.currentTimeMillis(), seriesId, season, episode)
        )
    }

    suspend fun clearHistory() = db.library().clearHistory(prefs.activeProfile())
}

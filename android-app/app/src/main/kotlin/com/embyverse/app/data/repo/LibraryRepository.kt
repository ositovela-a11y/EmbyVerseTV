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

/** Referencia ligera a un elemento reproducible o navegable. */
data class ItemRef(val kind: ContentKind, val sourceId: String, val id: String)

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) { val isEmpty get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty() }

class CatalogRepository(private val db: EmbyDatabase) {
    fun channels(category: String?, limit: Int = 5000) = db.channels().observe(category, limit)
    fun movies(category: String?, limit: Int = 5000) = db.movies().observe(category, limit)
    fun series(category: String?, limit: Int = 5000) = db.series().observe(category, limit)
    fun recentMovies(limit: Int) = db.movies().observeRecent(limit)
    fun recentSeries(limit: Int) = db.series().observeRecent(limit)
    fun categories(kind: ContentKind) = db.categories().observeNames(kind.name)
    fun movie(sourceId: String, id: String) = db.movies().observeOne(sourceId, id)
    fun seriesOne(sourceId: String, id: String) = db.series().observeOne(sourceId, id)
    fun episodes(sourceId: String, seriesId: String) = db.episodes().observeForSeries(sourceId, seriesId)
    val channelCount = db.channels().observeCount()

    suspend fun search(query: String, limit: Int = 60): SearchResults {
        val q = M3uCatalogBuilder.normalize(query)
        if (q.length < 2) return SearchResults()
        return SearchResults(db.channels().search(q, limit), db.movies().search(q, limit), db.series().search(q, limit))
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

    suspend fun cardsFor(recs: List<RecItem>): List<MediaCard> = recs.mapNotNull { r ->
        val (kind, src, id) = r.key.split('|', limit = 3)
        if (kind == "MOVIE") db.movies().get(src, id)?.toCard() else db.series().get(src, id)?.toCard()
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

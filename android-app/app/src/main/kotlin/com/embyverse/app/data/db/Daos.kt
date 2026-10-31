package com.embyverse.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class IdFingerprint(val providerId: String, val fingerprint: Int)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY sortOrder, createdAt")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY sortOrder, createdAt")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: String): SourceEntity?

    @Upsert suspend fun upsert(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE sources SET lastError = :error WHERE id = :id")
    suspend fun setError(id: String, error: String?)

    @Query("UPDATE sources SET lastSyncAt = :at, lastError = NULL, liveCount = :live, movieCount = :movies, seriesCount = :series, detectedEpgUrl = :epg WHERE id = :id")
    suspend fun setSynced(id: String, at: Long, live: Int, movies: Int, series: Int, epg: String?)

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int
}

@Dao
interface ChannelDao {
    @Query("SELECT providerId, fingerprint FROM channels WHERE sourceId = :sourceId")
    suspend fun fingerprints(sourceId: String): List<IdFingerprint>

    @Upsert suspend fun upsert(items: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId AND providerId IN (:ids)")
    suspend fun delete(sourceId: String, ids: List<String>)

    @Query("UPDATE channels SET position = :position WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun setPosition(sourceId: String, id: String, position: Int)

    @Query("SELECT * FROM channels WHERE (:category IS NULL OR category = :category) ORDER BY sourceId, position LIMIT :limit")
    fun observe(category: String?, limit: Int): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE searchKey LIKE '%' || :q || '%' ORDER BY position LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun get(sourceId: String, id: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE (:category IS NULL OR category = :category) ORDER BY sourceId, position LIMIT 2000")
    suspend fun listForZapping(category: String?): List<ChannelEntity>

    /** Posibles canales de eventos (partidos con hora en el nombre, carpetas de eventos/PPV/deportes). */
    @Query("SELECT * FROM channels WHERE name LIKE '%:%' OR name LIKE '% vs %' OR name LIKE '% vs. %' OR name LIKE '% x %' OR category LIKE '%event%' OR category LIKE '%ppv%' OR category LIKE '%deport%' OR category LIKE '%sport%' OR category LIKE '%futbol%' OR category LIKE '%fútbol%' LIMIT 4000")
    fun observeEventCandidates(): Flow<List<ChannelEntity>>

    @Query("SELECT COUNT(*) FROM channels")
    fun observeCount(): Flow<Int>

    @Query("SELECT DISTINCT epgChannelId FROM channels WHERE sourceId = :sourceId AND epgChannelId IS NOT NULL")
    suspend fun epgIds(sourceId: String): List<String>

    /** Datos mínimos para emparejar la guía por nombre de canal. */
    @Query("SELECT providerId, name, epgChannelId FROM channels WHERE sourceId = :sourceId")
    suspend fun epgCandidates(sourceId: String): List<EpgCandidate>

    /** Canales sin id de guía: se les asigna el de la guía emparejada por nombre. */
    @Query("UPDATE channels SET epgChannelId = :epgId WHERE sourceId = :sourceId AND providerId IN (:ids) AND epgChannelId IS NULL")
    suspend fun assignEpgId(sourceId: String, ids: List<String>, epgId: String)
}

@Dao
interface MovieDao {
    @Query("SELECT providerId, fingerprint FROM movies WHERE sourceId = :sourceId")
    suspend fun fingerprints(sourceId: String): List<IdFingerprint>

    @Upsert suspend fun upsert(items: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE sourceId = :sourceId AND providerId IN (:ids)")
    suspend fun delete(sourceId: String, ids: List<String>)

    @Query("UPDATE movies SET position = :position WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun setPosition(sourceId: String, id: String, position: Int)

    @Query("SELECT * FROM movies WHERE (:category IS NULL OR category = :category) ORDER BY sourceId, position LIMIT :limit")
    fun observe(category: String?, limit: Int): Flow<List<MovieEntity>>

    /** Solo lo que necesita la cuadrícula (sin sinopsis ni URLs): carga y desplazamiento más rápidos. */
    @Query("SELECT sourceId, providerId, title, searchKey, posterUrl, backdropUrl, year, rating, category FROM movies WHERE (:category IS NULL OR category = :category) ORDER BY sourceId, position LIMIT :limit")
    fun observeCards(category: String?, limit: Int): Flow<List<CardRow>>

    @Query("SELECT * FROM movies ORDER BY addedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MovieEntity>>

    @Query("SELECT * FROM movies WHERE searchKey LIKE '%' || :q || '%' ORDER BY position LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun get(sourceId: String, id: String): MovieEntity?

    @Query("SELECT * FROM movies WHERE sourceId = :sourceId AND providerId = :id")
    fun observeOne(sourceId: String, id: String): Flow<MovieEntity?>

    @Query("SELECT * FROM movies ORDER BY rating IS NULL, rating DESC LIMIT :limit")
    suspend fun candidates(limit: Int): List<MovieEntity>
}

@Dao
interface SeriesDao {
    @Query("SELECT providerId, fingerprint FROM series WHERE sourceId = :sourceId")
    suspend fun fingerprints(sourceId: String): List<IdFingerprint>

    @Upsert suspend fun upsert(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE sourceId = :sourceId AND providerId IN (:ids)")
    suspend fun delete(sourceId: String, ids: List<String>)

    @Query("UPDATE series SET position = :position WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun setPosition(sourceId: String, id: String, position: Int)

    @Query("SELECT * FROM series WHERE (:category IS NULL OR category = :category) ORDER BY sourceId, position LIMIT :limit")
    fun observe(category: String?, limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT sourceId, providerId, title, searchKey, posterUrl, backdropUrl, year, rating, category FROM series WHERE (:category IS NULL OR category = :category) ORDER BY sourceId, position LIMIT :limit")
    fun observeCards(category: String?, limit: Int): Flow<List<CardRow>>

    @Query("SELECT * FROM series ORDER BY addedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE searchKey LIKE '%' || :q || '%' ORDER BY position LIMIT :limit")
    suspend fun search(q: String, limit: Int): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun get(sourceId: String, id: String): SeriesEntity?

    @Query("SELECT * FROM series WHERE sourceId = :sourceId AND providerId = :id")
    fun observeOne(sourceId: String, id: String): Flow<SeriesEntity?>

    @Query("UPDATE series SET episodesLoaded = :loaded WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun setEpisodesLoaded(sourceId: String, id: String, loaded: Boolean)

    @Query("SELECT * FROM series ORDER BY rating IS NULL, rating DESC LIMIT :limit")
    suspend fun candidates(limit: Int): List<SeriesEntity>
}

@Dao
interface EpisodeDao {
    @Upsert suspend fun upsert(items: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId AND seriesProviderId = :seriesId")
    suspend fun deleteForSeries(sourceId: String, seriesId: String)

    @Query("DELETE FROM episodes WHERE sourceId = :sourceId AND seriesProviderId NOT IN (SELECT providerId FROM series WHERE sourceId = :sourceId)")
    suspend fun deleteOrphans(sourceId: String)

    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesProviderId = :seriesId ORDER BY season, episode")
    fun observeForSeries(sourceId: String, seriesId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND seriesProviderId = :seriesId ORDER BY season, episode")
    suspend fun forSeries(sourceId: String, seriesId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE sourceId = :sourceId AND providerId = :id")
    suspend fun get(sourceId: String, id: String): EpisodeEntity?
}

@Dao
interface CategoryDao {
    @Query("DELETE FROM categories WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("DELETE FROM categories WHERE sourceId = :sourceId AND kind = :kind")
    suspend fun deleteForSourceKind(sourceId: String, kind: String)

    @Upsert suspend fun upsert(items: List<CategoryEntity>)

    @Query("SELECT DISTINCT name FROM categories WHERE kind = :kind ORDER BY sourceId, position")
    fun observeNames(kind: String): Flow<List<String>>

    @Query("SELECT DISTINCT name FROM categories")
    fun observeAllNames(): Flow<List<String>>
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY createdAt")
    suspend fun all(): List<ProfileEntity>

    @Upsert suspend fun upsert(p: ProfileEntity): Long

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favorites WHERE profileId = :profileId ORDER BY addedAt DESC")
    fun observeFavorites(profileId: Long): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE profileId = :profileId")
    suspend fun favorites(profileId: Long): List<FavoriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE profileId = :profileId AND kind = :kind AND sourceId = :sourceId AND itemId = :itemId)")
    fun observeIsFavorite(profileId: Long, kind: String, sourceId: String, itemId: String): Flow<Boolean>

    @Upsert suspend fun addFavorite(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE profileId = :profileId AND kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun removeFavorite(profileId: Long, kind: String, sourceId: String, itemId: String)

    @Upsert suspend fun saveProgress(p: ProgressEntity)

    @Query("SELECT * FROM progress WHERE profileId = :profileId AND kind = :kind AND sourceId = :sourceId AND itemId = :itemId")
    suspend fun progress(profileId: Long, kind: String, sourceId: String, itemId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE profileId = :profileId AND kind IN ('MOVIE','EPISODE') AND completed = 0 AND positionMs > 15000 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeContinueWatching(profileId: Long, limit: Int): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE profileId = :profileId ORDER BY updatedAt DESC LIMIT :limit")
    fun observeHistory(profileId: Long, limit: Int): Flow<List<ProgressEntity>>

    @Query("SELECT * FROM progress WHERE profileId = :profileId ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun history(profileId: Long, limit: Int): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE profileId = :profileId AND kind = 'EPISODE' AND sourceId = :sourceId AND seriesId = :seriesId")
    fun observeEpisodeProgress(profileId: Long, sourceId: String, seriesId: String): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE profileId = :profileId")
    suspend fun clearHistory(profileId: Long)

    @Query("DELETE FROM favorites WHERE profileId = :profileId")
    suspend fun clearFavorites(profileId: Long)

    @Query("DELETE FROM favorites WHERE sourceId = :sourceId")
    suspend fun deleteFavoritesForSource(sourceId: String)

    @Query("DELETE FROM progress WHERE sourceId = :sourceId")
    suspend fun deleteProgressForSource(sourceId: String)
}

@Dao
interface EpgDao {
    @Query("DELETE FROM epg WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Upsert suspend fun upsert(items: List<EpgEntity>)

    @Query("SELECT * FROM epg WHERE sourceId = :sourceId AND channelId = :channelId AND `end` > :now ORDER BY start LIMIT :limit")
    fun observeUpcoming(sourceId: String, channelId: String, now: Long, limit: Int): Flow<List<EpgEntity>>

    @Query("SELECT * FROM epg WHERE sourceId = :sourceId AND channelId IN (:channelIds) AND start <= :now AND `end` > :now")
    suspend fun nowPlaying(sourceId: String, channelIds: List<String>, now: Long): List<EpgEntity>

    /** Programación de varios canales en una ventana de tiempo (guía en cuadrícula). */
    @Query("SELECT * FROM epg WHERE sourceId = :sourceId AND channelId IN (:channelIds) AND `end` > :from AND start < :to ORDER BY start")
    suspend fun range(sourceId: String, channelIds: List<String>, from: Long, to: Long): List<EpgEntity>

    @Query("DELETE FROM epg WHERE `end` < :before")
    suspend fun prune(before: Long)
}

/** Fila ligera para cuadrículas de películas y series. */
data class CardRow(
    val sourceId: String,
    val providerId: String,
    val title: String,
    val searchKey: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val rating: Double?,
    val category: String? = null,
)

data class EpgCandidate(val providerId: String, val name: String, val epgChannelId: String?)

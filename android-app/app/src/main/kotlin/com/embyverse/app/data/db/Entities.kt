package com.embyverse.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Fuente guardada. La contraseña NO se guarda aquí (ver CredentialStore). */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String, // SourceType.name
    val location: String,
    val username: String?,
    val userAgent: String?,
    val epgUrl: String?,
    val detectedEpgUrl: String? = null,
    val createdAt: Long,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "channels",
    primaryKeys = ["sourceId", "providerId"],
    indices = [Index("sourceId", "category"), Index("searchKey"), Index("epgChannelId"), Index("sourceId", "position")],
)
data class ChannelEntity(
    val sourceId: String,
    val providerId: String,
    val name: String,
    val searchKey: String,
    /** Para fuentes Xtream la URL lleva los marcadores {u}/{p} en lugar de las credenciales. */
    val streamUrl: String,
    val logoUrl: String?,
    val category: String?,
    val epgChannelId: String?,
    val number: Int?,
    val userAgent: String?,
    val referrer: String?,
    val catchupDays: Int,
    val position: Int,
    val fingerprint: Int,
)

@Entity(
    tableName = "movies",
    primaryKeys = ["sourceId", "providerId"],
    indices = [Index("sourceId", "category"), Index("searchKey"), Index("addedAt"), Index("sourceId", "position")],
)
data class MovieEntity(
    val sourceId: String,
    val providerId: String,
    val title: String,
    val searchKey: String,
    val streamUrl: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val category: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    /** Géneros separados por "|" */
    val genres: String,
    val durationSeconds: Long?,
    val addedAt: Long?,
    val containerExtension: String?,
    val userAgent: String?,
    val referrer: String?,
    val position: Int,
    val fingerprint: Int,
    val detailsLoaded: Boolean = false,
)

@Entity(
    tableName = "series",
    primaryKeys = ["sourceId", "providerId"],
    indices = [Index("sourceId", "category"), Index("searchKey"), Index("addedAt"), Index("sourceId", "position")],
)
data class SeriesEntity(
    val sourceId: String,
    val providerId: String,
    val title: String,
    val searchKey: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val category: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    val genres: String,
    val addedAt: Long?,
    val position: Int,
    val fingerprint: Int,
    val episodesLoaded: Boolean,
)

@Entity(
    tableName = "episodes",
    primaryKeys = ["sourceId", "providerId"],
    indices = [Index("sourceId", "seriesProviderId", "season", "episode")],
)
data class EpisodeEntity(
    val sourceId: String,
    val providerId: String,
    val seriesProviderId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String,
    val plot: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val userAgent: String?,
    val referrer: String?,
)

@Entity(tableName = "categories", primaryKeys = ["sourceId", "kind", "name"])
data class CategoryEntity(
    val sourceId: String,
    val kind: String,
    val name: String,
    val position: Int,
)

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Int,
    val createdAt: Long,
)

@Entity(tableName = "favorites", primaryKeys = ["profileId", "kind", "sourceId", "itemId"], indices = [Index("profileId", "addedAt")])
data class FavoriteEntity(
    val profileId: Long,
    val kind: String,
    val sourceId: String,
    val itemId: String,
    val title: String,
    val imageUrl: String?,
    val addedAt: Long,
)

/** Progreso e historial. Para TV en vivo sólo se registra el último acceso (positionMs = 0). */
@Entity(tableName = "progress", primaryKeys = ["profileId", "kind", "sourceId", "itemId"], indices = [Index("profileId", "updatedAt")])
data class ProgressEntity(
    val profileId: Long,
    val kind: String,
    val sourceId: String,
    val itemId: String,
    val title: String,
    val imageUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "NULL") val seriesId: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

@Entity(tableName = "epg", primaryKeys = ["sourceId", "channelId", "start"], indices = [Index("sourceId", "channelId", "end")])
data class EpgEntity(
    val sourceId: String,
    val channelId: String,
    val start: Long,
    val end: Long,
    val title: String,
    val description: String?,
)

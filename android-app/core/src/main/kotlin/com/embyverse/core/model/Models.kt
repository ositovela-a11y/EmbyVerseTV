package com.embyverse.core.model

/** Tipo de fuente. Cada tipo tiene su propio adaptador; no se asume compatibilidad cruzada. */
enum class SourceType { M3U_URL, M3U_FILE, XTREAM }

enum class ContentKind { LIVE, MOVIE, SERIES, EPISODE }

/**
 * Definición de una fuente tal como la guarda el usuario. La contraseña NO vive aquí:
 * se guarda cifrada con Android Keystore y se entrega al adaptador sólo en memoria.
 */
data class SourceSpec(
    val id: String,
    val name: String,
    val type: SourceType,
    /** URL de la lista (M3U_URL), URI del archivo (M3U_FILE) o URL base del servidor (XTREAM). */
    val location: String,
    val username: String? = null,
    val userAgent: String? = null,
    val epgUrl: String? = null,
)

data class Category(
    val sourceId: String,
    val kind: ContentKind,
    val providerId: String,
    val name: String,
)

/** Cabeceras específicas por stream declaradas en M3U (#EXTVLCOPT). */
data class StreamHeaders(val userAgent: String? = null, val referrer: String? = null) {
    val isEmpty get() = userAgent == null && referrer == null
}

data class LiveChannel(
    val sourceId: String,
    val providerId: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String? = null,
    val categoryName: String? = null,
    val epgChannelId: String? = null,
    val number: Int? = null,
    val headers: StreamHeaders = StreamHeaders(),
    val catchupDays: Int = 0,
)

data class Movie(
    val sourceId: String,
    val providerId: String,
    val title: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryName: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val durationSeconds: Long? = null,
    val addedAtEpochSeconds: Long? = null,
    val containerExtension: String? = null,
    val headers: StreamHeaders = StreamHeaders(),
)

data class Episode(
    val sourceId: String,
    val providerId: String,
    val seriesProviderId: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val streamUrl: String,
    val plot: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Long? = null,
    val headers: StreamHeaders = StreamHeaders(),
)

data class Series(
    val sourceId: String,
    val providerId: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val categoryName: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val addedAtEpochSeconds: Long? = null,
    /** Vacío si el proveedor entrega los episodios bajo demanda (Xtream get_series_info). */
    val episodes: List<Episode> = emptyList(),
) {
    val seasons: Map<Int, List<Episode>> get() = episodes.groupBy { it.season }.toSortedMap()
        .mapValues { (_, eps) -> eps.sortedBy { it.episode } }
}

/** Resultado completo de sincronizar una fuente. */
data class Catalog(
    val sourceId: String,
    val live: List<LiveChannel> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val series: List<Series> = emptyList(),
    val categories: List<Category> = emptyList(),
    val epgUrl: String? = null,
    val warnings: List<String> = emptyList(),
) {
    val isEmpty get() = live.isEmpty() && movies.isEmpty() && series.isEmpty()
}

data class EpgProgram(
    val channelId: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val title: String,
    val description: String? = null,
)

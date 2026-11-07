package com.embyverse.app.data.repo

import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.db.FavoriteEntity
import com.embyverse.app.data.db.MovieEntity
import com.embyverse.app.data.db.ProgressEntity
import com.embyverse.app.data.db.SeriesEntity
import com.embyverse.core.model.ContentKind

/** Modelo de presentación común para todas las tarjetas de contenido. */
data class MediaCard(
    val ref: ItemRef,
    val title: String,
    val imageUrl: String?,
    val subtitle: String? = null,
    /** 0..1 para la barra de progreso de "Continuar viendo". */
    val progress: Float? = null,
    val backdropUrl: String? = null,
    /** Para episodios: serie a la que pertenecen (para abrir su ficha). */
    val seriesId: String? = null,
    /** Etiquetas sobre la carátula: «NUEVO», «ESTRENO», «4K», «HD». */
    val badges: List<String> = emptyList(),
)

private val RX_4K = Regex("""\b(4K|UHD|2160P?)\b""")
private val RX_FHD = Regex("""\b(FHD|FULL ?HD|1080P?)\b""")
private val RX_HD = Regex("""\b(HD|720P?)\b""")
private val THIS_YEAR = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

/**
 * Límite de «NUEVO» por tipo: solo los ~120 títulos agregados más recientemente (y de los últimos 14 días).
 * Así, si el proveedor carga todo el catálogo el mismo día, no aparece «NUEVO» en todas las carátulas.
 */
object NewBadge {
    @Volatile var moviesSince = 0L
    @Volatile var seriesSince = 0L
    suspend fun refresh(db: com.embyverse.app.data.db.EmbyDatabase) = runCatching {
        moviesSince = db.movies().addedAtRank(120) ?: 0L
        seriesSince = db.series().addedAtRank(60) ?: 0L
    }
}

/** Etiquetas automáticas: calidad por el nombre, «NUEVO» si se agregó hace menos de 14 días, «ESTRENO» si es de este año. */
fun badgesFor(title: String, year: Int? = null, addedAtSec: Long? = null, newSince: Long = 0L): List<String> {
    val out = ArrayList<String>(2)
    val nowSec = System.currentTimeMillis() / 1000
    if (addedAtSec != null && addedAtSec > newSince && nowSec - addedAtSec in 0..14 * 86_400L) out += "NUEVO"
    else if (year != null && year >= THIS_YEAR) out += "ESTRENO"
    val up = title.uppercase()
    when {
        RX_4K.containsMatchIn(up) -> out += "4K"
        RX_FHD.containsMatchIn(up) -> out += "FHD"
        RX_HD.containsMatchIn(up) -> out += "HD"
    }
    return out
}

fun ChannelEntity.toCard() = MediaCard(ItemRef(ContentKind.LIVE, sourceId, providerId), name, logoUrl, subtitle = category, badges = badgesFor(name).filter { it != "NUEVO" && it != "ESTRENO" })

fun MovieEntity.toCard() = MediaCard(
    ItemRef(ContentKind.MOVIE, sourceId, providerId), title, posterUrl,
    subtitle = listOfNotNull(year?.toString(), rating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null },
    backdropUrl = backdropUrl, badges = badgesFor(title, year, addedAt, NewBadge.moviesSince),
)

fun SeriesEntity.toCard() = MediaCard(
    ItemRef(ContentKind.SERIES, sourceId, providerId), title, posterUrl,
    subtitle = listOfNotNull(year?.toString(), rating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null },
    backdropUrl = backdropUrl, badges = badgesFor(title, year, addedAt, NewBadge.seriesSince),
)

fun ProgressEntity.toCard() = MediaCard(
    ItemRef(ContentKind.valueOf(kind), sourceId, itemId), title, imageUrl,
    subtitle = if (season != null && episode != null) "T$season · E$episode" else null,
    progress = fraction.takeIf { durationMs > 0 }, seriesId = seriesId,
)

fun FavoriteEntity.toCard() = MediaCard(ItemRef(ContentKind.valueOf(kind), sourceId, itemId), title, imageUrl)

fun com.embyverse.app.data.db.CardRow.toCard(kind: ContentKind) = MediaCard(
    ItemRef(kind, sourceId, providerId), title, posterUrl,
    subtitle = listOfNotNull(year?.toString(), rating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null },
    backdropUrl = backdropUrl, badges = badgesFor(title, year, addedAt, if (kind == ContentKind.SERIES) NewBadge.seriesSince else NewBadge.moviesSince),
)

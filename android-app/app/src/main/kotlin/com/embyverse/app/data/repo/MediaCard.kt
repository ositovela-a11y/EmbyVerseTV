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
)

fun ChannelEntity.toCard() = MediaCard(ItemRef(ContentKind.LIVE, sourceId, providerId), name, logoUrl, subtitle = category)

fun MovieEntity.toCard() = MediaCard(
    ItemRef(ContentKind.MOVIE, sourceId, providerId), title, posterUrl,
    subtitle = listOfNotNull(year?.toString(), rating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null },
    backdropUrl = backdropUrl,
)

fun SeriesEntity.toCard() = MediaCard(
    ItemRef(ContentKind.SERIES, sourceId, providerId), title, posterUrl,
    subtitle = listOfNotNull(year?.toString(), rating?.let { "★ %.1f".format(it) }).joinToString(" · ").ifEmpty { null },
    backdropUrl = backdropUrl,
)

fun ProgressEntity.toCard() = MediaCard(
    ItemRef(ContentKind.valueOf(kind), sourceId, itemId), title, imageUrl,
    subtitle = if (season != null && episode != null) "T$season · E$episode" else null,
    progress = fraction.takeIf { durationMs > 0 }, seriesId = seriesId,
)

fun FavoriteEntity.toCard() = MediaCard(ItemRef(ContentKind.valueOf(kind), sourceId, itemId), title, imageUrl)

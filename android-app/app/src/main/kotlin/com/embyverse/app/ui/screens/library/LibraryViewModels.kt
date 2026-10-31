package com.embyverse.app.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.db.EpgEntity
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.data.repo.MediaCard
import com.embyverse.app.data.repo.toCard
import com.embyverse.app.di.AppContainer
import com.embyverse.core.m3u.M3uCatalogBuilder
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LiveFilter { ALL, FAVORITES, HISTORY }

/** Carpetas especiales: eventos de hoy (TV en vivo) y lo más nuevo (películas y series). */
const val CATEGORY_EVENTS = "\u0000events"
const val CATEGORY_NEW = "\u0000new"

data class ChannelRow(val channel: ChannelEntity, val nowPlaying: EpgEntity?, val favorite: Boolean)

data class LiveUiState(
    val categories: List<String> = emptyList(),
    val category: String? = null,
    val filter: LiveFilter = LiveFilter.ALL,
    val query: String = "",
    val rows: List<ChannelRow> = emptyList(),
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(private val c: AppContainer, initialCategory: String?) : ViewModel() {
    private val category = MutableStateFlow(initialCategory)
    private val filter = MutableStateFlow(LiveFilter.ALL)
    private val query = MutableStateFlow("")
    val selected = MutableStateFlow<ChannelEntity?>(null)
    /** La guía se consulta cuando el foco se detiene, no en cada canal por el que pasa el control. */
    val selectedSettled = selected.debounce(220).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val channels = category.flatMapLatest { cat ->
        if (cat == CATEGORY_EVENTS) c.catalog.eventCandidates().map { l -> com.embyverse.app.data.repo.Events.today(l, limit = 300).map { it.channel } }
        else c.catalog.channels(cat, 5000)
    }
    private val favKeys = c.library.favorites().map { l -> l.filter { it.kind == "LIVE" }.map { it.sourceId to it.itemId }.toSet() }
    private val histKeys = c.library.history(200).map { l -> l.filter { it.kind == "LIVE" }.map { it.sourceId to it.itemId } }

    val state: StateFlow<LiveUiState> = combine(
        c.catalog.categories(ContentKind.LIVE), channels, combine(filter, query.debounce(200)) { f, q -> f to q }, favKeys, histKeys,
    ) { cats, list, (f, q), favs, hist ->
        val nq = M3uCatalogBuilder.normalize(q)
        var filtered = if (nq.isEmpty()) list else list.filter { it.searchKey.contains(nq) }
        filtered = when (f) {
            LiveFilter.ALL -> filtered
            LiveFilter.FAVORITES -> filtered.filter { (it.sourceId to it.providerId) in favs }
            LiveFilter.HISTORY -> { val order = hist.withIndex().associate { it.value to it.index }; filtered.filter { (it.sourceId to it.providerId) in order }.sortedBy { order[it.sourceId to it.providerId] } }
        }
        Triple(cats, filtered, favs) to (f to q)
    }.mapLatest { (data, fq) ->
        val (cats, list, favs) = data
        // EPG "ahora" para los primeros canales visibles (consulta acotada).
        val bySource = list.take(300).filter { it.epgChannelId != null }.groupBy { it.sourceId }
        val now = HashMap<Pair<String, String>, EpgEntity>()
        bySource.forEach { (sid, chs) -> c.epg.nowPlaying(sid, chs.mapNotNull { it.epgChannelId }).forEach { now[sid to it.channelId] = it } }
        LiveUiState(cats, category.value, fq.first, fq.second,
            list.map { ChannelRow(it, it.epgChannelId?.let { id -> now[it.sourceId to id] }, (it.sourceId to it.providerId) in favs) }, false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LiveUiState())

    fun upcoming(ch: ChannelEntity) = ch.epgChannelId?.let { c.epg.upcoming(ch.sourceId, it) } ?: flowOf(emptyList())
    fun setCategory(cat: String?) { category.value = cat }
    fun setFilter(f: LiveFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun toggleFavorite(ch: ChannelEntity) = viewModelScope.launch { c.library.toggleFavorite(ItemRef(ContentKind.LIVE, ch.sourceId, ch.providerId), ch.name, ch.logoUrl) }
}

/** Cabecera destacada (TV) con la información del título enfocado. */
data class Spotlight(val ref: ItemRef, val title: String, val meta: String, val plot: String?, val backdrop: String?, val poster: String?)

data class VodUiState(
    val categories: List<String> = emptyList(),
    val category: String? = null,
    val query: String = "",
    val cards: List<MediaCard> = emptyList(),
    val loading: Boolean = true,
)

/** Catálogo de películas o de series (misma pantalla, distinto origen de datos). */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VodViewModel(private val c: AppContainer, private val kind: ContentKind) : ViewModel() {
    // Películas y series abren en «Lo más nuevo».
    private val category = MutableStateFlow<String?>(CATEGORY_NEW)
    private val query = MutableStateFlow("")

    private val items = category.flatMapLatest { cat ->
        if (cat == CATEGORY_NEW) return@flatMapLatest (if (kind == ContentKind.MOVIE) c.catalog.recentMovies(300).map { l -> l.map { it.searchKey to it.toCard() } }
            else c.catalog.recentSeries(300).map { l -> l.map { it.searchKey to it.toCard() } })
        (if (kind == ContentKind.MOVIE) c.database.movies().observeCards(cat, 5000) else c.database.series().observeCards(cat, 5000))
            .let { f -> kotlinx.coroutines.flow.combine(f, c.parental.hidden) { l, h -> if (h.isEmpty()) l else l.filter { it.category !in h } } }
            .map { l -> l.map { it.searchKey to it.toCard(kind) } }
    }

    val state: StateFlow<VodUiState> = combine(c.catalog.categories(kind), category, query.debounce(200), items) { cats, cat, q, list ->
        val nq = M3uCatalogBuilder.normalize(q)
        VodUiState(cats, cat, q, list.filter { nq.isEmpty() || it.first.contains(nq) }.map { it.second }, false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState())

    fun setCategory(cat: String?) { category.value = cat }
    fun setQuery(q: String) { query.value = q }

    /** Título enfocado con el control: su ficha se muestra arriba (se espera a que el foco se detenga). */
    val focused = MutableStateFlow<ItemRef?>(null)
    val spotlight: StateFlow<Spotlight?> = focused.debounce(140).flatMapLatest { r ->
        kotlinx.coroutines.flow.flow {
            if (r == null) { emit(null); return@flow }
            emit(load(r))
            // Las películas de Xtream no traen sinopsis ni fondo hasta pedir su ficha: se pide si el foco sigue ahí.
            if (r.kind == ContentKind.MOVIE && c.database.movies().get(r.sourceId, r.id)?.detailsLoaded == false) {
                kotlinx.coroutines.delay(600)
                c.sources.loadMovieDetails(r.sourceId, r.id)
                emit(load(r))
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun load(r: ItemRef): Spotlight? {
        fun meta(year: Int?, rating: Double?, genres: String, extra: String?) = listOfNotNull(
            year?.toString(), rating?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", if (it > 10) it / 10 else it) },
            genres.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(" · ").ifEmpty { null }, extra,
        ).joinToString("   ")
        return if (r.kind == ContentKind.MOVIE) c.database.movies().get(r.sourceId, r.id)?.let { m ->
            Spotlight(r, m.title, meta(m.year, m.rating, m.genres, m.durationSeconds?.takeIf { it > 0 }?.let { "${it / 3600}h ${(it % 3600) / 60}min" }), m.plot, m.backdropUrl, m.posterUrl)
        } else c.database.series().get(r.sourceId, r.id)?.let { m ->
            Spotlight(r, m.title, meta(m.year, m.rating, m.genres, null), m.plot, m.backdropUrl, m.posterUrl)
        }
    }
}

class FavoritesViewModel(c: AppContainer) : ViewModel() {
    val favorites: StateFlow<List<MediaCard>?> = c.library.favorites().map { l -> l.map { it.toCard() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<List<MediaCard>> = c.library.history(60).map { l -> l.map { it.toCard() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

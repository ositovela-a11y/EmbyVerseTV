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

    private val channels = category.flatMapLatest { c.catalog.channels(it, 5000) }
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
    private val category = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")

    private val items = category.flatMapLatest { cat ->
        if (kind == ContentKind.MOVIE) c.catalog.movies(cat, 5000).map { l -> l.map { it.searchKey to it.toCard() } }
        else c.catalog.series(cat, 5000).map { l -> l.map { it.searchKey to it.toCard() } }
    }

    val state: StateFlow<VodUiState> = combine(c.catalog.categories(kind), category, query.debounce(200), items) { cats, cat, q, list ->
        val nq = M3uCatalogBuilder.normalize(q)
        VodUiState(cats, cat, q, list.filter { nq.isEmpty() || it.first.contains(nq) }.map { it.second }, false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VodUiState())

    fun setCategory(cat: String?) { category.value = cat }
    fun setQuery(q: String) { query.value = q }
}

class FavoritesViewModel(c: AppContainer) : ViewModel() {
    val favorites: StateFlow<List<MediaCard>?> = c.library.favorites().map { l -> l.map { it.toCard() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val history: StateFlow<List<MediaCard>> = c.library.history(60).map { l -> l.map { it.toCard() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

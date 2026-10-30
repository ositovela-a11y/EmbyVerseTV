package com.embyverse.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.data.repo.MediaCard
import com.embyverse.app.data.repo.toCard
import com.embyverse.app.di.AppContainer
import com.embyverse.core.config.AppRoute
import com.embyverse.core.config.Banner
import com.embyverse.core.config.BannerPlacement
import com.embyverse.core.config.ConfigResolver
import com.embyverse.core.config.RemoteConfig
import com.embyverse.core.config.SectionSpec
import com.embyverse.core.config.SectionType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/** Elemento del banner principal: un banner publicado por el administrador o contenido destacado. */
data class HeroItem(
    val id: String,
    val title: String,
    val subtitle: String?,
    val imageUrl: String?,
    val route: AppRoute? = null,
    val item: ItemRef? = null,
)

data class HomeUiState(
    val config: RemoteConfig = RemoteConfig.DEFAULT,
    val sections: List<SectionSpec> = emptyList(),
    val content: Map<SectionType, List<MediaCard>> = emptyMap(),
    val hero: List<HeroItem> = emptyList(),
    val promos: List<Banner> = emptyList(),
    val profileName: String? = null,
    val hasSources: Boolean = true,
    val loading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val minuteTicker: Flow<Long> = flow { while (true) { emit(System.currentTimeMillis() / 1000); delay(60_000) } }
    private val appearance = c.prefs.activeProfileId.flatMapLatest { c.prefs.appearance(it) }

    private val profileName = combine(c.library.profiles, c.prefs.activeProfileId) { all, id -> all.firstOrNull { it.id == id }?.name }

    private val lists = combine(
        c.library.continueWatching(40).map { l -> l.map { it.toCard() } },
        c.library.favorites().map { l -> l.map { it.toCard() } },
        combine(c.library.history(60), c.catalog.channels(null, 60)) { hist, ch ->
            // Primero los canales vistos recientemente, después el resto en el orden de la lista.
            val recentIds = hist.filter { it.kind == "LIVE" }.map { it.sourceId to it.itemId }
            val byId = ch.associateBy { it.sourceId to it.providerId }
            (recentIds.mapNotNull { byId[it] } + ch).distinctBy { it.sourceId to it.providerId }.map { it.toCard() }
        },
        c.catalog.movies(null, 60).map { l -> l.map { it.toCard() } },
        c.catalog.series(null, 60).map { l -> l.map { it.toCard() } },
    ) { cont, fav, live, movies, series ->
        mapOf(SectionType.CONTINUE_WATCHING to cont, SectionType.FAVORITES to fav, SectionType.LIVE_TV to live,
            SectionType.MOVIES to movies, SectionType.SERIES to series)
    }

    private val recent = combine(c.catalog.recentMovies(40), c.catalog.recentSeries(40)) { m, s ->
        (m.map { (it.addedAt ?: 0) to it.toCard() } + s.map { (it.addedAt ?: 0) to it.toCard() })
            .sortedByDescending { it.first }.map { it.second }
    }

    private val recommended = combine(c.library.history(100), c.library.favorites()) { h, f -> h to f }
        .mapLatest { (h, f) ->
            try { c.catalog.cardsFor(c.catalog.recommendations(h, f, 30)) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { emptyList() } // las recomendaciones nunca deben cerrar la app
        }

    val state: StateFlow<HomeUiState> = combine(
        combine(c.config.config, appearance, minuteTicker) { cfg, app, now -> Triple(cfg, ConfigResolver.sections(cfg, app), now) },
        lists, recent, recommended, combine(profileName, c.sources.sources) { n, s -> n to s.isNotEmpty() },
    ) { (cfg, sections, now), base, rec, recs, (name, hasSources) ->
        val content = base + mapOf(SectionType.RECENTLY_ADDED to rec, SectionType.RECOMMENDED to recs)
        val heroBanners = ConfigResolver.banners(cfg, BannerPlacement.HERO, now).map { HeroItem(it.id, it.title, it.subtitle, it.imageUrl, it.route) }
        val heroMax = sections.firstOrNull { it.type == SectionType.HERO_BANNER }?.maxItems ?: 5
        val featured = rec.filter { it.backdropUrl != null || it.imageUrl != null }.take(heroMax).map {
            HeroItem(it.ref.id, it.title, it.subtitle, it.backdropUrl ?: it.imageUrl, item = it.ref)
        }
        HomeUiState(
            config = cfg, sections = sections, content = content,
            hero = (heroBanners + featured).take(heroMax),
            promos = ConfigResolver.banners(cfg, BannerPlacement.PROMO, now),
            profileName = name, hasSources = hasSources, loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun markHealthy() = c.config.markHealthy()
}

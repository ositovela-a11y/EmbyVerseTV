package com.embyverse.app.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import com.embyverse.app.ui.components.kenBurns
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.embyverse.app.R
import com.embyverse.app.data.db.EpisodeEntity
import com.embyverse.app.data.db.MovieEntity
import com.embyverse.app.data.db.ProgressEntity
import com.embyverse.app.data.db.SeriesEntity
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.di.AppContainer
import com.embyverse.app.ui.appViewModel
import com.embyverse.app.ui.components.CategoryChip
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.components.LoadingBox
import com.embyverse.app.ui.components.MediaImage
import com.embyverse.app.ui.components.MessageState
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.model.ContentKind
import com.embyverse.core.net.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ PELÍCULA
class MovieDetailViewModel(private val c: AppContainer, private val sid: String, private val id: String) : ViewModel() {
    private val ref = ItemRef(ContentKind.MOVIE, sid, id)
    val movie: StateFlow<MovieEntity?> = c.catalog.movie(sid, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val favorite = c.library.isFavorite(ref).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val progress = MutableStateFlow<ProgressEntity?>(null)

    init {
        viewModelScope.launch { c.sources.loadMovieDetails(sid, id) }
        refreshProgress()
    }
    fun refreshProgress() { viewModelScope.launch { progress.value = c.library.progress(ref) } }
    fun toggleFavorite() = viewModelScope.launch { movie.value?.let { c.library.toggleFavorite(ref, it.title, it.posterUrl) } }
}

@Composable
private fun DetailHeader(imageUrl: String?, poster: String?, kind: ContentKind, onBack: () -> Unit, content: @Composable () -> Unit) {
    val t = LocalEmby.current
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (t.device.isTv) 2.6f else 16f / 10f)) {
            Box(Modifier.fillMaxSize().clipToBounds()) { MediaImage(imageUrl ?: poster, kind, Modifier.fillMaxSize().kenBurns()) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.background.copy(alpha = 0.2f), t.background.copy(alpha = 0.6f), t.background))))
        }
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp).background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
        }
        Row(Modifier.align(Alignment.BottomStart).padding(horizontal = t.screenPadding), verticalAlignment = Alignment.Bottom) {
            if (poster != null) Box(Modifier.width(if (t.device.isTv) 150.dp else 104.dp).aspectRatio(2f / 3f).background(t.surface, RoundedCornerShape(t.cardRadius))) {
                MediaImage(poster, kind, Modifier.fillMaxSize().padding(0.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) { content() }
        }
    }
}

private fun formatDuration(seconds: Long?): String? = seconds?.takeIf { it > 0 }?.let { s -> if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else "${s / 60} min" }

@Composable
fun MovieDetailScreen(nav: Navigator, sid: String, id: String) {
    val vm = appViewModel(key = "movie-$sid-$id") { MovieDetailViewModel(it, sid, id) }
    val movie by vm.movie.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { vm.refreshProgress(); onPauseOrDispose { } }
    val m = movie ?: run { LoadingBox(); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            DetailHeader(m.backdropUrl, m.posterUrl, ContentKind.MOVIE, nav::back) {
                Text(m.title, style = MaterialTheme.typography.headlineSmall, color = t.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(m.year?.toString(), formatDuration(m.durationSeconds), m.rating?.let { "★ %.1f".format(it) }, m.category).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
        item {
            Row(Modifier.padding(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val p = progress
                val resume = p != null && !p.completed && p.positionMs > 15_000
                EmbyButton(stringResource(if (resume) R.string.action_resume else R.string.action_play), { nav.play(ItemRef(ContentKind.MOVIE, sid, id)) }, icon = Icons.Rounded.PlayArrow)
                if (resume) EmbyButton(stringResource(R.string.action_restart), { nav.play(ItemRef(ContentKind.MOVIE, sid, id), "restart") }, icon = Icons.Rounded.Replay, secondary = true)
                m.trailer?.let { url ->
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    EmbyButton(stringResource(R.string.action_trailer), {
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                            .onFailure { android.widget.Toast.makeText(ctx, R.string.trailer_no_app, android.widget.Toast.LENGTH_LONG).show() }
                    }, icon = Icons.Rounded.Movie, secondary = true)
                }
                IconButton(onClick = { vm.toggleFavorite() }) {
                    Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (fav) R.string.action_unfavorite else R.string.action_favorite), tint = if (fav) t.primary else t.onBackground)
                }
            }
        }
        progress?.takeIf { !it.completed && it.durationMs > 0 }?.let { p ->
            item { LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(horizontal = t.screenPadding), color = t.primary, trackColor = t.surfaceVariant) }
        }
        if (m.genres.isNotBlank()) item {
            LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(m.genres.split('|').filter { it.isNotBlank() }) { g -> CategoryChip(g, false) {} }
            }
        }
        item {
            Text(m.plot ?: stringResource(R.string.no_synopsis), style = MaterialTheme.typography.bodyLarge, color = if (m.plot != null) t.onSurface else t.textSecondary,
                modifier = Modifier.padding(horizontal = t.screenPadding))
        }
    }
}

// ------------------------------------------------------------------ SERIE
class SeriesDetailViewModel(private val c: AppContainer, private val sid: String, private val id: String) : ViewModel() {
    private val ref = ItemRef(ContentKind.SERIES, sid, id)
    val series: StateFlow<SeriesEntity?> = c.catalog.seriesOne(sid, id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val favorite = c.library.isFavorite(ref).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val loadError = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(true)

    /** Episodios agrupados por temporada + progreso de cada episodio. */
    val seasons: StateFlow<Map<Int, List<Pair<EpisodeEntity, ProgressEntity?>>>> = combine(
        c.catalog.episodes(sid, id), c.library.episodeProgress(sid, id).map { l -> l.associateBy { it.itemId } },
    ) { eps, prog -> eps.groupBy { it.season }.toSortedMap().mapValues { (_, l) -> l.map { it to prog[it.providerId] } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    init {
        viewModelScope.launch {
            val s = c.database.series().get(sid, id)
            if (s != null && !s.episodesLoaded) {
                when (val r = c.sources.loadEpisodes(sid, id)) {
                    is Outcome.Fail -> loadError.value = r.error.userMessage
                    is Outcome.Ok -> Unit
                }
            }
            loading.value = false
        }
    }
    fun toggleFavorite() = viewModelScope.launch { series.value?.let { c.library.toggleFavorite(ref, it.title, it.posterUrl) } }

    /** Primer episodio sin terminar (o el siguiente al último visto) para "Continuar". */
    fun nextToWatch(): EpisodeEntity? {
        val all = seasons.value.values.flatten()
        val lastIdx = all.indexOfLast { it.second != null }
        if (lastIdx < 0) return all.firstOrNull()?.first
        val (ep, p) = all[lastIdx]
        return if (p?.completed == true) all.getOrNull(lastIdx + 1)?.first ?: ep else ep
    }
}

@Composable
fun SeriesDetailScreen(nav: Navigator, sid: String, id: String) {
    val vm = appViewModel(key = "series-$sid-$id") { SeriesDetailViewModel(it, sid, id) }
    val series by vm.series.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.loadError.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    val s = series ?: run { LoadingBox(); return }
    var season by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(seasons.keys) { if (season !in seasons.keys) season = seasons.keys.firstOrNull() ?: -1 }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            DetailHeader(s.backdropUrl, s.posterUrl, ContentKind.SERIES, nav::back) {
                Text(s.title, style = MaterialTheme.typography.headlineSmall, color = t.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(s.year?.toString(), s.rating?.let { "★ %.1f".format(it) }, s.category,
                    seasons.size.takeIf { it > 0 }?.let { stringResource(R.string.seasons_count, it) }).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
        item {
            Row(Modifier.padding(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val next = vm.nextToWatch()
                if (next != null) EmbyButton(stringResource(R.string.action_play_episode, next.season, next.episode),
                    { nav.play(ItemRef(ContentKind.EPISODE, sid, next.providerId)) }, icon = Icons.Rounded.PlayArrow)
                IconButton(onClick = { vm.toggleFavorite() }) {
                    Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(if (fav) R.string.action_unfavorite else R.string.action_favorite), tint = if (fav) t.primary else t.onBackground)
                }
            }
        }
        s.plot?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge, color = t.onSurface, modifier = Modifier.padding(horizontal = t.screenPadding)) } }
        if (seasons.size > 1) item {
            LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(seasons.keys.toList()) { n -> CategoryChip(stringResource(R.string.season_n, n), n == season) { season = n } }
            }
        }
        when {
            loading && seasons.isEmpty() -> item { Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = t.primary) } }
            seasons.isEmpty() -> item { MessageState(Icons.Rounded.VideoLibrary, stringResource(R.string.episodes_empty), error) }
            else -> items(seasons[season].orEmpty(), key = { it.first.providerId }) { (ep, p) ->
                EpisodeRow(ep, p) { nav.play(ItemRef(ContentKind.EPISODE, sid, ep.providerId)) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: EpisodeEntity, p: ProgressEntity?, onPlay: () -> Unit) {
    val t = LocalEmby.current
    FocusableSurface(onClick = onPlay, modifier = Modifier.fillMaxWidth().padding(horizontal = t.screenPadding).testTag("episode"), focusScale = 1.02f) { focused ->
        Row(Modifier.fillMaxWidth().background(if (focused) t.surfaceVariant else t.surface.copy(alpha = 0.7f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(if (t.device.isTv) 200.dp else 132.dp).aspectRatio(16f / 9f).background(t.surfaceVariant, RoundedCornerShape(t.cardRadius))) {
                MediaImage(ep.thumbnailUrl, ContentKind.EPISODE, Modifier.fillMaxSize())
                p?.takeIf { it.durationMs > 0 }?.let { LinearProgressIndicator(progress = { it.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter), color = t.primary, trackColor = Color.Black.copy(alpha = 0.5f)) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${ep.episode}. ${ep.title}", style = MaterialTheme.typography.titleSmall, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ep.durationSeconds?.let { Text(formatDuration(it) ?: "", style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
                ep.plot?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (p?.completed == true) Text("✓", color = t.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

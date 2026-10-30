package com.embyverse.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.R
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.ui.appViewModel
import com.embyverse.app.ui.components.CategoryChip
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.components.LoadingBox
import com.embyverse.app.ui.components.MediaCardView
import com.embyverse.app.ui.components.MediaImage
import com.embyverse.app.ui.components.MessageState
import com.embyverse.app.ui.components.cardWidth
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.navigation.Routes
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.CardAspect
import com.embyverse.core.config.CardSize
import com.embyverse.core.model.ContentKind
import java.text.DateFormat
import java.util.Date

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    Text(title, style = MaterialTheme.typography.headlineMedium, color = t.onBackground,
        modifier = modifier.statusBarsPadding().padding(horizontal = t.screenPadding, vertical = 12.dp))
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, modifier = modifier,
        placeholder = { Text(hint) }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Clear, stringResource(R.string.action_clear)) } },
        shape = RoundedCornerShape(t.buttonRadius),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = t.focusRing, unfocusedBorderColor = t.surfaceVariant,
            focusedContainerColor = t.surface, unfocusedContainerColor = t.surface, cursorColor = t.primary),
    )
}

@Composable
private fun CategoryBar(categories: List<String>, selected: String?, onSelect: (String?) -> Unit, leading: @Composable () -> Unit = {}) {
    val t = LocalEmby.current
    LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { leading() }
        item { CategoryChip(stringResource(R.string.category_all), selected == null) { onSelect(null) } }
        items(categories, key = { it }) { c -> CategoryChip(c, selected == c) { onSelect(c) } }
    }
}

// ------------------------------------------------------------------ TV EN VIVO
@Composable
fun LiveScreen(nav: Navigator, initialCategory: String?) {
    val vm = appViewModel(key = "live-$initialCategory") { LiveViewModel(it, initialCategory) }
    val s by vm.state.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    var text by rememberSaveable { mutableStateOf("") }
    val selected by vm.selected.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.nav_live))
        SearchField(text, { text = it; vm.setQuery(it) }, stringResource(R.string.search_channels),
            Modifier.fillMaxWidth().padding(horizontal = t.screenPadding))
        Row(Modifier.padding(horizontal = t.screenPadding, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CategoryChip(stringResource(R.string.filter_all), s.filter == LiveFilter.ALL) { vm.setFilter(LiveFilter.ALL) }
            CategoryChip(stringResource(R.string.filter_favorites), s.filter == LiveFilter.FAVORITES) { vm.setFilter(LiveFilter.FAVORITES) }
            CategoryChip(stringResource(R.string.filter_history), s.filter == LiveFilter.HISTORY) { vm.setFilter(LiveFilter.HISTORY) }
        }
        if (s.categories.isNotEmpty()) CategoryBar(s.categories, s.category, vm::setCategory)
        when {
            s.loading -> LoadingBox()
            s.rows.isEmpty() -> MessageState(Icons.Rounded.LiveTv, stringResource(R.string.live_empty_title), stringResource(R.string.live_empty_message),
                stringResource(R.string.nav_sources), { nav.go(Routes.SOURCES) })
            else -> Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(s.rows, key = { "${it.channel.sourceId}|${it.channel.providerId}" }) { row ->
                        ChannelListItem(row, onFocus = { vm.selected.value = row.channel },
                            onPlay = { nav.play(ItemRef(ContentKind.LIVE, row.channel.sourceId, row.channel.providerId), s.category) },
                            onFavorite = { vm.toggleFavorite(row.channel) })
                    }
                }
                // Guía del canal enfocado (sólo en pantallas anchas / TV, y sólo si hay EPG válida).
                if (t.device.useRail || t.device.width != com.embyverse.app.ui.theme.WidthClass.COMPACT) {
                    selected?.let { EpgPanel(vm, it, Modifier.width(320.dp).fillMaxHeight()) }
                }
            }
        }
    }
}

@Composable
private fun ChannelListItem(row: ChannelRow, onFocus: () -> Unit, onPlay: () -> Unit, onFavorite: () -> Unit) {
    val t = LocalEmby.current
    val ch = row.channel
    Row(verticalAlignment = Alignment.CenterVertically) {
        FocusableSurface(onClick = onPlay, modifier = Modifier.weight(1f).testTag("channel").onFocusChanged { if (it.isFocused) onFocus() }, focusScale = 1.02f) { focused ->
            Row(Modifier.fillMaxWidth().background(if (focused) t.surfaceVariant else t.surface.copy(alpha = 0.7f)).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ch.number?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = t.textSecondary, modifier = Modifier.width(36.dp)) }
                Box(Modifier.size(width = 64.dp, height = 40.dp)) { MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, style = MaterialTheme.typography.titleSmall, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = row.nowPlaying?.title ?: ch.category
                    sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (row.nowPlaying != null) t.accent else t.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        IconButton(onClick = onFavorite) {
            Icon(if (row.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                stringResource(if (row.favorite) R.string.action_unfavorite else R.string.action_favorite), tint = if (row.favorite) t.primary else t.textSecondary)
        }
    }
}

@Composable
private fun EpgPanel(vm: LiveViewModel, ch: ChannelEntity, modifier: Modifier) {
    val t = LocalEmby.current
    val flow = remember(ch.sourceId, ch.providerId) { vm.upcoming(ch) }
    val programs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(modifier.padding(end = t.screenPadding, top = 8.dp).background(t.surface.copy(alpha = 0.8f), RoundedCornerShape(t.cardRadius)).padding(16.dp)) {
        Text(ch.name, style = MaterialTheme.typography.titleMedium, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stringResource(R.string.epg_title), style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
        Spacer(Modifier.height(10.dp))
        if (programs.isEmpty()) Text(stringResource(R.string.epg_unavailable), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(programs, key = { it.start }) { p ->
                Column {
                    Text("${fmt.format(Date(p.start * 1000))} – ${fmt.format(Date(p.end * 1000))}", style = MaterialTheme.typography.labelMedium, color = t.accent)
                    Text(p.title, style = MaterialTheme.typography.bodyMedium, color = t.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    p.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ PELÍCULAS Y SERIES
@Composable
private fun VodScreen(nav: Navigator, kind: ContentKind) {
    val vm = appViewModel(key = "vod-$kind") { VodViewModel(it, kind) }
    val s by vm.state.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    var text by rememberSaveable { mutableStateOf("") }
    val title = stringResource(if (kind == ContentKind.MOVIE) R.string.nav_movies else R.string.nav_series)
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title)
        SearchField(text, { text = it; vm.setQuery(it) }, stringResource(R.string.search_in, title), Modifier.fillMaxWidth().padding(horizontal = t.screenPadding))
        if (s.categories.isNotEmpty()) CategoryBar(s.categories, s.category, vm::setCategory)
        when {
            s.loading -> LoadingBox()
            s.cards.isEmpty() -> MessageState(if (kind == ContentKind.MOVIE) Icons.Rounded.Theaters else Icons.Rounded.VideoLibrary,
                stringResource(R.string.vod_empty_title), stringResource(R.string.vod_empty_message))
            else -> {
                val minW = cardWidth(CardAspect.POSTER, CardSize.M, t.device.isTv)
                LazyVerticalGrid(GridCells.Adaptive(minW), Modifier.fillMaxSize().testTag("grid"),
                    contentPadding = PaddingValues(t.screenPadding), horizontalArrangement = Arrangement.spacedBy(t.cardGap), verticalArrangement = Arrangement.spacedBy(t.cardGap + 6.dp)) {
                    items(s.cards, key = { "${it.ref.sourceId}|${it.ref.id}" }) { c ->
                        MediaCardView(c, CardAspect.POSTER, CardSize.M, { nav.openItem(c.ref) })
                    }
                }
            }
        }
    }
}

@Composable fun MoviesScreen(nav: Navigator) = VodScreen(nav, ContentKind.MOVIE)
@Composable fun SeriesScreen(nav: Navigator) = VodScreen(nav, ContentKind.SERIES)

// ------------------------------------------------------------------ FAVORITOS E HISTORIAL
@Composable
fun FavoritesScreen(nav: Navigator) {
    val vm = appViewModel { FavoritesViewModel(it) }
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    val list = favs
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle(stringResource(R.string.favorites_title)) }
        if (list == null) item { LoadingBox(Modifier.height(200.dp)) }
        else if (list.isEmpty()) item { MessageState(Icons.Rounded.FavoriteBorder, stringResource(R.string.favorites_empty_title), stringResource(R.string.favorites_empty_message)) }
        else item {
            LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(t.cardGap)) {
                items(list, key = { "${it.ref.kind}|${it.ref.sourceId}|${it.ref.id}" }) { c ->
                    MediaCardView(c, if (c.ref.kind == ContentKind.LIVE) CardAspect.LANDSCAPE else CardAspect.POSTER, CardSize.M, { nav.openItem(c.ref) })
                }
            }
        }
        if (history.isNotEmpty()) {
            item { ScreenTitle(stringResource(R.string.history_title)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(t.cardGap)) {
                    items(history, key = { "${it.ref.kind}|${it.ref.sourceId}|${it.ref.id}" }) { c ->
                        MediaCardView(c, CardAspect.LANDSCAPE, CardSize.S, { nav.openItem(c.ref, seriesId = c.seriesId) })
                    }
                }
            }
        }
    }
}

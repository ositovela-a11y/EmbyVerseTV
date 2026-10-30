package com.embyverse.app.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.PlayerView
import com.embyverse.app.player.LivePreview
import com.embyverse.app.ui.LocalAppContainer
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
/**
 * TV en vivo con VISTA PREVIA: el primer OK (o toque) en un canal lo reproduce en el recuadro;
 * el segundo OK sobre el mismo canal, o pulsar el recuadro, lo abre a pantalla completa.
 * En TV y pantallas anchas la vista previa va a la derecha; en teléfono, arriba de la lista.
 */
@Composable
fun LiveScreen(nav: Navigator, initialCategory: String?) {
    val vm = appViewModel(key = "live-$initialCategory") { LiveViewModel(it, initialCategory) }
    val s by vm.state.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    var text by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val preview = remember { LivePreview(context, container) }
    val previewing by preview.channel.collectAsStateWithLifecycle()
    val wide = t.device.useRail || t.device.width != com.embyverse.app.ui.theme.WidthClass.COMPACT

    // La vista previa se detiene al salir de la pantalla y se reanuda al volver.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) preview.release()
            if (e == Lifecycle.Event.ON_START) preview.channel.value?.let { preview.play(it) }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); preview.dispose() }
    }
    val fullscreen: (ChannelEntity) -> Unit = { ch ->
        preview.release()
        nav.play(ItemRef(ContentKind.LIVE, ch.sourceId, ch.providerId), s.category)
    }
    val onChannel: (ChannelEntity) -> Unit = { ch -> if (preview.isPreviewing(ch)) fullscreen(ch) else preview.play(ch) }

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
            wide -> Row(Modifier.fillMaxSize()) {
                ChannelList(s, previewing, vm, onChannel, showFavoriteButton = !t.device.isTv, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(Modifier.width(if (t.device.isTv) 460.dp else 380.dp).fillMaxHeight().padding(end = t.screenPadding, top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreviewBox(preview, onFullscreen = fullscreen, modifier = Modifier.fillMaxWidth())
                    SelectedInfo(vm, previewing, Modifier.fillMaxWidth().weight(1f))
                }
            }
            else -> Column(Modifier.fillMaxSize()) {
                if (previewing != null) PreviewBox(preview, onFullscreen = fullscreen, modifier = Modifier.fillMaxWidth().padding(horizontal = t.screenPadding, vertical = 6.dp))
                ChannelList(s, previewing, vm, onChannel, showFavoriteButton = true, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ChannelList(s: LiveUiState, previewing: ChannelEntity?, vm: LiveViewModel, onChannel: (ChannelEntity) -> Unit, showFavoriteButton: Boolean, modifier: Modifier) {
    val t = LocalEmby.current
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(s.rows, key = { "${it.channel.sourceId}|${it.channel.providerId}" }, contentType = { 0 }) { row ->
            val ch = row.channel
            ChannelListItem(row, isPreviewing = previewing != null && previewing.sourceId == ch.sourceId && previewing.providerId == ch.providerId,
                onFocus = { vm.selected.value = ch }, onPlay = { onChannel(ch) }, onFavorite = { vm.toggleFavorite(ch) }, showFavoriteButton = showFavoriteButton)
        }
    }
}

@Composable
private fun ChannelListItem(row: ChannelRow, isPreviewing: Boolean, onFocus: () -> Unit, onPlay: () -> Unit, onFavorite: () -> Unit, showFavoriteButton: Boolean) {
    val t = LocalEmby.current
    val ch = row.channel
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Mantener pulsado (OK largo en el mando) = añadir o quitar de favoritos.
        FocusableSurface(onClick = onPlay, onLongClick = onFavorite, modifier = Modifier.weight(1f).testTag("channel").onFocusChanged { if (it.isFocused) onFocus() }, focusScale = 1.03f) { focused ->
            Row(Modifier.fillMaxWidth()
                .background(when { focused -> t.primary.copy(alpha = 0.22f); isPreviewing -> t.surfaceVariant; else -> t.surface.copy(alpha = 0.7f) })
                .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ch.number?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = if (focused) t.onBackground else t.textSecondary, modifier = Modifier.width(40.dp)) }
                Box(Modifier.size(width = 60.dp, height = 38.dp)) { MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, style = MaterialTheme.typography.titleSmall, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (focused || isPreviewing) androidx.compose.ui.text.font.FontWeight.Bold else null)
                    val sub = row.nowPlaying?.title ?: ch.category
                    sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (row.nowPlaying != null) t.accent else t.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (isPreviewing) Text("● " + stringResource(R.string.preview_now), style = MaterialTheme.typography.labelSmall, color = t.primary)
                else if (row.favorite && !showFavoriteButton) Icon(Icons.Rounded.Favorite, null, tint = t.primary, modifier = Modifier.size(16.dp))
            }
        }
        if (showFavoriteButton) IconButton(onClick = onFavorite) {
            Icon(if (row.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                stringResource(if (row.favorite) R.string.action_unfavorite else R.string.action_favorite), tint = if (row.favorite) t.primary else t.textSecondary)
        }
    }
}

/** Recuadro de vista previa: muestra el canal elegido; pulsarlo (OK) lo abre a pantalla completa. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun PreviewBox(preview: LivePreview, onFullscreen: (ChannelEntity) -> Unit, modifier: Modifier) {
    val t = LocalEmby.current
    val player by preview.player.collectAsStateWithLifecycle()
    val ch by preview.channel.collectAsStateWithLifecycle()
    val buffering by preview.buffering.collectAsStateWithLifecycle()
    val error by preview.error.collectAsStateWithLifecycle()
    com.embyverse.app.player.KeepScreenOnWhilePlaying(player)
    FocusableSurface(onClick = { ch?.let(onFullscreen) }, modifier = modifier.aspectRatio(16f / 9f).testTag("preview"), focusScale = 1.02f) { focused ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (ch != null) AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER); setKeepContentOnPlayerReset(true) } },
                update = { v -> v.player = player },
                onRelease = { v -> v.player = null },
            )
            val c = ch
            when {
                c == null -> Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LiveTv, null, tint = t.textSecondary, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.preview_hint), style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                error -> Text(stringResource(R.string.preview_error), style = MaterialTheme.typography.bodyMedium, color = t.onBackground, modifier = Modifier.align(Alignment.Center).padding(16.dp))
                buffering -> androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = t.primary)
            }
            if (c != null) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.preview_fullscreen), style = MaterialTheme.typography.labelSmall, color = if (focused) t.primary else Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

/** Guía (ahora y después) del canal enfocado o en vista previa. Lee la selección aquí para no recomponer la lista. */
@Composable
private fun SelectedInfo(vm: LiveViewModel, previewing: ChannelEntity?, modifier: Modifier) {
    val focused by vm.selected.collectAsStateWithLifecycle()
    val ch = focused ?: previewing ?: return
    EpgPanel(vm, ch, modifier)
}

@Composable
private fun EpgPanel(vm: LiveViewModel, ch: ChannelEntity, modifier: Modifier) {
    val t = LocalEmby.current
    val flow = remember(ch.sourceId, ch.providerId) { vm.upcoming(ch) }
    val programs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    Column(modifier.background(t.surface.copy(alpha = 0.8f), RoundedCornerShape(t.cardRadius)).padding(16.dp)) {
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

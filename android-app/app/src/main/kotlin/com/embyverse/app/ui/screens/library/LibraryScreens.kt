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
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.GridView
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.togetherWith
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
import com.embyverse.app.ui.theme.topMenu
import com.embyverse.core.config.CardAspect
import com.embyverse.core.config.CardSize
import com.embyverse.core.model.ContentKind
import java.text.DateFormat
import kotlinx.coroutines.launch
import java.util.Date

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    // Con menú arriba (estilos de plataforma) la barra ya deja el espacio del estado: sin hueco extra.
    val topMenu = t.topMenu
    Text(title, style = MaterialTheme.typography.headlineMedium, color = t.onBackground,
        modifier = modifier.then(if (topMenu) Modifier else Modifier.statusBarsPadding()).padding(horizontal = t.screenPadding, vertical = if (topMenu) 4.dp else 12.dp))
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

/** Una carpeta del catálogo (o un filtro como Favoritos / Historial). */
data class Folder(val key: String, val label: String)

/** Celular: una sola fila de carpetas que se desliza con el dedo; la elegida queda resaltada y visible. */
@Composable
private fun FolderRow(folders: List<Folder>, selected: String, onSelect: (String) -> Unit) {
    val t = LocalEmby.current
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val i = folders.indexOfFirst { it.key == selected }
        if (i > 0) state.scrollToItem((i - 1).coerceAtLeast(0))
    }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(folders, key = { it.key }) { f ->
            Box(Modifier.testTag(if (f.key == KEY_ALL) "folder_all" else "folder")) { CategoryChip(f.label, selected == f.key) { onSelect(f.key) } }
        }
    }
}

/**
 * TV: columna de carpetas a la izquierda. Al bajar con el control la carpeta se abre sola (sin pulsar OK),
 * ▶ entra a su contenido y ◀ regresa exactamente a la carpeta en la que estabas.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FolderColumn(folders: List<Folder>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val restore = remember { androidx.compose.ui.focus.FocusRequester() }
    var pending by remember { mutableStateOf<String?>(null) }
    // Elegir al enfocar, pero esperando un instante: si se mantiene ▼ pulsado no se carga cada carpeta.
    androidx.compose.runtime.LaunchedEffect(pending) {
        val p = pending ?: return@LaunchedEffect
        kotlinx.coroutines.delay(180)
        if (p != selected) onSelect(p)
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val i = folders.indexOfFirst { it.key == selected }
        if (i > 2) state.scrollToItem(i - 2)
    }
    LazyColumn(state = state, modifier = modifier.testTag("folders").focusRestorer(restore),
        contentPadding = PaddingValues(start = t.screenPadding, end = 8.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(folders, key = { it.key }, contentType = { 0 }) { f ->
            val isSel = f.key == selected
            FocusableSurface(onClick = { pending = null; onSelect(f.key) }, focusScale = 1f,
                modifier = Modifier.fillMaxWidth().testTag(if (f.key == KEY_ALL) "folder_all" else "folder")
                    .then(if (isSel) Modifier.focusRequester(restore) else Modifier)
                    .onFocusChanged { if (it.isFocused && f.key != KEY_LOCK) pending = f.key }) { focused ->
                Row(Modifier.fillMaxWidth().background(when { focused -> t.primary.copy(alpha = 0.28f); isSel -> t.surfaceVariant; else -> Color.Transparent })
                    .padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 3.dp, height = 18.dp).background(if (isSel) t.primary else Color.Transparent))
                    Spacer(Modifier.width(10.dp))
                    Text(f.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (isSel || focused) t.onBackground else t.textSecondary,
                        fontWeight = if (isSel || focused) androidx.compose.ui.text.font.FontWeight.Bold else null)
                }
            }
        }
    }
}

private const val KEY_ALL = "\u0000all"
private const val KEY_FAV = "\u0000fav"
private const val KEY_HIST = "\u0000hist"
private const val KEY_LOCK = "\u0000lock"

/**
 * Entrada de control parental al final de las carpetas: desbloquear con el PIN del perfil
 * (o volver a bloquear). Devuelve la carpeta extra y el manejador; dibuja el diálogo de PIN.
 */
@Composable
private fun rememberParentalFolder(): Pair<Folder?, () -> Unit> {
    val c = LocalAppContainer.current
    val statusFlow = remember { c.parental.status }
    val status by statusFlow.collectAsStateWithLifecycle(initialValue = com.embyverse.app.data.repo.ParentalControl.Status.OFF)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var ask by remember { mutableStateOf(false) }
    if (ask) com.embyverse.app.ui.components.PinDialog(stringResource(R.string.parental_unlock), stringResource(R.string.parental_unlock_sub),
        onDismiss = { ask = false }) { pin -> c.parental.unlock(pin).also { if (it) ask = false } }
    val folder = when (status) {
        com.embyverse.app.data.repo.ParentalControl.Status.LOCKED -> Folder(KEY_LOCK, "🔒 " + stringResource(R.string.parental_unlock))
        com.embyverse.app.data.repo.ParentalControl.Status.UNLOCKED -> Folder(KEY_LOCK, "🔓 " + stringResource(R.string.parental_relock))
        else -> null
    }
    return folder to {
        if (status == com.embyverse.app.data.repo.ParentalControl.Status.LOCKED) ask = true
        else scope.launch { c.parental.relock() }
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
        nav.play(ItemRef(ContentKind.LIVE, ch.sourceId, ch.providerId), s.category?.takeIf { !it.startsWith("\u0000") })
    }
    val onChannel: (ChannelEntity) -> Unit = { ch -> if (preview.isPreviewing(ch)) fullscreen(ch) else preview.play(ch) }

    val allLabel = stringResource(R.string.category_all)
    val favLabel = stringResource(R.string.filter_favorites)
    val histLabel = stringResource(R.string.filter_history)
    val eventsLabel = stringResource(R.string.events_today)
    val (lockFolder, onLockFolder) = rememberParentalFolder()
    val folders = remember(s.categories, allLabel, lockFolder, eventsLabel) {
        listOf(Folder(CATEGORY_EVENTS, "⚽ $eventsLabel"), Folder(KEY_FAV, "♥ $favLabel"), Folder(KEY_HIST, "⟲ $histLabel"), Folder(KEY_ALL, allLabel)) +
            s.categories.map { Folder(it, it) } + listOfNotNull(lockFolder)
    }
    val selectedFolder = when (s.filter) { LiveFilter.FAVORITES -> KEY_FAV; LiveFilter.HISTORY -> KEY_HIST; else -> s.category ?: KEY_ALL }
    val onFolder: (String) -> Unit = { k ->
        when (k) {
            KEY_LOCK -> onLockFolder()
            KEY_FAV -> vm.setFilter(LiveFilter.FAVORITES)
            KEY_HIST -> vm.setFilter(LiveFilter.HISTORY)
            else -> { vm.setFilter(LiveFilter.ALL); vm.setCategory(if (k == KEY_ALL) null else k) }
        }
    }
    val listKey = "$selectedFolder|${s.query}"

    Column(Modifier.fillMaxSize()) {
        if (t.device.isTv) {
            Row(Modifier.statusBarsPadding().padding(end = t.screenPadding), verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(stringResource(R.string.nav_live), Modifier.weight(1f))
                com.embyverse.app.ui.components.Clock(Modifier.padding(end = 16.dp))
                com.embyverse.app.ui.components.RefreshListsButton(Modifier.padding(end = 12.dp))
                com.embyverse.app.ui.components.EmbyButton(stringResource(R.string.guide_title), { nav.go(Routes.GUIDE) }, icon = Icons.Rounded.CalendarViewWeek, secondary = true)
                Spacer(Modifier.width(12.dp))
                com.embyverse.app.ui.components.EmbyButton(stringResource(R.string.multiview_title), { nav.go(Routes.MULTIVIEW) }, icon = Icons.Rounded.GridView, secondary = true,
                    modifier = Modifier.testTag("open_multiview"))
                Spacer(Modifier.width(12.dp))
                SearchField(text, { text = it; vm.setQuery(it) }, stringResource(R.string.search_channels), Modifier.width(360.dp))
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(stringResource(R.string.nav_live), Modifier.weight(1f))
                com.embyverse.app.ui.components.RefreshListsButton(Modifier.statusBarsPadding(), compact = true)
                IconButton(onClick = { nav.go(Routes.MULTIVIEW) }, modifier = Modifier.statusBarsPadding().testTag("open_multiview")) {
                    Icon(Icons.Rounded.GridView, stringResource(R.string.multiview_title), tint = t.onBackground)
                }
                IconButton(onClick = { nav.go(Routes.GUIDE) }, modifier = Modifier.statusBarsPadding().padding(end = 8.dp).testTag("open_guide")) {
                    Icon(Icons.Rounded.CalendarViewWeek, stringResource(R.string.guide_title), tint = t.onBackground)
                }
            }
            SearchField(text, { text = it; vm.setQuery(it) }, stringResource(R.string.search_channels),
                Modifier.fillMaxWidth().padding(horizontal = t.screenPadding))
            FolderRow(folders, selectedFolder, onFolder)
        }
        Row(Modifier.fillMaxSize()) {
            if (t.device.isTv) FolderColumn(folders, selectedFolder, onFolder, Modifier.width(210.dp).fillMaxHeight())
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                // Lista de canales con espacio para el nombre completo; vista previa + ficha ≈ 40 % del ancho.
                val sideW = (maxWidth * 0.42f).coerceIn(300.dp, 480.dp)
                when {
                    s.loading -> LoadingBox()
                    s.rows.isEmpty() && s.filter == LiveFilter.ALL && s.category == null && s.query.isEmpty() ->
                        MessageState(Icons.Rounded.LiveTv, stringResource(R.string.live_empty_title), stringResource(R.string.live_empty_message),
                            stringResource(R.string.nav_sources), { nav.go(Routes.SOURCES) })
                    s.rows.isEmpty() -> MessageState(Icons.Rounded.LiveTv, stringResource(R.string.folder_empty_title), stringResource(R.string.folder_empty_message))
                    wide -> Row(Modifier.fillMaxSize()) {
                        ChannelList(s, listKey, previewing, vm, onChannel, showFavoriteButton = !t.device.isTv, modifier = Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(sideW).fillMaxHeight().padding(end = t.screenPadding, top = 8.dp, bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            PreviewBox(preview, onFullscreen = fullscreen, modifier = Modifier.fillMaxWidth())
                            SelectedInfo(vm, previewing, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                    else -> Column(Modifier.fillMaxSize()) {
                        if (previewing != null) PreviewBox(preview, onFullscreen = fullscreen, modifier = Modifier.fillMaxWidth().padding(horizontal = t.screenPadding, vertical = 6.dp))
                        ChannelList(s, listKey, previewing, vm, onChannel, showFavoriteButton = true, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelList(s: LiveUiState, listKey: String, previewing: ChannelEntity?, vm: LiveViewModel, onChannel: (ChannelEntity) -> Unit, showFavoriteButton: Boolean, modifier: Modifier) {
    val t = LocalEmby.current
    // Cada carpeta empieza arriba: la lista no "brinca" a una posición vieja al cambiar de carpeta.
    val state = androidx.compose.runtime.key(listKey) { androidx.compose.foundation.lazy.rememberLazyListState() }
    LazyColumn(modifier.testTag("channels"), state = state, contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        FocusableSurface(onClick = onPlay, onLongClick = onFavorite, modifier = Modifier.weight(1f).testTag("channel").onFocusChanged { if (it.isFocused) onFocus() }, focusScale = 1f) { focused ->
            Row(Modifier.fillMaxWidth()
                .background(when { focused -> t.primary.copy(alpha = 0.22f); isPreviewing -> t.surfaceVariant; else -> t.surface.copy(alpha = 0.7f) })
                .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ch.number?.let { Text("$it", style = MaterialTheme.typography.labelMedium, color = if (focused) t.onBackground else t.textSecondary,
                    maxLines = 1, modifier = Modifier.width(if (it >= 1000) 44.dp else 34.dp)) }
                Box(Modifier.size(width = 52.dp, height = 34.dp)) { MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    // El nombre muchas veces ES la información del evento ("10/30 LIGUE 1 12:00pm"): se ve completo.
                    Text(ch.name, style = MaterialTheme.typography.titleSmall, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (focused || isPreviewing) androidx.compose.ui.text.font.FontWeight.Bold else null)
                    val now = row.nowPlaying
                    when {
                        now != null -> {
                            Text(now.title, style = MaterialTheme.typography.bodySmall, color = t.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            ProgramProgress(now.start, now.end, Modifier.padding(top = 4.dp).fillMaxWidth(0.6f))
                        }
                        isPreviewing -> Text("● " + stringResource(R.string.preview_now), style = MaterialTheme.typography.labelSmall, color = t.primary)
                    }
                }
                if (row.favorite && !showFavoriteButton) Icon(Icons.Rounded.Favorite, null, tint = t.primary, modifier = Modifier.size(16.dp))
            }
        }
        if (showFavoriteButton) IconButton(onClick = onFavorite) {
            Icon(if (row.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                stringResource(if (row.favorite) R.string.action_unfavorite else R.string.action_favorite), tint = if (row.favorite) t.primary else t.textSecondary)
        }
    }
}

/** Barra de avance del programa en curso. */
@Composable
private fun ProgramProgress(start: Long, end: Long, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    val now = System.currentTimeMillis() / 1000
    val f = if (end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
    Box(modifier.height(3.dp).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(t.primary, RoundedCornerShape(2.dp)))
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
    val focused by vm.selectedSettled.collectAsStateWithLifecycle()
    val ch = focused ?: previewing ?: return
    EpgPanel(vm, ch, modifier)
}

/**
 * Ficha del canal enfocado o en vista previa: logotipo, nombre completo (que en los canales de eventos
 * dice qué partido pasa y a qué hora), carpeta, programa actual con su avance, lo que sigue y la guía.
 */
@Composable
private fun EpgPanel(vm: LiveViewModel, ch: ChannelEntity, modifier: Modifier) {
    val t = LocalEmby.current
    val flow = remember(ch.sourceId, ch.providerId) { vm.upcoming(ch) }
    val programs by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val nowSec = System.currentTimeMillis() / 1000
    val current = programs.firstOrNull { it.start <= nowSec && it.end > nowSec }
    val later = programs.filter { it !== current }
    LazyColumn(modifier.background(t.surface.copy(alpha = 0.85f), RoundedCornerShape(t.cardRadius)).testTag("channel_info"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "head") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(width = 76.dp, height = 50.dp).background(t.surfaceVariant, RoundedCornerShape(8.dp)).padding(4.dp)) {
                    MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ch.name, style = MaterialTheme.typography.titleMedium, color = t.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(listOfNotNull(ch.number?.let { "Canal $it" }, ch.category).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium, color = t.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (current != null) item(key = "now") {
            Column(Modifier.fillMaxWidth().background(t.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.epg_now).uppercase(), style = MaterialTheme.typography.labelSmall, color = t.primary)
                Text(current.title, style = MaterialTheme.typography.titleMedium, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${fmt.format(Date(current.start * 1000))} – ${fmt.format(Date(current.end * 1000))}", style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
                ProgramProgress(current.start, current.end, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                current.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            }
        } else item(key = "noepg") {
            // Sin guía: los canales de eventos traen la información en el nombre (equipo, liga, fecha y hora).
            val event = eventInfo(ch.name)
            Column(Modifier.fillMaxWidth().background(t.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (event != null) {
                    Text(stringResource(R.string.epg_event).uppercase(), style = MaterialTheme.typography.labelSmall, color = t.primary)
                    Text(event, style = MaterialTheme.typography.titleMedium, color = t.onBackground, maxLines = 3, overflow = TextOverflow.Ellipsis)
                } else Text(stringResource(R.string.epg_unavailable), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
        if (later.isNotEmpty()) item(key = "nextTitle") { Text(stringResource(R.string.epg_next).uppercase(), style = MaterialTheme.typography.labelSmall, color = t.textSecondary) }
        items(later, key = { it.start }) { p ->
            Row {
                Text(fmt.format(Date(p.start * 1000)), style = MaterialTheme.typography.labelMedium, color = t.accent, modifier = Modifier.width(64.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium, color = t.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    p.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** Si el nombre del canal describe un evento (fecha u hora, "vs", liga…), devuelve ese texto limpio. */
internal fun eventInfo(name: String): String? {
    val clean = name.replace(Regex("^[A-Z]{2,3}\\s*[|:-]\\s*"), "").trim()
    val hasTime = Regex("\\b\\d{1,2}[:.]\\d{2}\\s*(am|pm|hrs|h)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
        Regex("\\b\\d{1,2}/\\d{1,2}\\b").containsMatchIn(clean)
    val hasVs = Regex("\\b(vs\\.?|v\\.?|x|contra)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)
    return if (hasTime || hasVs) clean else null
}

// ------------------------------------------------------------------ PELÍCULAS Y SERIES
@Composable
private fun VodScreen(nav: Navigator, kind: ContentKind, initialCategory: String? = null) {
    val vm = appViewModel(key = "vod-$kind-${initialCategory ?: ""}") { VodViewModel(it, kind, initialCategory) }
    val s by vm.state.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    var text by rememberSaveable { mutableStateOf("") }
    val title = stringResource(if (kind == ContentKind.MOVIE) R.string.nav_movies else R.string.nav_series)
    Column(Modifier.fillMaxSize()) {
        val allLabel = stringResource(R.string.category_all)
        val (lockFolder, onLockFolder) = rememberParentalFolder()
        val newLabel = stringResource(R.string.folder_newest)
        val folders = remember(s.categories, allLabel, lockFolder, newLabel) {
            listOf(Folder(CATEGORY_NEW, "★ $newLabel"), Folder(KEY_ALL, allLabel)) + s.categories.map { Folder(it, it) } + listOfNotNull(lockFolder)
        }
        val selectedFolder = s.category ?: KEY_ALL
        val onFolder: (String) -> Unit = { k -> if (k == KEY_LOCK) onLockFolder() else vm.setCategory(if (k == KEY_ALL) null else k) }
        if (t.device.isTv) {
            val sp by vm.spotlight.collectAsStateWithLifecycle()
            Box(Modifier.fillMaxWidth().height(210.dp)) {
                SpotlightHeader(sp, title, kind, Modifier.fillMaxSize())
                SearchField(text, { text = it; vm.setQuery(it) }, stringResource(R.string.search_in, title),
                    Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = t.screenPadding).width(320.dp))
            }
        } else {
            ScreenTitle(title)
            SearchField(text, { text = it; vm.setQuery(it) }, stringResource(R.string.search_in, title), Modifier.fillMaxWidth().padding(horizontal = t.screenPadding))
            if (folders.size > 1) FolderRow(folders, selectedFolder, onFolder)
        }
        Row(Modifier.fillMaxSize()) {
            if (t.device.isTv && folders.size > 1) FolderColumn(folders, selectedFolder, onFolder, Modifier.width(210.dp).fillMaxHeight())
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when {
                    s.loading -> LoadingBox()
                    s.cards.isEmpty() -> MessageState(if (kind == ContentKind.MOVIE) Icons.Rounded.Theaters else Icons.Rounded.VideoLibrary,
                        stringResource(R.string.vod_empty_title), stringResource(R.string.vod_empty_message))
                    else -> {
                        // Celular: 4 columnas (o más en pantallas grandes); TV: según el ancho.
                        val minW = if (t.device.isTv) cardWidth(CardAspect.POSTER, CardSize.M, true) else 76.dp
                        val gridState = androidx.compose.runtime.key("$selectedFolder|${s.query}") { androidx.compose.foundation.lazy.grid.rememberLazyGridState() }
                        val grid = @Composable {
                            LazyVerticalGrid(GridCells.Adaptive(minW), Modifier.fillMaxSize().testTag("grid"), state = gridState,
                                contentPadding = PaddingValues(t.screenPadding), horizontalArrangement = Arrangement.spacedBy(t.cardGap), verticalArrangement = Arrangement.spacedBy(t.cardGap + 6.dp)) {
                                items(s.cards, key = { "${it.ref.sourceId}|${it.ref.id}" }, contentType = { 0 }) { c ->
                                    MediaCardView(c, CardAspect.POSTER, CardSize.M, { nav.openItem(c.ref) },
                                        modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.hasFocus) vm.focused.value = c.ref },
                                        showText = !t.device.isTv) // en TV el título y la ficha se ven arriba, en grande
                                }
                            }
                        }
                        if (t.device.isTv) com.embyverse.app.ui.RowAlignedScrolling { grid() } else grid()
                    }
                }
            }
        }
    }
}

/**
 * Cabecera de "plataforma" en TV: fondo panorámico del título enfocado, con su nombre, año,
 * calificación, géneros y sinopsis. Cambia con un fundido al moverse por la cuadrícula.
 */
@Composable
private fun SpotlightHeader(sp: Spotlight?, sectionTitle: String, kind: ContentKind, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    androidx.compose.animation.AnimatedContent(sp, modifier.testTag("spotlight"), label = "spotlight",
        contentKey = { it?.ref?.id },
        transitionSpec = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)) }) { item ->
        Box(Modifier.fillMaxSize()) {
            val img = item?.backdrop ?: item?.poster
            if (img != null) Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f)) {
                MediaImage(img, kind, Modifier.fillMaxSize(), fallbackText = null)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(t.background, t.background.copy(alpha = 0.55f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to t.background)))
            }
            Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.55f).padding(start = t.screenPadding, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sectionTitle.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 2.sp), color = t.primary)
                if (item == null) Text(stringResource(R.string.spotlight_hint), style = MaterialTheme.typography.titleMedium, color = t.textSecondary)
                else {
                    Text(item.title, style = MaterialTheme.typography.headlineMedium, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    if (item.meta.isNotBlank()) Text(item.meta, style = MaterialTheme.typography.labelLarge, color = t.accent, maxLines = 1)
                    item.plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = t.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

@Composable fun MoviesScreen(nav: Navigator, initialCategory: String? = null) = VodScreen(nav, ContentKind.MOVIE, initialCategory)
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

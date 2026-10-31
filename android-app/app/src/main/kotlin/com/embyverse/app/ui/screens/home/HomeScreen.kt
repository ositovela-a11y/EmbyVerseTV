package com.embyverse.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SatelliteAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.R
import com.embyverse.app.ui.appViewModel
import com.embyverse.app.ui.components.BrandLogo
import com.embyverse.app.ui.components.CardGrid
import com.embyverse.app.ui.components.CardRow
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.components.LoadingBox
import com.embyverse.app.ui.components.MediaImage
import com.embyverse.app.ui.components.MessageState
import com.embyverse.app.ui.components.SectionHeader
import com.embyverse.app.ui.components.cardWidth
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.navigation.Routes
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.Banner
import com.embyverse.core.config.CardAspect
import com.embyverse.core.config.SectionLayout
import com.embyverse.core.config.SectionSpec
import com.embyverse.core.config.SectionType
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: Navigator) {
    val vm = appViewModel { HomeViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val t = LocalEmby.current

    // Si la pantalla de inicio se mantiene estable 3 s, la configuración remota se marca como sana.
    LaunchedEffect(s.config.version, s.loading) { if (!s.loading) { delay(3_000); vm.markHealthy() } }

    if (s.loading) { LoadingBox(); return }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(t.sectionGap)) {
        item(key = "header") { HomeHeader(s, nav) }
        s.expiresAt?.let { exp ->
            val days = ((exp - System.currentTimeMillis() / 1000) / 86_400).toInt()
            if (days <= 10) item(key = "expiry") { ExpiryNotice(exp, days) }
        }
        if (!s.hasSources) item(key = "empty") {
            MessageState(Icons.Rounded.SatelliteAlt, stringResource(R.string.home_no_sources_title),
                s.config.text("home.emptySources", stringResource(R.string.home_no_sources_message)),
                stringResource(R.string.action_add_source), { nav.go(Routes.sourceEdit()) })
        }
        s.sections.forEach { sec ->
            val type = sec.type ?: return@forEach
            when (type) {
                SectionType.HERO_BANNER -> {
                    if (s.hero.isNotEmpty()) item(key = sec.id) { HeroCarousel(s.hero, nav) }
                    // Justo debajo de las novedades: partidos y eventos de hoy.
                    if (s.events.isNotEmpty()) item(key = "events") {
                        Column {
                            SectionHeader(stringResource(R.string.events_today), stringResource(R.string.events_today_sub), null)
                            EventsRow(s.events, nav)
                        }
                    }
                }
                SectionType.PROMO_BANNERS -> if (s.promos.isNotEmpty()) item(key = sec.id) {
                    Column { SectionHeader(sec.title, sec.subtitle, null); Spacer(Modifier.height(8.dp)); PromoRow(s.promos.take(sec.maxItems), sec, nav) }
                }
                else -> {
                    val cards = s.content[type].orEmpty().take(sec.maxItems)
                    if (cards.isNotEmpty()) item(key = sec.id) {
                        Column {
                            SectionHeader(sec.title, sec.subtitle, seeAll(type, nav))
                            if (sec.layout == SectionLayout.GRID) {
                                val cols = gridColumns(sec, t.device.isTv)
                                CardGrid(cards, sec.aspect, sec.size, cols) { nav.openItem(it.ref, seriesId = it.seriesId) }
                            } else {
                                // Estilo de plataforma: películas/series en tarjetas horizontales (p. ej. Cristal, Océano) o pósters.
                                val aspect = t.rowAspect?.takeIf { type in setOf(SectionType.MOVIES, SectionType.SERIES, SectionType.RECENTLY_ADDED, SectionType.FAVORITES) } ?: sec.aspect
                                CardRow(cards, aspect, sec.size) { nav.openItem(it.ref, seriesId = it.seriesId) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun seeAll(type: SectionType, nav: Navigator): (() -> Unit)? = when (type) {
    SectionType.LIVE_TV -> { { nav.goTop(Routes.LIVE) } }
    SectionType.MOVIES -> { { nav.goTop(Routes.MOVIES) } }
    SectionType.SERIES -> { { nav.goTop(Routes.SERIES) } }
    SectionType.FAVORITES -> { { nav.go(Routes.FAVORITES) } }
    else -> null
}

@Composable
private fun gridColumns(sec: SectionSpec, tv: Boolean): Int {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val t = LocalEmby.current
    val w = cardWidth(sec.aspect, sec.size, tv).value + t.cardGap.value
    val avail = cfg.screenWidthDp - 2 * t.screenPadding.value - (if (t.device.useRail) 96 else 0)
    return (avail / w).toInt().coerceIn(2, 8)
}

@Composable
private fun HomeHeader(s: HomeUiState, nav: Navigator) {
    val t = LocalEmby.current
    Column {
        // Con menú arriba (Apple TV / HBO Max) el logotipo ya está en la barra: el inicio empieza directo con el banner.
        val topMenu = t.navVariant == com.embyverse.core.config.NavVariant.PILLS || t.navVariant == com.embyverse.core.config.NavVariant.TEXT_TABS
        if (!topMenu) HomeHeaderRow(s, nav)
        // Estilo de plataforma (tipo Netflix en celular): atajos de texto bajo la cabecera.
        if (t.homeChips && !t.device.isTv) Row(Modifier.padding(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.nav_series to Routes.SERIES, R.string.nav_movies to Routes.MOVIES, R.string.nav_live to Routes.LIVE).forEach { (label, route) ->
                FocusableSurface(onClick = { nav.goTop(route) }, shape = RoundedCornerShape(50), focusScale = 1.04f) { _ ->
                    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = t.onBackground,
                        modifier = Modifier.border(1.dp, t.onBackground.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeHeaderRow(s: HomeUiState, nav: Navigator) {
    val t = LocalEmby.current
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = t.screenPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandLogo(s.config.brand.logoVariant, s.config.brand.logoUrl, Modifier.height(if (t.device.isTv) 64.dp else 48.dp).width(if (t.device.isTv) 140.dp else 104.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text("${s.config.text("home.greeting", stringResource(R.string.home_greeting))}${s.profileName?.let { ", $it" } ?: ""}",
                style = MaterialTheme.typography.titleMedium, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.config.brand.tagline?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 1) }
        }
        IconButton(onClick = { nav.goTop(Routes.SEARCH) }) { Icon(Icons.Rounded.Search, stringResource(R.string.nav_search), tint = t.onBackground) }
        FocusableSurface(onClick = { nav.go(Routes.PROFILES) }, shape = CircleShape) { _ ->
            Box(Modifier.size(36.dp).background(t.primary), contentAlignment = Alignment.Center) {
                Text((s.profileName ?: "?").take(1).uppercase(), color = t.buttonText, style = MaterialTheme.typography.titleSmall)
            }
        }
        IconButton(onClick = { nav.goTop(Routes.SETTINGS) }) { Icon(Icons.Rounded.Settings, stringResource(R.string.nav_settings), tint = t.onBackground) }
    }
}

@Composable
private fun HeroCarousel(items: List<HeroItem>, nav: Navigator) {
    val t = LocalEmby.current
    val pager = rememberPagerState { items.size }
    val cfgW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    // Banner a todo lo ancho, proporción cinematográfica.
    val cinema = t.heroStyle == com.embyverse.core.config.HeroStyle.CINEMA
    val centered = t.heroStyle == com.embyverse.core.config.HeroStyle.CENTERED
    // CINEMA (tipo Netflix/HBO): banner más alto, imagen dominante. CENTERED (tipo Apple TV): texto centrado.
    val height = when {
        t.device.isTv -> if (cinema) 470.dp else 380.dp
        cfgW >= 600 -> if (cinema) 420.dp else 340.dp
        else -> if (cinema) 440.dp else 300.dp
    }
    // Cada novedad dura 6.5 s (tiempo para leerla); la barra del indicador muestra cuánto falta.
    // Se avanza con un temporizador ligero (no una animación continua): menos trabajo para el TV.
    val progress = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // Se espera a que la página quede ASENTADA (settledPage): antes se reiniciaba a media transición
    // (currentPage cambia en la mitad del deslizamiento) y la novedad se quedaba partida a la mitad.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(pager.settledPage, items.size) {
        if (items.size < 2) return@LaunchedEffect
        val total = 6_500L; val step = 100L
        var elapsed = 0L
        progress.floatValue = 0f
        while (elapsed < total) {
            delay(step)
            if (pager.isScrollInProgress) { elapsed = 0L; progress.floatValue = 0f; continue } // el usuario está deslizando
            elapsed += step; progress.floatValue = elapsed / total.toFloat()
        }
        val next = (pager.settledPage + 1) % items.size
        // La animación corre en su propio ámbito: no la cancela el cambio de página a mitad de camino.
        scope.launch { pager.animateScrollToPage(next, animationSpec = androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    }
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(pager, modifier = Modifier.fillMaxSize()) { page ->
            val h = items[page]
            val open = { if (h.item != null) nav.openItem(h.item) else h.route?.let(nav::appRoute) ?: Unit }
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(t.secondary.copy(alpha = 0.55f), t.surface, t.primary.copy(alpha = 0.3f))))) {
                if (h.imageUrl != null) MediaImage(h.imageUrl, ContentKind.MOVIE, Modifier.fillMaxSize(), fallbackText = null)
                // Sin imagen panorámica: póster a la derecha, como en las apps de streaming.
                else if (h.posterUrl != null) Box(Modifier.align(Alignment.CenterEnd).padding(end = t.screenPadding + 8.dp, top = 24.dp)
                    .fillMaxHeight(0.78f).aspectRatio(2f / 3f).clip(RoundedCornerShape(t.cardRadius))) {
                    MediaImage(h.posterUrl, ContentKind.MOVIE, Modifier.fillMaxSize(), fallbackText = null)
                }
                // Degradados: oscurece la izquierda (texto) y funde abajo con el fondo de la app.
                if (!centered) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(t.background.copy(alpha = if (cinema) 0.8f else 0.92f), t.background.copy(alpha = if (cinema) 0.3f else 0.55f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient((if (cinema || centered) 0.35f else 0.45f) to Color.Transparent, 1f to t.background)))
                Column(
                    Modifier.align(if (centered) Alignment.BottomCenter else Alignment.BottomStart).padding(start = t.screenPadding, end = t.screenPadding, bottom = if (cinema) 40.dp else 28.dp)
                        .fillMaxWidth(if (centered) 0.8f else if (t.device.isTv || cfgW >= 600) 0.5f else 0.92f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                ) {
                    if (h.item != null) {
                        val kind = stringResource(if (h.item.kind == ContentKind.SERIES) R.string.hero_new_series else R.string.hero_new_movie)
                        val ago = h.addedAtEpochSeconds?.takeIf { it > 0 }?.let {
                            android.text.format.DateUtils.getRelativeTimeSpanString(it * 1000, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS).toString()
                        }
                        Text(listOfNotNull(if (h.isNew) kind else stringResource(R.string.hero_featured), ago).joinToString("  ·  ").uppercase(),
                            style = MaterialTheme.typography.labelLarge, color = t.primary)
                    }
                    Text(h.title, style = if (cinema) MaterialTheme.typography.displaySmall.copy(fontSize = MaterialTheme.typography.displaySmall.fontSize * 1.25f,
                            lineHeight = MaterialTheme.typography.displaySmall.lineHeight * 1.2f, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
                        else MaterialTheme.typography.displaySmall, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null)
                    h.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = t.onSurface, maxLines = if (centered) 2 else 3, overflow = TextOverflow.Ellipsis,
                        textAlign = if (centered) androidx.compose.ui.text.style.TextAlign.Center else null) }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (h.item != null) {
                            EmbyButton(stringResource(R.string.action_watch), onClick = { nav.play(h.item) }, icon = Icons.Rounded.PlayArrow)
                            EmbyButton(stringResource(R.string.action_more_info), onClick = { open() }, secondary = true)
                        } else if (h.route != null) EmbyButton(
                            stringResource(if (h.route == com.embyverse.core.config.AppRoute.SOURCES) R.string.action_add_source else R.string.action_open),
                            onClick = { open() }, icon = if (h.route == com.embyverse.core.config.AppRoute.SOURCES) Icons.Rounded.Add else null,
                        )
                    }
                }
            }
        }
        if (items.size > 1) Row(Modifier.align(Alignment.BottomEnd).padding(end = t.screenPadding, bottom = 32.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(items.size) { i ->
                val active = i == pager.currentPage
                Box(Modifier.size(width = if (active) 28.dp else 6.dp, height = 6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))) {
                    if (active) Box(Modifier.fillMaxHeight().fillMaxWidth(progress.floatValue.coerceIn(0.05f, 1f)).background(t.primary))
                }
            }
        }
    }
}

/** Banners promocionales: SOLO en la pantalla de inicio (nunca en el reproductor ni en las listas de canales). */
@Composable
private fun PromoRow(promos: List<Banner>, sec: SectionSpec, nav: Navigator) {
    val t = LocalEmby.current
    val w = cardWidth(CardAspect.LANDSCAPE, sec.size, t.device.isTv) * 1.3f
    LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding), horizontalArrangement = Arrangement.spacedBy(t.cardGap)) {
        items(promos, key = { it.id }) { b ->
            FocusableSurface(onClick = { b.route?.let(nav::appRoute) }, modifier = Modifier.width(w).height(w * 9f / 16f), shape = RoundedCornerShape(t.cardRadius)) { _ ->
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(t.secondary.copy(alpha = 0.6f), t.primary.copy(alpha = 0.35f))))) {
                    if (b.imageUrl != null) MediaImage(b.imageUrl, ContentKind.MOVIE, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        b.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}


/** Tarjetas de eventos de hoy: hora destacada, nombre del partido y logo del canal. OK = verlo. */
@Composable
private fun EventsRow(events: List<com.embyverse.app.data.repo.LiveEvent>, nav: Navigator) {
    val t = LocalEmby.current
    val w = if (t.device.isTv) 300.dp else 240.dp
    LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(t.cardGap)) {
        items(events, key = { "${it.channel.sourceId}|${it.channel.providerId}" }) { e ->
            FocusableSurface(onClick = { nav.play(com.embyverse.app.data.repo.ItemRef(ContentKind.LIVE, e.channel.sourceId, e.channel.providerId)) },
                modifier = Modifier.width(w).height(w * 0.48f).testTag("event"), shape = RoundedCornerShape(t.cardRadius)) { focused ->
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(t.secondary.copy(alpha = 0.55f), t.surface, t.primary.copy(alpha = if (focused) 0.45f else 0.2f))))
                    .padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        e.timeLabel?.let {
                            Text(it.uppercase(), style = MaterialTheme.typography.labelLarge, color = t.buttonText,
                                modifier = Modifier.background(t.primary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.events_live_badge), style = MaterialTheme.typography.labelSmall, color = t.accent)
                    }
                    Text(e.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.align(Alignment.CenterStart).padding(top = 18.dp, end = 50.dp))
                    Row(Modifier.align(Alignment.BottomStart), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 36.dp, height = 22.dp)) { MediaImage(e.channel.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit) }
                        Spacer(Modifier.width(6.dp))
                        Text(e.channel.category ?: "", style = MaterialTheme.typography.labelSmall, color = t.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}


/** Aviso de vencimiento de la cuenta: con tiempo para renovar con el proveedor o vendedor. */
@Composable
private fun ExpiryNotice(exp: Long, days: Int) {
    val t = LocalEmby.current
    val urgent = days <= 3
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(exp * 1000))
    Row(Modifier.fillMaxWidth().padding(horizontal = t.screenPadding)
        .background((if (urgent) t.error else t.primary).copy(alpha = 0.16f), RoundedCornerShape(t.cardRadius)).padding(14.dp).testTag("expiry"),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.EventBusy, null, tint = if (urgent) t.error else t.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(when {
                days < 0 -> stringResource(R.string.expiry_expired, date)
                days == 0 -> stringResource(R.string.expiry_today)
                days == 1 -> stringResource(R.string.expiry_tomorrow, date)
                else -> stringResource(R.string.expiry_in_days, days, date)
            }, style = MaterialTheme.typography.titleSmall, color = t.onBackground)
            Text(stringResource(R.string.expiry_renew), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
        }
    }
}

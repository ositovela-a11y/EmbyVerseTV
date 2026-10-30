package com.embyverse.app.ui.screens.home

import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
        if (!s.hasSources) item(key = "empty") {
            MessageState(Icons.Rounded.SatelliteAlt, stringResource(R.string.home_no_sources_title),
                s.config.text("home.emptySources", stringResource(R.string.home_no_sources_message)),
                stringResource(R.string.action_add_source), { nav.go(Routes.sourceEdit()) })
        }
        s.sections.forEach { sec ->
            val type = sec.type ?: return@forEach
            when (type) {
                SectionType.HERO_BANNER -> if (s.hero.isNotEmpty()) item(key = sec.id) { HeroCarousel(s.hero, nav) }
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
                            } else CardRow(cards, sec.aspect, sec.size) { nav.openItem(it.ref, seriesId = it.seriesId) }
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
    val height = when { t.device.isTv -> 380.dp; cfgW >= 600 -> 340.dp; else -> 300.dp }
    LaunchedEffect(items.size) {
        while (items.size > 1) {
            delay(8_000)
            if (!pager.isScrollInProgress) pager.animateScrollToPage((pager.currentPage + 1) % items.size)
        }
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
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(t.background.copy(alpha = 0.92f), t.background.copy(alpha = 0.55f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to t.background)))
                Column(
                    Modifier.align(Alignment.BottomStart).padding(start = t.screenPadding, end = t.screenPadding, bottom = 28.dp)
                        .fillMaxWidth(if (t.device.isTv || cfgW >= 600) 0.5f else 0.92f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (h.item != null) {
                        val kind = stringResource(if (h.item.kind == ContentKind.SERIES) R.string.hero_new_series else R.string.hero_new_movie)
                        val ago = h.addedAtEpochSeconds?.takeIf { it > 0 }?.let {
                            android.text.format.DateUtils.getRelativeTimeSpanString(it * 1000, System.currentTimeMillis(), android.text.format.DateUtils.DAY_IN_MILLIS).toString()
                        }
                        Text(listOfNotNull(if (h.isNew) kind else stringResource(R.string.hero_featured), ago).joinToString("  ·  ").uppercase(),
                            style = MaterialTheme.typography.labelLarge, color = t.primary)
                    }
                    Text(h.title, style = MaterialTheme.typography.displaySmall, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    h.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = t.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis) }
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
                Box(Modifier.size(width = if (i == pager.currentPage) 22.dp else 6.dp, height = 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) t.primary else Color.White.copy(alpha = 0.35f)))
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

package com.embyverse.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.embyverse.app.R
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.player.PlayerActivity
import com.embyverse.app.ui.components.BrandLogo
import com.embyverse.app.ui.screens.detail.MovieDetailScreen
import com.embyverse.app.ui.screens.detail.SeriesDetailScreen
import com.embyverse.app.ui.screens.home.HomeScreen
import com.embyverse.app.ui.screens.library.FavoritesScreen
import com.embyverse.app.ui.screens.library.LiveScreen
import com.embyverse.app.ui.screens.library.MoviesScreen
import com.embyverse.app.ui.screens.library.SeriesScreen
import com.embyverse.app.ui.screens.search.SearchScreen
import com.embyverse.app.ui.screens.settings.AboutScreen
import com.embyverse.app.ui.screens.settings.AppearanceScreen
import com.embyverse.app.ui.screens.settings.PlaybackSettingsScreen
import com.embyverse.app.ui.screens.settings.PrivacyScreen
import com.embyverse.app.ui.screens.settings.ProfilesScreen
import com.embyverse.app.ui.screens.settings.SettingsScreen
import com.embyverse.app.ui.screens.sources.SourceEditScreen
import com.embyverse.app.ui.screens.sources.SourcesScreen
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.AppRoute
import com.embyverse.core.config.LogoVariant
import com.embyverse.core.config.NavStyle
import com.embyverse.core.model.ContentKind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag

object Routes {
    const val HOME = "home"; const val LIVE = "live"; const val MOVIES = "movies"; const val SERIES = "series"
    const val SEARCH = "search"; const val SETTINGS = "settings"; const val SOURCES = "sources"; const val FAVORITES = "favorites"
    const val SOURCE_EDIT = "source_edit?id={id}&first={first}"
    const val MOVIE = "movie/{sid}/{id}"; const val SERIES_DETAIL = "series_detail/{sid}/{id}"
    const val APPEARANCE = "appearance"; const val PLAYBACK = "playback"; const val ABOUT = "about"; const val PRIVACY = "privacy"; const val PROFILES = "profiles"; const val PARENTAL = "parental"; const val GUIDE = "guide"

    fun sourceEdit(id: String? = null, first: Boolean = false) = "source_edit?id=${id?.let(Uri::encode) ?: ""}&first=$first"
    fun movie(sid: String, id: String) = "movie/${Uri.encode(sid)}/${Uri.encode(id)}"
    fun seriesDetail(sid: String, id: String) = "series_detail/${Uri.encode(sid)}/${Uri.encode(id)}"
    fun live(category: String? = null) = if (category == null) LIVE else "$LIVE?category=${Uri.encode(category)}"

    fun forAppRoute(r: AppRoute) = when (r) {
        AppRoute.HOME -> HOME; AppRoute.LIVE -> LIVE; AppRoute.MOVIES -> MOVIES; AppRoute.SERIES -> SERIES
        AppRoute.SEARCH -> SEARCH; AppRoute.FAVORITES -> FAVORITES; AppRoute.SETTINGS -> SETTINGS; AppRoute.SOURCES -> SOURCES
    }
}

private data class TopDest(val route: String, val label: Int, val icon: ImageVector)
private val TOP = listOf(
    TopDest(Routes.HOME, R.string.nav_home, Icons.Rounded.Home),
    TopDest(Routes.LIVE, R.string.nav_live, Icons.Rounded.LiveTv),
    TopDest(Routes.MOVIES, R.string.nav_movies, Icons.Rounded.Theaters),
    TopDest(Routes.SERIES, R.string.nav_series, Icons.Rounded.VideoLibrary),
    TopDest(Routes.SEARCH, R.string.nav_search, Icons.Rounded.Search),
    TopDest(Routes.SETTINGS, R.string.nav_settings, Icons.Rounded.Settings),
)

/** Acciones de navegación comunes que reciben las pantallas. */
class Navigator(private val nav: NavHostController, private val openPlayer: (ItemRef, String?) -> Unit) {
    fun go(route: String) = nav.navigate(route) { launchSingleTop = true }
    fun goTop(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true; restoreState = true
    }
    fun back() { nav.popBackStack() }
    fun openItem(ref: ItemRef, context: String? = null, seriesId: String? = null) = when (ref.kind) {
        ContentKind.MOVIE -> go(Routes.movie(ref.sourceId, ref.id))
        ContentKind.SERIES -> go(Routes.seriesDetail(ref.sourceId, ref.id))
        ContentKind.EPISODE -> if (seriesId != null) go(Routes.seriesDetail(ref.sourceId, seriesId)) else play(ref)
        ContentKind.LIVE -> play(ref, context)
    }
    fun play(ref: ItemRef, context: String? = null) = openPlayer(ref, context)
    fun appRoute(r: AppRoute) = if (r == AppRoute.SOURCES) go(Routes.SOURCES) else goTop(Routes.forAppRoute(r))
}

@Composable
fun AppShell(startOnboarding: Boolean, logoVariant: LogoVariant, logoUrl: String?) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val navigator = remember(nav, context) { Navigator(nav) { ref, ctx -> PlayerActivity.start(context, ref, ctx) } }
    val t = LocalEmby.current
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route?.substringBefore('?')
    val showNav = current in TOP.map { it.route }
    // En TV el menú lateral es el normal; algunos estilos de plataforma usan pestañas arriba.
    val style = if (t.device.useRail && !(t.tvNavFromTheme && t.navStyle == NavStyle.TOP_TABS)) NavStyle.SIDE_RAIL else t.navStyle

    val host: @Composable (Modifier) -> Unit = { m ->
        NavHost(nav, startDestination = if (startOnboarding) Routes.SOURCE_EDIT else Routes.HOME, modifier = m,
            // Cambio de sección "de plataforma": la nueva entra con un ligero acercamiento y fundido.
            enterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280, delayMillis = 60)) +
                androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(340, easing = androidx.compose.animation.core.FastOutSlowInEasing), initialScale = 0.94f) },
            exitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) },
            popEnterTransition = { androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(240)) },
            popExitTransition = { androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(200), targetScale = 0.97f) },
        ) {
            composable(Routes.HOME) { HomeScreen(navigator) }
            composable("${Routes.LIVE}?category={category}", arguments = listOf(navArgument("category") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                LiveScreen(navigator, it.arguments?.getString("category"))
            }
            composable(Routes.MOVIES) { MoviesScreen(navigator) }
            composable(Routes.SERIES) { SeriesScreen(navigator) }
            composable(Routes.SEARCH) { SearchScreen(navigator) }
            composable(Routes.FAVORITES) { FavoritesScreen(navigator) }
            composable(Routes.SETTINGS) { SettingsScreen(navigator) }
            composable(Routes.SOURCES) { SourcesScreen(navigator) }
            composable(Routes.SOURCE_EDIT, arguments = listOf(
                navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("first") { type = NavType.BoolType; defaultValue = startOnboarding },
            )) {
                val first = it.arguments?.getBoolean("first") ?: false
                SourceEditScreen(navigator, it.arguments?.getString("id")?.ifBlank { null }, first) {
                    if (first) nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true } } else nav.popBackStack()
                }
            }
            composable(Routes.MOVIE) { MovieDetailScreen(navigator, Uri.decode(it.arguments?.getString("sid")!!), Uri.decode(it.arguments?.getString("id")!!)) }
            composable(Routes.SERIES_DETAIL) { SeriesDetailScreen(navigator, Uri.decode(it.arguments?.getString("sid")!!), Uri.decode(it.arguments?.getString("id")!!)) }
            composable(Routes.APPEARANCE) { AppearanceScreen(navigator) }
            composable(Routes.PLAYBACK) { PlaybackSettingsScreen(navigator) }
            composable(Routes.ABOUT) { AboutScreen(navigator) }
            composable(Routes.PRIVACY) { PrivacyScreen(navigator) }
            composable(Routes.PROFILES) { ProfilesScreen(navigator) }
            composable(Routes.PARENTAL) { com.embyverse.app.ui.screens.settings.ParentalScreen(navigator) }
            composable(Routes.GUIDE) { com.embyverse.app.ui.screens.guide.GuideScreen(navigator) }
        }
    }

    // IMPORTANTE: el NavHost se compone SIEMPRE en el mismo lugar. Antes cambiaba de rama al
    // ocultar el menú (fichas de película/serie, ajustes…), Compose lo recreaba y la navegación
    // volvía al inicio: por eso películas y series "no abrían". Ahora solo se muestran u ocultan
    // las barras alrededor del contenido.
    val rail = showNav && style == NavStyle.SIDE_RAIL
    val tabs = showNav && style == NavStyle.TOP_TABS
    val bottom = showNav && style != NavStyle.SIDE_RAIL && style != NavStyle.TOP_TABS
    Row(Modifier.fillMaxSize()) {
        if (rail) NavigationRail(containerColor = t.surface.copy(alpha = 0.94f), modifier = Modifier.statusBarsPadding(), header = {
            BrandLogo(if (logoVariant == LogoVariant.ASSET) logoVariant else LogoVariant.BUNDLED_PLANET, logoUrl, Modifier.padding(top = 12.dp).size(52.dp))
            Spacer(Modifier.height(12.dp))
        }) {
            TOP.forEach { d ->
                NavigationRailItem(
                    modifier = Modifier.testTag("nav_${d.route}").focusHighlight(t.focusRing, t.primary),
                    selected = current == d.route, onClick = { navigator.goTop(d.route) },
                    icon = { Icon(d.icon, null) }, label = if (t.navShowLabels) ({ Text(stringResource(d.label)) }) else null,
                    colors = NavigationRailItemDefaults.colors(selectedIconColor = t.buttonText, indicatorColor = t.primary,
                        unselectedIconColor = t.textSecondary, selectedTextColor = t.onBackground, unselectedTextColor = t.textSecondary),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxSize()) {
            if (tabs) {
                // Barra superior estilo plataforma: logotipo + secciones en texto con subrayado.
                Row(Modifier.fillMaxWidth().statusBarsPadding().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(t.background, t.background.copy(alpha = 0.6f))))
                    .padding(horizontal = t.screenPadding, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandLogo(logoVariant, logoUrl, Modifier.height(if (t.device.isTv) 40.dp else 32.dp).width(if (t.device.isTv) 110.dp else 84.dp))
                    Spacer(Modifier.width(if (t.device.isTv) 28.dp else 10.dp))
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(if (t.device.isTv) 10.dp else 2.dp)) {
                        items(TOP.size) { i ->
                            val d = TOP[i]
                            val sel = current == d.route
                            com.embyverse.app.ui.components.FocusableSurface(onClick = { navigator.goTop(d.route) }, focusScale = 1.05f,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(50), modifier = Modifier.testTag("nav_${d.route}")) { focused ->
                                Column(Modifier.background(if (focused) t.onBackground.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(stringResource(d.label), maxLines = 1, style = MaterialTheme.typography.titleSmall,
                                        color = if (sel || focused) t.onBackground else t.textSecondary,
                                        fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.Bold else null)
                                    Box(Modifier.padding(top = 3.dp).width(if (sel) 18.dp else 0.dp).height(3.dp)
                                        .background(t.primary, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { host(Modifier.fillMaxSize()) }
            if (bottom) NavigationBar(containerColor = t.surface.copy(alpha = 0.97f), modifier = Modifier.navigationBarsPadding(), windowInsets = androidx.compose.foundation.layout.WindowInsets(0)) {
                TOP.forEach { d ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("nav_${d.route}").focusHighlight(t.focusRing, t.primary),
                        selected = current == d.route, onClick = { navigator.goTop(d.route) },
                        icon = { Icon(d.icon, null) }, label = if (t.navShowLabels) ({ Text(stringResource(d.label), maxLines = 1) }) else null,
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = t.buttonText, indicatorColor = t.primary,
                            unselectedIconColor = t.textSecondary, selectedTextColor = t.onBackground, unselectedTextColor = t.textSecondary),
                    )
                }
            }
        }
    }
}

/** Resalta con fuerza el elemento del menú que tiene el foco del mando (antes apenas se notaba). */
private fun Modifier.focusHighlight(ring: androidx.compose.ui.graphics.Color, fill: androidx.compose.ui.graphics.Color): Modifier = composed {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    this.onFocusChanged { focused = it.hasFocus || it.isFocused }
        .then(if (focused) Modifier.background(fill.copy(alpha = 0.22f), androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .border(2.5.dp, ring, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)) else Modifier)
}

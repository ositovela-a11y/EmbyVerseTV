package com.embyverse.core.config

/**
 * Configuración visual remota, esquema v1. Es DATOS, nunca código: la app sólo la usa para
 * elegir entre componentes nativos ya compilados (catálogo seguro) y sus parámetros acotados.
 * Especificación completa: docs/remote-config.md. Debe mantenerse en sincronía con
 * backend/src/config/schema.ts (ambos se prueban contra tests/fixtures/config).
 */
data class RemoteConfig(
    val schemaVersion: Int = 1,
    val version: Int = 0,
    val publishedAt: String? = null,
    val minAppVersionCode: Int = 1,
    val brand: Brand = Brand(),
    val theme: ThemeSpec = ThemeSpec(),
    val themes: List<NamedTheme> = DEFAULT_THEMES,
    val userCustomization: UserCustomization = UserCustomization(),
    val sections: List<SectionSpec> = DEFAULT_SECTIONS,
    val banners: List<Banner> = emptyList(),
    val texts: Map<String, String> = emptyMap(),
) {
    fun text(key: String, fallback: String) = texts[key] ?: fallback

    companion object {
        const val SUPPORTED_SCHEMA = 1
        val DEFAULT = RemoteConfig()
    }
}

enum class LogoVariant(val wire: String) { BUNDLED_FULL("bundled_full"), BUNDLED_PLANET("bundled_planet"), ASSET("asset") }

data class Brand(
    val appName: String = "EmbyVerse",
    val logoVariant: LogoVariant = LogoVariant.BUNDLED_FULL,
    val logoUrl: String? = null,
    val tagline: String? = null,
)

/** Colores como ARGB empaquetado (0xAARRGGBB). */
data class Palette(
    val primary: Int = 0xFF7BE52F.toInt(),
    val secondary: Int = 0xFF1E6BFF.toInt(),
    val accent: Int = 0xFF22D3EE.toInt(),
    val background: Int = 0xFF03070F.toInt(),
    val surface: Int = 0xFF0A1324.toInt(),
    val surfaceVariant: Int = 0xFF13213B.toInt(),
    val onBackground: Int = 0xFFEAF2FF.toInt(),
    val onSurface: Int = 0xFFDCE6F5.toInt(),
    val textSecondary: Int = 0xFF8FA3BF.toInt(),
    val onPrimary: Int = 0xFF04110A.toInt(),
    val buttonBackground: Int = 0xFF7BE52F.toInt(),
    val buttonText: Int = 0xFF04110A.toInt(),
    val focusRing: Int = 0xFF22D3EE.toInt(),
    val error: Int = 0xFFFF5A6E.toInt(),
) {
    fun with(key: String, color: Int): Palette = when (key) {
        "primary" -> copy(primary = color); "secondary" -> copy(secondary = color); "accent" -> copy(accent = color)
        "background" -> copy(background = color); "surface" -> copy(surface = color); "surfaceVariant" -> copy(surfaceVariant = color)
        "onBackground" -> copy(onBackground = color); "onSurface" -> copy(onSurface = color); "textSecondary" -> copy(textSecondary = color)
        "onPrimary" -> copy(onPrimary = color); "buttonBackground" -> copy(buttonBackground = color); "buttonText" -> copy(buttonText = color)
        "focusRing" -> copy(focusRing = color); "error" -> copy(error = color)
        else -> this
    }

    fun merge(partial: Map<String, Int>): Palette = partial.entries.fold(this) { p, (k, v) -> p.with(k, v) }

    companion object {
        val KEYS = listOf("primary", "secondary", "accent", "background", "surface", "surfaceVariant", "onBackground",
            "onSurface", "textSecondary", "onPrimary", "buttonBackground", "buttonText", "focusRing", "error")
    }
}

enum class BackgroundStyle { SOLID, GRADIENT, NEBULA }
enum class FontChoice(val wire: String) { SYSTEM("system"), INTER("inter"), POPPINS("poppins") }
enum class ButtonStyle { FILLED, TONAL, OUTLINED }
enum class NavStyle(val wire: String) { BOTTOM_BAR("bottom_bar"), SIDE_RAIL("side_rail"), TOP_TABS("top_tabs") }

data class ThemeSpec(
    val palette: Palette = Palette(),
    val backgroundStyle: BackgroundStyle = BackgroundStyle.NEBULA,
    val gradientStart: Int = 0xFF03070F.toInt(),
    val gradientEnd: Int = 0xFF071A33.toInt(),
    val font: FontChoice = FontChoice.POPPINS,
    val fontScale: Float = 1f,
    val cardRadius: Int = 14,
    val buttonRadius: Int = 24,
    val bannerRadius: Int = 20,
    val screenPadding: Int = 20,
    val sectionGap: Int = 26,
    val cardGap: Int = 12,
    val glowEnabled: Boolean = true,
    val glowIntensity: Float = 0.35f,
    val buttonStyle: ButtonStyle = ButtonStyle.FILLED,
    val navStyle: NavStyle = NavStyle.BOTTOM_BAR,
    val navShowLabels: Boolean = true,
)

data class NamedTheme(val id: String, val name: String, val palette: Map<String, Int>)

data class UserCustomization(
    val allowThemeSelection: Boolean = true,
    val allowAccentColor: Boolean = true,
    val accentChoices: List<Int> = listOf(0xFF7BE52F, 0xFF22D3EE, 0xFF1E6BFF, 0xFFA855F7, 0xFFF59E0B, 0xFFF43F5E).map { it.toInt() },
    val allowCardSize: Boolean = true,
    val allowHideSections: Boolean = true,
    val allowReorderSections: Boolean = true,
    val lockedSections: Set<String> = setOf("hero"),
)

/** Catálogo cerrado de módulos nativos de la pantalla de inicio. */
enum class SectionType(val wire: String) {
    HERO_BANNER("hero_banner"), PROMO_BANNERS("promo_banners"), CONTINUE_WATCHING("continue_watching"),
    LIVE_TV("live_tv"), MOVIES("movies"), SERIES("series"), RECENTLY_ADDED("recently_added"),
    FAVORITES("favorites"), RECOMMENDED("recommended");

    companion object { fun fromWire(s: String) = entries.firstOrNull { it.wire == s } }
}

enum class SectionLayout(val wire: String) { HERO("hero"), CAROUSEL("carousel"), GRID("grid") }
enum class CardAspect(val wire: String, val ratio: Float) { POSTER("poster", 2f / 3f), LANDSCAPE("landscape", 16f / 9f), SQUARE("square", 1f) }
enum class CardSize(val wire: String, val scale: Float) { S("s", 0.8f), M("m", 1f), L("l", 1.3f) }

data class SectionSpec(
    val id: String,
    /** Valor tal cual llegó; si [type] es null la app es más antigua que el panel y omite la sección. */
    val rawType: String,
    val type: SectionType?,
    val enabled: Boolean = true,
    val title: String = "",
    val subtitle: String? = null,
    val layout: SectionLayout = SectionLayout.CAROUSEL,
    val aspect: CardAspect = CardAspect.POSTER,
    val size: CardSize = CardSize.M,
    val maxItems: Int = 20,
)

/** Rutas internas autorizadas para banners. No se admiten URLs externas como destino. */
enum class AppRoute(val wire: String) {
    HOME("home"), LIVE("live"), MOVIES("movies"), SERIES("series"), SEARCH("search"),
    FAVORITES("favorites"), SETTINGS("settings"), SOURCES("sources");
    companion object { fun fromWire(s: String) = entries.firstOrNull { it.wire == s } }
}

enum class BannerPlacement(val wire: String) { HERO("hero"), PROMO("promo") }

data class Banner(
    val id: String,
    val placement: BannerPlacement,
    val title: String,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val route: AppRoute? = null,
    val startsAtEpoch: Long? = null,
    val endsAtEpoch: Long? = null,
) {
    fun isActive(nowEpoch: Long) = (startsAtEpoch == null || nowEpoch >= startsAtEpoch) && (endsAtEpoch == null || nowEpoch < endsAtEpoch)
}

val DEFAULT_THEMES = listOf(NamedTheme("embyverse", "EmbyVerse (original)", emptyMap()))

val DEFAULT_SECTIONS = listOf(
    SectionSpec("hero", "hero_banner", SectionType.HERO_BANNER, title = "Destacado", layout = SectionLayout.HERO, aspect = CardAspect.LANDSCAPE, size = CardSize.L, maxItems = 5),
    SectionSpec("promos", "promo_banners", SectionType.PROMO_BANNERS, title = "Novedades EmbyVerse", aspect = CardAspect.LANDSCAPE, maxItems = 6),
    SectionSpec("continue", "continue_watching", SectionType.CONTINUE_WATCHING, title = "Continuar viendo", aspect = CardAspect.LANDSCAPE),
    SectionSpec("live", "live_tv", SectionType.LIVE_TV, title = "TV EN VIVO", aspect = CardAspect.LANDSCAPE, size = CardSize.S, maxItems = 30),
    SectionSpec("movies", "movies", SectionType.MOVIES, title = "PELÍCULAS", maxItems = 30),
    SectionSpec("series", "series", SectionType.SERIES, title = "SERIES", maxItems = 30),
    SectionSpec("new", "recently_added", SectionType.RECENTLY_ADDED, title = "Novedades"),
    SectionSpec("favorites", "favorites", SectionType.FAVORITES, title = "Favoritos", maxItems = 30),
    SectionSpec("recommended", "recommended", SectionType.RECOMMENDED, title = "Recomendado para ti", layout = SectionLayout.GRID, size = CardSize.S, maxItems = 12),
)

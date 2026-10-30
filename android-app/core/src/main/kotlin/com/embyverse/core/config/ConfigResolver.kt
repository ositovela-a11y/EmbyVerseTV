package com.embyverse.core.config

/**
 * Preferencias de apariencia de UN perfil local. Se guardan en el dispositivo (DataStore) y
 * nunca se envían al servidor ni afectan a otros perfiles o usuarios.
 */
data class UserAppearance(
    val themeId: String? = null,
    val accentColor: Int? = null,
    val cardSize: CardSize? = null,
    val hiddenSections: Set<String> = emptySet(),
    val sectionOrder: List<String> = emptyList(),
) {
    companion object { val ORIGINAL = UserAppearance() }
}

/**
 * Combina la configuración del administrador con las preferencias del usuario, respetando
 * siempre los permisos y bloqueos del administrador. Misma lógica que admin-web/src/resolve.ts
 * (la vista previa del panel), probada en ConfigResolverTest y en resolve.test.ts.
 */
object ConfigResolver {

    fun theme(config: RemoteConfig, prefs: UserAppearance): ThemeSpec {
        val uc = config.userCustomization
        // Tema de estilo completo (Cristal, Cine Rojo…): cambia colores, fondo, esquinas y botones.
        val style = if (uc.allowThemeSelection) STYLE_THEMES.firstOrNull { it.id == prefs.themeId } else null
        val base = style?.transform?.invoke(config.theme) ?: config.theme
        var palette = base.palette
        if (style == null && uc.allowThemeSelection && prefs.themeId != null) {
            config.themes.firstOrNull { it.id == prefs.themeId }?.let { palette = palette.merge(it.palette) }
        }
        val accent = prefs.accentColor
        if (uc.allowAccentColor && accent != null && accent in uc.accentChoices) {
            palette = palette.copy(primary = accent, buttonBackground = accent, focusRing = accent,
                onPrimary = readableOn(accent), buttonText = readableOn(accent))
        }
        return base.copy(palette = palette)
    }

    fun sections(config: RemoteConfig, prefs: UserAppearance): List<SectionSpec> {
        val uc = config.userCustomization
        val adminList = config.sections.filter { it.enabled && it.type != null }
        val locked = uc.lockedSections

        val visible = adminList.filter { s ->
            s.id in locked || !uc.allowHideSections || s.id !in prefs.hiddenSections
        }
        val ordered = if (uc.allowReorderSections && prefs.sectionOrder.isNotEmpty()) {
            val movable = visible.filter { it.id !in locked }
            val rank = prefs.sectionOrder.withIndex().associate { (i, id) -> id to i }
            val sortedMovable = movable.sortedWith(compareBy<SectionSpec> { rank[it.id] ?: Int.MAX_VALUE }
                .thenBy { adminList.indexOf(it) }).iterator()
            // Las secciones bloqueadas mantienen su posición; las demás ocupan los huecos en el orden del usuario.
            visible.map { if (it.id in locked) it else sortedMovable.next() }
        } else visible

        return if (uc.allowCardSize && prefs.cardSize != null) ordered.map {
            if (it.layout == SectionLayout.HERO) it else it.copy(size = prefs.cardSize)
        } else ordered
    }

    fun banners(config: RemoteConfig, placement: BannerPlacement, nowEpoch: Long): List<Banner> =
        config.banners.filter { it.placement == placement && it.isActive(nowEpoch) }

    /** Negro o blanco según el contraste (luminancia relativa WCAG). */
    fun readableOn(argb: Int): Int {
        fun ch(v: Int): Double { val c = v / 255.0; return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * ch((argb shr 16) and 0xFF) + 0.7152 * ch((argb shr 8) and 0xFF) + 0.0722 * ch(argb and 0xFF)
        return if (l > 0.179) 0xFF04110A.toInt() else 0xFFFFFFFF.toInt()
    }
}

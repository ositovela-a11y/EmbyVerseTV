package com.embyverse.core.config

/**
 * Temas de estilo completos que cada cliente puede elegir en su perfil (Ajustes › Apariencia).
 * Inspirados en el ambiente de las grandes plataformas, pero con colores, nombres y formas propios:
 * no reproducen la marca, el logotipo ni los colores exactos de ninguna.
 * A diferencia de los temas del panel (solo colores), estos cambian también fondo, esquinas,
 * botones y brillo.
 */
data class StyleTheme(
    val id: String,
    val name: String,
    /** Frase corta que describe el estilo (se muestra en la tarjeta del tema). */
    val mood: String,
    val transform: (ThemeSpec) -> ThemeSpec,
)

private fun c(hex: Long) = hex.toInt()

val STYLE_THEMES: List<StyleTheme> = listOf(
    // Ambiente "Apple TV": negro grafito, tarjetas grises, botones claros, esquinas suaves, sin brillos.
    StyleTheme("style_cristal", "Cristal", "Grafito elegante y minimalista") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFFF2F2F7), secondary = c(0xFF3A3A3F), accent = c(0xFF4DA3FF),
                background = c(0xFF050506), surface = c(0xFF18181B), surfaceVariant = c(0xFF2A2A2F),
                onBackground = c(0xFFF5F5F7), onSurface = c(0xFFE5E5EA), textSecondary = c(0xFF9A9AA2),
                onPrimary = c(0xFF0A0A0B), buttonBackground = c(0xFFF2F2F7), buttonText = c(0xFF0A0A0B), focusRing = c(0xFFFFFFFF)),
            backgroundStyle = BackgroundStyle.GRADIENT, gradientStart = c(0xFF050506), gradientEnd = c(0xFF1A1A1E),
            cardRadius = 16, buttonRadius = 12, bannerRadius = 22, glowEnabled = false, buttonStyle = ButtonStyle.FILLED,
        )
    },
    // Ambiente "Netflix": negro profundo y rojo cine, esquinas rectas.
    StyleTheme("style_cine_rojo", "Cine Rojo", "Negro profundo con rojo de cine") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFFE3262F), secondary = c(0xFF8A1218), accent = c(0xFFFF5A5F),
                background = c(0xFF0A0A0A), surface = c(0xFF161616), surfaceVariant = c(0xFF242424),
                onBackground = c(0xFFF7F7F7), onSurface = c(0xFFE6E6E6), textSecondary = c(0xFFA3A3A3),
                onPrimary = c(0xFFFFFFFF), buttonBackground = c(0xFFE3262F), buttonText = c(0xFFFFFFFF), focusRing = c(0xFFFFFFFF)),
            backgroundStyle = BackgroundStyle.SOLID, cardRadius = 5, buttonRadius = 5, bannerRadius = 6, glowEnabled = false,
        )
    },
    // Ambiente "HBO Max": degradado violeta-azul nocturno con brillo.
    StyleTheme("style_purpura", "Púrpura Premium", "Violeta nocturno con brillo") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFF9D5CFF), secondary = c(0xFF3D2BD9), accent = c(0xFF5EC8FF),
                background = c(0xFF07031A), surface = c(0xFF140B33), surfaceVariant = c(0xFF22154D),
                onBackground = c(0xFFF3EEFF), onSurface = c(0xFFE2D9FF), textSecondary = c(0xFFA699CC),
                onPrimary = c(0xFFFFFFFF), buttonBackground = c(0xFF9D5CFF), buttonText = c(0xFFFFFFFF), focusRing = c(0xFF5EC8FF)),
            backgroundStyle = BackgroundStyle.NEBULA, gradientStart = c(0xFF07031A), gradientEnd = c(0xFF1C0B4F),
            cardRadius = 10, buttonRadius = 22, glowEnabled = true, glowIntensity = 0.5f,
        )
    },
    // Ambiente "Disney+": azul marino mágico con destellos celestes.
    StyleTheme("style_azul_magico", "Azul Mágico", "Azul marino con destellos") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFF3EC5F5), secondary = c(0xFF1B3A8C), accent = c(0xFFB9E9FF),
                background = c(0xFF081028), surface = c(0xFF111C3D), surfaceVariant = c(0xFF1B2A55),
                onBackground = c(0xFFF0F6FF), onSurface = c(0xFFDCE7FA), textSecondary = c(0xFF93A6CC),
                onPrimary = c(0xFF04121E), buttonBackground = c(0xFFF0F6FF), buttonText = c(0xFF0A1530), focusRing = c(0xFFFFFFFF)),
            backgroundStyle = BackgroundStyle.GRADIENT, gradientStart = c(0xFF081028), gradientEnd = c(0xFF1A2F6B),
            cardRadius = 12, buttonRadius = 8, glowEnabled = true, glowIntensity = 0.3f,
        )
    },
    // Ambiente "Prime Video": azul petróleo y celeste.
    StyleTheme("style_oceano", "Océano", "Azul petróleo con celeste") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFF1AA7EC), secondary = c(0xFF0E4D6E), accent = c(0xFF7AD3FF),
                background = c(0xFF0D161E), surface = c(0xFF17222D), surfaceVariant = c(0xFF22313F),
                onBackground = c(0xFFF2F7FA), onSurface = c(0xFFDDE7EE), textSecondary = c(0xFF8DA2B3),
                onPrimary = c(0xFFFFFFFF), buttonBackground = c(0xFF1AA7EC), buttonText = c(0xFFFFFFFF), focusRing = c(0xFFFFFFFF)),
            backgroundStyle = BackgroundStyle.SOLID, cardRadius = 8, buttonRadius = 6, glowEnabled = false,
        )
    },
    // Ambiente "Paramount+": azul intenso estelar.
    StyleTheme("style_estelar", "Estelar", "Azul intenso de estrellas") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFF2F6BFF), secondary = c(0xFF0B2A80), accent = c(0xFF8FB4FF),
                background = c(0xFF030A1C), surface = c(0xFF0B1735), surfaceVariant = c(0xFF14234A),
                onBackground = c(0xFFF1F5FF), onSurface = c(0xFFDCE4FA), textSecondary = c(0xFF8E9CC2),
                onPrimary = c(0xFFFFFFFF), buttonBackground = c(0xFF2F6BFF), buttonText = c(0xFFFFFFFF), focusRing = c(0xFF8FB4FF)),
            backgroundStyle = BackgroundStyle.NEBULA, gradientStart = c(0xFF030A1C), gradientEnd = c(0xFF0A1F5C),
            cardRadius = 12, buttonRadius = 24, glowEnabled = true, glowIntensity = 0.4f,
        )
    },
    // Ambiente "Hulu": negro carbón con verde neón.
    StyleTheme("style_verde_neon", "Verde Neón", "Carbón con verde brillante") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFF2BE38A), secondary = c(0xFF136B45), accent = c(0xFF9CFFCF),
                background = c(0xFF0B0D0F), surface = c(0xFF15181C), surfaceVariant = c(0xFF20252B),
                onBackground = c(0xFFF4F7F5), onSurface = c(0xFFE0E6E2), textSecondary = c(0xFF95A39B),
                onPrimary = c(0xFF04140B), buttonBackground = c(0xFF2BE38A), buttonText = c(0xFF04140B), focusRing = c(0xFF2BE38A)),
            backgroundStyle = BackgroundStyle.SOLID, cardRadius = 10, buttonRadius = 10, glowEnabled = false,
        )
    },
    // Ambiente "Crunchyroll": oscuro con naranja vibrante.
    StyleTheme("style_naranja", "Naranja Vibrante", "Oscuro con naranja energético") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFFFF7A1A), secondary = c(0xFF8C3B06), accent = c(0xFFFFB774),
                background = c(0xFF0C0A09), surface = c(0xFF1A1613), surfaceVariant = c(0xFF2A231E),
                onBackground = c(0xFFFFF7F0), onSurface = c(0xFFF0E4DA), textSecondary = c(0xFFAD9E92),
                onPrimary = c(0xFF1A0B00), buttonBackground = c(0xFFFF7A1A), buttonText = c(0xFF1A0B00), focusRing = c(0xFFFFB774)),
            backgroundStyle = BackgroundStyle.GRADIENT, gradientStart = c(0xFF0C0A09), gradientEnd = c(0xFF231409),
            cardRadius = 8, buttonRadius = 8, glowEnabled = false,
        )
    },
    // Lujo: negro y oro.
    StyleTheme("style_oro", "Oro Negro", "Negro con detalles dorados") { s ->
        s.copy(
            palette = s.palette.copy(primary = c(0xFFD9B45A), secondary = c(0xFF6B5420), accent = c(0xFFF3DC9A),
                background = c(0xFF080706), surface = c(0xFF15130F), surfaceVariant = c(0xFF231F17),
                onBackground = c(0xFFFBF6EA), onSurface = c(0xFFEDE4CF), textSecondary = c(0xFFA89D84),
                onPrimary = c(0xFF1A1405), buttonBackground = c(0xFFD9B45A), buttonText = c(0xFF1A1405), focusRing = c(0xFFF3DC9A)),
            backgroundStyle = BackgroundStyle.GRADIENT, gradientStart = c(0xFF080706), gradientEnd = c(0xFF1E1910),
            cardRadius = 12, buttonRadius = 4, glowEnabled = true, glowIntensity = 0.2f, buttonStyle = ButtonStyle.FILLED,
        )
    },
)

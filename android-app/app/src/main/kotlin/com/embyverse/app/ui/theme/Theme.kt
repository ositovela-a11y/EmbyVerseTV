package com.embyverse.app.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.embyverse.app.R
import com.embyverse.core.config.BackgroundStyle
import com.embyverse.core.config.ButtonStyle
import com.embyverse.core.config.FontChoice
import com.embyverse.core.config.NavStyle
import com.embyverse.core.config.ThemeSpec

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

@Immutable
data class DeviceInfo(val isTv: Boolean, val width: WidthClass) {
    /** En TV y pantallas anchas se usa navegación lateral (mejor para mando a distancia). */
    val useRail get() = isTv || width == WidthClass.EXPANDED
}

/** Tokens de diseño de EmbyVerse derivados de la configuración remota + preferencias del usuario. */
@Immutable
data class EmbyTokens(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val textSecondary: Color,
    val buttonBackground: Color,
    val buttonText: Color,
    val focusRing: Color,
    val error: Color,
    val backgroundStyle: BackgroundStyle,
    val gradientStart: Color,
    val gradientEnd: Color,
    val cardRadius: Dp,
    val buttonRadius: Dp,
    val bannerRadius: Dp,
    val screenPadding: Dp,
    val sectionGap: Dp,
    val cardGap: Dp,
    val glowEnabled: Boolean,
    val glowIntensity: Float,
    val buttonStyle: ButtonStyle,
    val navStyle: NavStyle,
    val navShowLabels: Boolean,
    val device: DeviceInfo,
    val heroStyle: com.embyverse.core.config.HeroStyle = com.embyverse.core.config.HeroStyle.CLASSIC,
    val cardStyle: com.embyverse.core.config.CardStyle = com.embyverse.core.config.CardStyle.TITLES,
    val focusStyle: com.embyverse.core.config.FocusStyle = com.embyverse.core.config.FocusStyle.RING,
    val rowAspect: com.embyverse.core.config.CardAspect? = null,
    val tvNavFromTheme: Boolean = false,
)

val LocalEmby = staticCompositionLocalOf<EmbyTokens> { error("EmbyTheme no inicializado") }

fun Int.toColor() = Color(this)

private val Poppins = FontFamily(
    Font(R.font.poppins_regular, FontWeight.Normal),
    Font(R.font.poppins_medium, FontWeight.Medium),
    Font(R.font.poppins_medium, FontWeight.SemiBold),
    Font(R.font.poppins_bold, FontWeight.Bold),
)
private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

fun fontFor(choice: FontChoice): FontFamily = when (choice) {
    FontChoice.POPPINS -> Poppins
    FontChoice.INTER -> Inter
    FontChoice.SYSTEM -> FontFamily.SansSerif
}

private fun typography(family: FontFamily, scale: Float, tv: Boolean): Typography {
    val s = scale * if (tv) 1.12f else 1f
    fun ts(size: Int, weight: FontWeight, line: Int, spacing: Float = 0f) =
        TextStyle(fontFamily = family, fontWeight = weight, fontSize = (size * s).sp, lineHeight = (line * s).sp, letterSpacing = spacing.sp)
    return Typography(
        displaySmall = ts(32, FontWeight.Bold, 40),
        headlineMedium = ts(26, FontWeight.Bold, 32),
        headlineSmall = ts(22, FontWeight.SemiBold, 28),
        titleLarge = ts(19, FontWeight.SemiBold, 25),
        titleMedium = ts(16, FontWeight.SemiBold, 22, 0.1f),
        titleSmall = ts(14, FontWeight.Medium, 20, 0.1f),
        bodyLarge = ts(15, FontWeight.Normal, 22),
        bodyMedium = ts(14, FontWeight.Normal, 20),
        bodySmall = ts(12, FontWeight.Normal, 16),
        labelLarge = ts(14, FontWeight.SemiBold, 20, 0.2f),
        labelMedium = ts(12, FontWeight.Medium, 16, 0.3f),
        labelSmall = ts(11, FontWeight.Medium, 14, 0.4f),
    )
}

fun detectTv(context: Context): Boolean {
    val ui = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

@Composable
fun rememberDeviceInfo(): DeviceInfo {
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val tv = remember(context) { detectTv(context) }
    val width = when {
        cfg.screenWidthDp >= 840 -> WidthClass.EXPANDED
        cfg.screenWidthDp >= 600 -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
    return remember(tv, width) { DeviceInfo(tv, width) }
}

@Composable
fun EmbyTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    val device = rememberDeviceInfo()
    val p = spec.palette
    val tokens = remember(spec, device) {
        EmbyTokens(
            primary = p.primary.toColor(), secondary = p.secondary.toColor(), accent = p.accent.toColor(),
            background = p.background.toColor(), surface = p.surface.toColor(), surfaceVariant = p.surfaceVariant.toColor(),
            onBackground = p.onBackground.toColor(), onSurface = p.onSurface.toColor(), textSecondary = p.textSecondary.toColor(),
            buttonBackground = p.buttonBackground.toColor(), buttonText = p.buttonText.toColor(), focusRing = p.focusRing.toColor(),
            error = p.error.toColor(), backgroundStyle = spec.backgroundStyle,
            gradientStart = spec.gradientStart.toColor(), gradientEnd = spec.gradientEnd.toColor(),
            cardRadius = spec.cardRadius.dp, buttonRadius = spec.buttonRadius.dp, bannerRadius = spec.bannerRadius.dp,
            screenPadding = (if (device.isTv) maxOf(spec.screenPadding, 40) else spec.screenPadding).dp,
            sectionGap = spec.sectionGap.dp, cardGap = spec.cardGap.dp,
            glowEnabled = spec.glowEnabled, glowIntensity = spec.glowIntensity,
            buttonStyle = spec.buttonStyle, navStyle = spec.navStyle, navShowLabels = spec.navShowLabels, device = device,
            heroStyle = spec.heroStyle, cardStyle = spec.cardStyle, focusStyle = spec.focusStyle, rowAspect = spec.rowAspect,
            tvNavFromTheme = spec.tvNavFromTheme,
        )
    }
    val scheme: ColorScheme = remember(tokens) {
        darkColorScheme(
            primary = tokens.primary, onPrimary = p.onPrimary.toColor(),
            primaryContainer = tokens.primary.copy(alpha = 0.22f), onPrimaryContainer = tokens.onBackground,
            secondary = tokens.secondary, onSecondary = Color.White,
            secondaryContainer = tokens.surfaceVariant, onSecondaryContainer = tokens.onSurface,
            tertiary = tokens.accent, onTertiary = Color.Black,
            background = tokens.background, onBackground = tokens.onBackground,
            surface = tokens.surface, onSurface = tokens.onSurface,
            surfaceVariant = tokens.surfaceVariant, onSurfaceVariant = tokens.textSecondary,
            surfaceContainer = tokens.surface, surfaceContainerHigh = tokens.surfaceVariant,
            surfaceContainerHighest = tokens.surfaceVariant, surfaceContainerLow = tokens.surface,
            outline = tokens.textSecondary.copy(alpha = 0.5f), outlineVariant = tokens.surfaceVariant,
            error = tokens.error, onError = Color.White,
        )
    }
    val typography = remember(spec.font, spec.fontScale, device.isTv) { typography(fontFor(spec.font), spec.fontScale, device.isTv) }
    val shapes = remember(spec.cardRadius, spec.buttonRadius) {
        Shapes(
            small = RoundedCornerShape((spec.cardRadius * 0.6f).dp),
            medium = RoundedCornerShape(spec.cardRadius.dp),
            large = RoundedCornerShape((spec.cardRadius * 1.3f).dp),
            extraLarge = RoundedCornerShape(spec.bannerRadius.dp),
        )
    }
    CompositionLocalProvider(LocalEmby provides tokens) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

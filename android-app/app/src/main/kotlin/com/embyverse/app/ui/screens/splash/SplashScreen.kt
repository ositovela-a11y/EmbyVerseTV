package com.embyverse.app.ui.screens.splash

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.embyverse.app.R
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.BrandLogo
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.Brand
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Entrada premium (~3.5 s):
 *  0.0 s  aparece el halo y un anillo de luz se dibuja alrededor del emblema circular
 *  0.8 s  el logotipo (completo, sin recortes) entra al centro del círculo — coincide con el golpe del sonido
 *  1.5 s  un destello recorre el logotipo; aparecen el nombre y el lema
 *  3.2 s  acercamiento + fundido hacia la app (o en cuanto llegue la configuración, si tarda más)
 * El sonido se puede apagar en Ajustes › Reproducción › Sonido de entrada.
 */
@Composable
fun SplashScreen(brand: Brand, onFinished: () -> Unit) {
    val t = LocalEmby.current
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val ring = remember { Animatable(0f) }     // anillo que se dibuja (0 → 1 vuelta)
    val logo = remember { Animatable(0f) }     // entrada del logotipo
    val shine = remember { Animatable(-0.5f) } // destello
    val text = remember { Animatable(0f) }     // nombre y lema
    val exit = remember { Animatable(0f) }     // salida hacia la app
    val defaultProfile = stringResource(R.string.profile_default)
    val player = remember { arrayOfNulls<MediaPlayer>(1) }

    DisposableEffect(Unit) { onDispose { player[0]?.runCatching { release() }; player[0] = null } }

    LaunchedEffect(Unit) {
        val fetch = async { withTimeoutOrNull(4_000) { container.config.refresh() } }
        val ensure = async { container.library.ensureProfile(defaultProfile) }
        launch {
            if (container.prefs.playback.first().introSound) runCatching {
                MediaPlayer.create(context, R.raw.intro, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(), 0)
                    ?.apply { setVolume(0.8f, 0.8f); setOnCompletionListener { it.release(); player[0] = null }; start() }
                    ?.also { player[0] = it }
            }
        }
        launch { ring.animateTo(1f, tween(1_100, easing = Emphasized)) }
        delay(650)
        launch { logo.animateTo(1f, tween(800, easing = Emphasized)) }
        delay(650)
        launch { shine.animateTo(1.5f, tween(900, easing = LinearEasing)) }
        launch { text.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        delay(1_800)
        ensure.await(); fetch.await()
        exit.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        onFinished()
    }

    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(0.88f, 1f,
        infiniteRepeatable(tween(1_500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glowPulse")
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f,
        infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "ringSpin")
    val loader by rememberInfiniteTransition(label = "loader").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1_100, easing = LinearEasing)), label = "loaderPos")

    val emblem = if (t.device.isTv) 230.dp else 210.dp
    val out = 1f - exit.value
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = out; val s = 1f + exit.value * 0.12f; scaleX = s; scaleY = s },
        contentAlignment = Alignment.Center) {
        // Halo de luz de los colores de la marca.
        Canvas(Modifier.fillMaxSize()) {
            val e = ring.value
            val r = size.minDimension * (if (t.device.isTv) 0.48f else 0.62f) * (0.6f + 0.4f * e) * pulse
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = 0.30f * e), t.secondary.copy(alpha = 0.12f * e), Color.Transparent),
                center = center, radius = r), radius = r, center = center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(emblem), contentAlignment = Alignment.Center) {
                // Anillo: se dibuja una vuelta completa y luego gira despacio.
                Canvas(Modifier.fillMaxSize().rotate(spin * ring.value)) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2 + 2.dp.toPx()
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(Brush.sweepGradient(listOf(t.primary, t.accent, t.secondary, t.primary)), -90f, 360f * ring.value, false,
                        Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                // Disco interior con el logotipo completo (ajustado, nunca recortado).
                Box(Modifier.size(emblem - 22.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(t.surfaceVariant, t.surface, t.background)))
                    .graphicsLayer { alpha = (ring.value * 1.4f).coerceAtMost(1f) },
                    contentAlignment = Alignment.Center) {
                    BrandLogo(brand.logoVariant, brand.logoUrl,
                        Modifier.padding(emblem * 0.15f).fillMaxSize()
                            .graphicsLayer {
                                val e = logo.value
                                alpha = e; val s = 0.7f + 0.3f * e; scaleX = s; scaleY = s
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithContent {
                                drawContent()
                                val x = shine.value * size.width
                                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent),
                                    start = Offset(x - size.width * 0.3f, 0f), end = Offset(x + size.width * 0.3f, size.height)),
                                    blendMode = BlendMode.SrcAtop)
                            })
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(brand.appName.uppercase(), style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 6.sp), fontWeight = FontWeight.Bold,
                color = t.onBackground, modifier = Modifier.graphicsLayer { alpha = text.value; translationY = (1f - text.value) * 12.dp.toPx() })
            Spacer(Modifier.height(6.dp))
            Text(brand.tagline ?: stringResource(R.string.splash_loading), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.5.sp),
                color = t.textSecondary, modifier = Modifier.graphicsLayer { alpha = text.value * 0.9f })
            Spacer(Modifier.height(22.dp))
            // Barra fina de carga.
            Canvas(Modifier.width(120.dp).height(3.dp).graphicsLayer { alpha = text.value }) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = radius)
                val w = size.width * 0.35f
                val x0 = ((size.width + w) * loader - w).coerceAtLeast(0f)
                val x1 = ((size.width + w) * loader).coerceAtMost(size.width)
                if (x1 > x0) drawRoundRect(Brush.horizontalGradient(listOf(t.primary, t.accent)), topLeft = Offset(x0, 0f),
                    size = Size(x1 - x0, size.height), cornerRadius = radius)
            }
        }
    }
}

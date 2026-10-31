package com.embyverse.app.ui.screens.splash

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlin.math.cos
import kotlin.math.sin

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val WarpEasing = CubicBezierEasing(0.6f, 0f, 0.8f, 1f)

/**
 * Entrada «EmbyVerse» (~5 s), un viaje por el universo:
 *  0.0–1.5 s  salto al hiperespacio: las estrellas salen disparadas desde el centro
 *  0.9 s      aparece una galaxia en espiral que gira lentamente
 *  1.4 s      destello y el logotipo surge en el centro con dos órbitas y pequeños planetas
 *  2.2 s      bienvenida y mensajes que cambian
 *  4.6 s      acercamiento final hacia la app
 * El sonido se puede apagar en Ajustes › Reproducción › Sonido de entrada.
 */
@Composable
fun SplashScreen(brand: Brand, onFinished: () -> Unit) {
    val t = LocalEmby.current
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val warp = remember { Animatable(0f) }    // 0→1 viaje (velocidad de las estrellas)
    val galaxy = remember { Animatable(0f) }  // aparición de la galaxia
    val flash = remember { Animatable(0f) }   // destello al llegar
    val logo = remember { Animatable(0f) }    // logotipo y órbitas
    val text = remember { Animatable(0f) }    // textos
    val exit = remember { Animatable(0f) }    // salida
    val message = remember { mutableIntStateOf(0) }
    val messages = listOf(
        stringResource(R.string.splash_msg_1), stringResource(R.string.splash_msg_2),
        stringResource(R.string.splash_msg_3), stringResource(R.string.splash_msg_4),
    )
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
        launch { warp.animateTo(1f, tween(1_500, easing = WarpEasing)) }
        delay(900)
        launch { galaxy.animateTo(1f, tween(1_400, easing = FastOutSlowInEasing)) }
        delay(500)
        launch { flash.animateTo(1f, tween(140)); flash.animateTo(0f, tween(600)) }
        launch { logo.animateTo(1f, tween(900, easing = Emphasized)) }
        delay(800)
        launch { text.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
        for (i in 1 until messages.size) { delay(650); message.intValue = i }
        delay(500)
        ensure.await(); fetch.await()
        exit.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        onFinished()
    }

    val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f,
        infiniteRepeatable(tween(18_000, easing = LinearEasing)), label = "galaxySpin")
    val orbit by rememberInfiniteTransition(label = "orbit").animateFloat(0f, 1f,
        infiniteRepeatable(tween(4_000, easing = LinearEasing)), label = "orbitPos")
    val stars = remember { List(140) { i -> Triple((i * 137.508f) % 360f, ((i * 7919) % 1000) / 1000f, 0.6f + (i % 7) / 7f) } }

    val logoSize = if (t.device.isTv) 210.dp else 190.dp
    val out = 1f - exit.value
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = out; val s = 1f + exit.value * 0.18f; scaleX = s; scaleY = s }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val maxR = size.maxDimension * 0.75f
            // 1) Hiperespacio: cada estrella avanza hacia afuera y deja una estela que se alarga con la velocidad.
            val w = warp.value
            val speed = 1f - kotlin.math.abs(w - 0.6f) / 0.6f // máxima a mitad del viaje
            stars.forEach { (angDeg, depth, bright) ->
                val ang = Math.toRadians(angDeg.toDouble()).toFloat()
                val d = ((depth + w * 1.6f) % 1f)
                val r = d * d * maxR
                val tail = (0.02f + speed.coerceAtLeast(0f) * 0.22f) * maxR * d
                val dir = Offset(cos(ang), sin(ang))
                val alpha = (bright * (0.25f + d) * (1f - logo.value * 0.6f)).coerceIn(0f, 1f)
                drawLine(Color.White.copy(alpha = alpha), c + dir * r, c + dir * (r + tail), strokeWidth = (1f + d * 2.2f) * density, cap = StrokeCap.Round)
            }
            // 2) Galaxia en espiral (brazos de puntos de los colores de la marca).
            val g = galaxy.value
            if (g > 0f) rotate(spin, c) {
                for (arm in 0 until 3) for (k in 0 until 70) {
                    val f = k / 70f
                    val a = arm * 2.094f + f * 5.2f
                    val rr = f * size.minDimension * 0.62f
                    val col = if (k % 3 == 0) t.accent else if (arm == 1) t.secondary else t.primary
                    drawCircle(col.copy(alpha = 0.55f * g * (1f - f * 0.8f)), radius = (2.6f - f * 1.6f) * density,
                        center = Offset(c.x + cos(a) * rr, c.y + sin(a) * rr * 0.62f))
                }
            }
            // Núcleo luminoso.
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = 0.35f * g), t.secondary.copy(alpha = 0.12f * g), Color.Transparent), center = c,
                radius = size.minDimension * 0.5f), radius = size.minDimension * 0.5f, center = c)
            // 3) Destello de llegada.
            if (flash.value > 0f) drawCircle(Color.White.copy(alpha = 0.55f * flash.value), radius = size.maxDimension, center = c)
        }
        // 4) Órbitas con planetas alrededor del logotipo.
        Canvas(Modifier.size(logoSize * 1.9f).graphicsLayer { alpha = logo.value }) {
            val c = center
            listOf(Triple(0.48f, 0.18f, -18f), Triple(0.40f, 0.12f, 24f)).forEachIndexed { i, (rx, ry, tilt) ->
                rotate(tilt, c) {
                    val ow = size.width * rx * 2; val oh = size.height * ry * 2
                    drawOval(Brush.linearGradient(listOf(t.primary.copy(alpha = 0.7f), t.accent.copy(alpha = 0.2f), t.secondary.copy(alpha = 0.7f))),
                        topLeft = Offset(c.x - ow / 2, c.y - oh / 2), size = Size(ow, oh), style = Stroke(1.6f * density))
                    val a = (orbit + i * 0.5f) * 6.283f * (if (i == 0) 1f else -1.3f)
                    val p = Offset(c.x + cos(a) * ow / 2, c.y + sin(a) * oh / 2)
                    drawCircle(if (i == 0) t.accent else t.primary, radius = (4.5f - i) * density, center = p)
                    drawCircle(Color.White.copy(alpha = 0.6f), radius = 1.6f * density, center = p - Offset(1.2f * density, 1.2f * density))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLogo(brand.logoVariant, brand.logoUrl,
                Modifier.size(logoSize).graphicsLayer {
                    val e = logo.value
                    alpha = e; val s = 0.35f + 0.65f * e; scaleX = s; scaleY = s
                    compositingStrategy = CompositingStrategy.Offscreen
                }.drawWithContent {
                    drawContent()
                    val x = (logo.value * 1.8f - 0.4f) * size.width
                    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
                        start = Offset(x - size.width * 0.3f, 0f), end = Offset(x + size.width * 0.3f, size.height)), blendMode = BlendMode.SrcAtop)
                })
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.splash_welcome, brand.appName), style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 1.sp),
                fontWeight = FontWeight.Bold, color = t.onBackground,
                modifier = Modifier.graphicsLayer { alpha = text.value; translationY = (1f - text.value) * 14.dp.toPx() })
            Spacer(Modifier.height(6.dp))
            Text(brand.tagline ?: stringResource(R.string.splash_universe), style = MaterialTheme.typography.titleSmall.copy(letterSpacing = 2.sp),
                color = t.accent, modifier = Modifier.graphicsLayer { alpha = text.value })
            Spacer(Modifier.height(10.dp))
            AnimatedContent(message.intValue, label = "splashMsg",
                transitionSpec = { (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 2 }) togetherWith fadeOut(tween(220)) }) { i ->
                Text(messages[i.coerceIn(0, messages.lastIndex)], style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
                    color = t.textSecondary, modifier = Modifier.graphicsLayer { alpha = text.value })
            }
        }
    }
}

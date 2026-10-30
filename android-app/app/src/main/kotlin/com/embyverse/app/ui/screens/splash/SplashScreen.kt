package com.embyverse.app.ui.screens.splash

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Entrada de la app: un solo logotipo (el del sistema queda vacío) que aparece con un halo de luz,
 * un brillo que lo recorre una vez y una barra fina de carga. Mientras tanto se descarga la
 * configuración remota. Al terminar todo se desvanece suavemente hacia la app.
 */
@Composable
fun SplashScreen(brand: Brand, onFinished: () -> Unit) {
    val t = LocalEmby.current
    val container = LocalAppContainer.current
    val enter = remember { Animatable(0f) }      // 0 → 1: aparición del logotipo
    val shine = remember { Animatable(-0.4f) }   // posición del brillo sobre el logotipo
    val exit = remember { Animatable(0f) }       // 0 → 1: salida hacia la app
    val defaultProfile = stringResource(R.string.profile_default)

    LaunchedEffect(Unit) {
        val fetch = async { withTimeoutOrNull(2500) { container.config.refresh() } }
        val ensure = async { container.library.ensureProfile(defaultProfile) }
        enter.animateTo(1f, tween(700, easing = Emphasized))
        launch { shine.animateTo(1.4f, tween(650, easing = LinearEasing)) }
        delay(450)
        ensure.await(); fetch.await()
        exit.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
        onFinished()
    }

    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(0.85f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glowPulse")
    val loader by rememberInfiniteTransition(label = "loader").animateFloat(0f, 1f,
        infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "loaderPos")

    val e = enter.value
    val out = 1f - exit.value
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = out; scaleX = 1f + exit.value * 0.04f; scaleY = scaleX }, contentAlignment = Alignment.Center) {
        // Halo de luz del color principal detrás del logotipo.
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension * (if (t.device.isTv) 0.42f else 0.55f) * (0.7f + 0.3f * e) * pulse
            drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = 0.32f * e), t.accent.copy(alpha = 0.10f * e), Color.Transparent),
                center = center, radius = r), radius = r, center = center)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            BrandLogo(brand.logoVariant, brand.logoUrl,
                Modifier.fillMaxWidth(if (t.device.isTv) 0.30f else 0.66f).widthIn(max = 420.dp)
                    .graphicsLayer {
                        alpha = e; val s = 0.86f + 0.14f * e; scaleX = s; scaleY = s
                        translationY = (1f - e) * 24.dp.toPx()
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // Brillo diagonal que recorre el logotipo una vez (solo sobre sus píxeles).
                        val x = shine.value * size.width
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                            start = Offset(x - size.width * 0.25f, 0f), end = Offset(x + size.width * 0.25f, size.height)),
                            blendMode = BlendMode.SrcAtop)
                    })
            Spacer(Modifier.height(if (t.device.isTv) 28.dp else 24.dp))
            // Barra fina de carga.
            Canvas(Modifier.width(140.dp).height(3.dp).graphicsLayer { alpha = e }) {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(Color.White.copy(alpha = 0.12f), cornerRadius = radius)
                val w = size.width * 0.35f
                val x = (size.width + w) * loader - w
                drawRoundRect(Brush.horizontalGradient(listOf(t.primary, t.accent)), topLeft = Offset(x.coerceAtLeast(0f), 0f),
                    size = Size((x + w).coerceAtMost(size.width) - x.coerceAtLeast(0f), size.height).let { if (it.width < 0) Size(0f, it.height) else it },
                    cornerRadius = radius)
            }
            Spacer(Modifier.height(14.dp))
            Text(brand.tagline ?: stringResource(R.string.splash_loading), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp),
                fontWeight = FontWeight.Medium, color = t.textSecondary.copy(alpha = 0.9f), modifier = Modifier.graphicsLayer { alpha = e })
        }
        Spacer(Modifier.size(0.dp))
    }
}

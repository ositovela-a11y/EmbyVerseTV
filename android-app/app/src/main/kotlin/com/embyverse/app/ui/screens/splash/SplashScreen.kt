package com.embyverse.app.ui.screens.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.embyverse.app.R
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.BrandLogo
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.Brand
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Pantalla de bienvenida con el logotipo oficial. Aprovecha la animación para pedir la configuración remota. */
@Composable
fun SplashScreen(brand: Brand, onFinished: () -> Unit) {
    val t = LocalEmby.current
    val container = LocalAppContainer.current
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.92f) }
    val defaultProfile = stringResource(R.string.profile_default)
    LaunchedEffect(Unit) {
        val fetch = async { withTimeoutOrNull(2500) { container.config.refresh() } }
        val ensure = async { container.library.ensureProfile(defaultProfile) }
        val a = async { alpha.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        scale.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        a.await(); ensure.await()
        delay(350)
        fetch.await()
        onFinished()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BrandLogo(brand.logoVariant, brand.logoUrl,
                Modifier.fillMaxWidth(if (t.device.isTv) 0.32f else 0.7f).widthIn(max = 420.dp).alpha(alpha.value).scale(scale.value))
            brand.tagline?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = t.textSecondary, modifier = Modifier.alpha(alpha.value)) }
        }
    }
}

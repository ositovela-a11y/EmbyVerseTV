package com.embyverse.app.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.BuildConfig
import com.embyverse.app.R
import com.embyverse.app.data.prefs.UiPrefs
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.theme.LocalEmby
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ------------------------------------------------------------------ Conexión a internet

/** Estado de internet de todo el equipo (para el aviso «Sin conexión» y reintentar al volver). */
object Connectivity {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        fun check() = runCatching {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }.getOrDefault(true)
        _online.value = check()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _online.value = true }
                override fun onLost(network: Network) { _online.value = check() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }
}

/**
 * Aviso fijo arriba cuando no hay internet; al volver dice «Conexión recuperada» y la app vuelve a
 * pedir el diseño y las listas sola (el reproductor ya reintenta por su cuenta).
 */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val online by Connectivity.online.collectAsStateWithLifecycle()
    var wasOffline by remember { mutableStateOf(false) }
    var back by remember { mutableStateOf(false) }
    LaunchedEffect(online) {
        if (!online) { wasOffline = true; back = false }
        else if (wasOffline) {
            wasOffline = false; back = true
            runCatching { c.config.refresh() }
            delay(3_000); back = false
        }
    }
    AnimatedVisibility(!online || back, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = modifier) {
        Row(Modifier.statusBarsPadding().padding(top = 6.dp).clip(RoundedCornerShape(50))
            .background(if (online) Color(0xFF2E7D32) else Color(0xFFB3261E)).padding(horizontal = 16.dp, vertical = 8.dp).testTag("offline_banner"),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (online) Icons.Rounded.Wifi else Icons.Rounded.CloudOff, null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (online) R.string.net_back else R.string.net_offline), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ------------------------------------------------------------------ Progreso real de la carga de listas

/** «Preparando tus canales… 40 %»: avance real mientras se descargan canales, películas y series. */
@Composable
fun SyncProgressPill(modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val t = LocalEmby.current
    val p by c.sources.syncProgress.collectAsStateWithLifecycle()
    AnimatedVisibility(p != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut(), modifier = modifier) {
        val v = p ?: 0f
        Column(Modifier.padding(16.dp).widthIn(max = 360.dp).clip(RoundedCornerShape(16.dp)).background(t.surface.copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 10.dp).testTag("sync_progress")) {
            Text(stringResource(R.string.sync_progress, (v * 100).toInt()), color = t.onBackground, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { v }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                color = t.primary, trackColor = t.surfaceVariant, drawStopIndicator = {}, gapSize = 0.dp)
        }
    }
}

// ------------------------------------------------------------------ Esqueleto de carga

/** Brillo que recorre el hueco mientras llega la imagen (como en las plataformas grandes). */
fun Modifier.shimmer(active: Boolean): Modifier = if (!active) this else composed {
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f, infiniteRepeatable(tween(1_200, easing = LinearEasing)), label = "shimmerX")
    drawWithContent {
        drawContent()
        val w = size.width
        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent),
            start = Offset(x * w - w * 0.5f, 0f), end = Offset(x * w + w * 0.5f, size.height)))
    }
}

// ------------------------------------------------------------------ Tamaño de letra

/** Aplica el tamaño de letra elegido por el cliente (normal, grande, muy grande) a toda la app. */
@Composable
fun FontScaled(content: @Composable () -> Unit) {
    val scale by UiPrefs.fontScale.collectAsStateWithLifecycle()
    val d = LocalDensity.current
    if (scale == 1f) content()
    else CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * scale)) { content() }
}

// ------------------------------------------------------------------ Bienvenida

private data class TourPage(val icon: ImageVector, val title: Int, val body: Int)

/** Bienvenida de 3 pantallas la primera vez: qué trae, cómo se usa y dónde pedir ayuda. */
@Composable
fun WelcomeTour(onDone: () -> Unit) {
    val t = LocalEmby.current
    val context = LocalContext.current
    val pages = remember {
        listOf(TourPage(Icons.Rounded.LiveTv, R.string.tour1_title, R.string.tour1_body),
            TourPage(Icons.Rounded.Favorite, R.string.tour2_title, R.string.tour2_body),
            TourPage(Icons.Rounded.SupportAgent, R.string.tour3_title, R.string.tour3_body))
    }
    var i by remember { mutableIntStateOf(0) }
    val finish = { UiPrefs.setOnboardingDone(context); onDone() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(i) { runCatching { focus.requestFocus() } }
    androidx.activity.compose.BackHandler(enabled = i > 0) { i-- }
    Box(Modifier.fillMaxSize().background(t.background).testTag("welcome_tour"), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 560.dp).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandLogo(com.embyverse.core.config.LogoVariant.BUNDLED_FULL, null, Modifier.height(if (t.device.isTv) 90.dp else 72.dp))
            Spacer(Modifier.height(28.dp))
            AnimatedContent(i, label = "tour", transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(320)) { it * dir / 3 } + fadeIn(tween(320))) togetherWith (slideOutHorizontally(tween(220)) { -it * dir / 3 } + fadeOut(tween(200)))
            }) { k ->
                val pg = pages[k]
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(104.dp).clip(CircleShape).background(Brush.linearGradient(listOf(t.primary, t.secondary))), contentAlignment = Alignment.Center) {
                        Icon(pg.icon, null, tint = Color.White, modifier = Modifier.size(52.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(pg.title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = t.onBackground, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(pg.body), style = MaterialTheme.typography.bodyLarge, color = t.textSecondary, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { k -> Box(Modifier.size(if (k == i) 22.dp else 8.dp, 8.dp).clip(RoundedCornerShape(50)).background(if (k == i) t.primary else t.surfaceVariant)) }
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i < pages.lastIndex) EmbyButton(stringResource(R.string.tour_skip), finish, secondary = true, modifier = Modifier.testTag("tour_skip"))
                EmbyButton(stringResource(if (i < pages.lastIndex) R.string.tour_next else R.string.tour_start), { if (i < pages.lastIndex) i++ else finish() },
                    modifier = Modifier.focusRequester(focus).testTag("tour_next"))
            }
        }
    }
}

// ------------------------------------------------------------------ Novedades de la versión

/** Lo nuevo de esta versión (se muestra una vez después de actualizar, no en una instalación nueva). */
val WHATS_NEW = listOf(
    R.string.wn_1, R.string.wn_2, R.string.wn_3, R.string.wn_4, R.string.wn_5, R.string.wn_6, R.string.wn_7,
)

@Composable
fun WhatsNewDialog(force: Boolean = false, onClose: () -> Unit = {}) {
    val context = LocalContext.current
    val t = LocalEmby.current
    var show by remember {
        mutableStateOf(force || UiPrefs.lastSeenVersion(context).let { seen ->
            if (seen == 0) { UiPrefs.setSeenVersion(context, BuildConfig.VERSION_CODE); false } else seen < BuildConfig.VERSION_CODE
        })
    }
    if (!show) return
    val close = { UiPrefs.setSeenVersion(context, BuildConfig.VERSION_CODE); show = false; onClose() }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.wn_title, BuildConfig.VERSION_NAME)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("whats_new")) {
                WHATS_NEW.forEach { res ->
                    Row { Text("✦  ", color = t.accent); Text(stringResource(res), style = MaterialTheme.typography.bodyMedium) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close, modifier = Modifier.testTag("whats_new_ok")) { Text(stringResource(R.string.wn_ok)) } },
    )
}

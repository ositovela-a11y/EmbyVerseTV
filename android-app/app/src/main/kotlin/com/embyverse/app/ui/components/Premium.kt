package com.embyverse.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.R
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.sin
import kotlin.random.Random

/**
 * Reloj de animación con pasos de [stepMs] mediante delay (no con animaciones infinitas de Compose,
 * que impiden que las pruebas automáticas lleguen a «reposo»). Devuelve 0..1 en vaivén cada [periodMs].
 */
@Composable
fun rememberPingPong(periodMs: Long = 16_000, stepMs: Long = 40): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs) {
        val start = System.currentTimeMillis()
        while (true) {
            val x = ((System.currentTimeMillis() - start) % (periodMs * 2)).toFloat() / periodMs
            phase.floatValue = if (x <= 1f) x else 2f - x
            delay(stepMs)
        }
    }
    return phase
}

/** Efecto «Ken Burns»: acercamiento y desplazamiento lentos de la imagen de fondo. */
@Composable
fun Modifier.kenBurns(): Modifier {
    val p by rememberPingPong()
    return this.graphicsLayer {
        val s = 1.05f + 0.10f * p
        scaleX = s; scaleY = s
        translationX = (p - 0.5f) * size.width * 0.05f
        translationY = (0.5f - p) * size.height * 0.03f
    }
}

/** Temporadas que se activan desde el panel (texto «season»). */
enum class Season(val key: String) { NAVIDAD("navidad"), HALLOWEEN("halloween"), MUERTOS("muertos"), MUNDIAL("mundial");
    companion object { fun of(v: String?) = entries.firstOrNull { it.key == v?.trim()?.lowercase() } }
}

/** Partículas ligeras sobre el fondo (nieve, murciélagos, cempasúchil, balones). No captura toques. */
@Composable
fun SeasonOverlay(season: Season, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    val n = if (t.device.isTv) 22 else 16
    val seeds = remember(season) { List(n) { Triple(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat() * 0.8f) } }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(season) { val s = System.currentTimeMillis(); while (true) { now = System.currentTimeMillis() - s; delay(50) } }
    val glyphs = when (season) {
        Season.NAVIDAD -> listOf("❄", "❅", "•")
        Season.HALLOWEEN -> listOf("🦇", "🎃", "🕸")
        Season.MUERTOS -> listOf("🌼", "💀", "🕯")
        Season.MUNDIAL -> listOf("⚽", "🏆", "🎉")
    }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.fillMaxSize().testTag("season_overlay")) {
        // Papel picado (Día de Muertos) arriba: banderitas de colores.
        if (season == Season.MUERTOS) {
            val colors = listOf(Color(0xFFFF4FA3), Color(0xFFFFB300), Color(0xFF7C4DFF), Color(0xFF00C2A8), Color(0xFFFF6D00))
            val flags = 14; val w = size.width / flags
            repeat(flags) { i ->
                drawRect(colors[i % colors.size].copy(alpha = 0.32f), Offset(i * w + 2f, 0f), androidx.compose.ui.geometry.Size(w - 4f, 26.dp.toPx()))
            }
        }
        seeds.forEachIndexed { i, (x0, y0, speed) ->
            val fall = (now / 1000f) * 0.035f * speed
            val y = ((y0 + fall) % 1.1f) - 0.05f
            val x = x0 + sin((now / 1000f) * speed + i) * 0.02f
            val px = 14.dp.toPx() * speed
            if (season == Season.NAVIDAD && i % 3 == 2) {
                drawCircle(Color.White.copy(alpha = 0.55f), px * 0.25f, Offset(x * size.width, y * size.height))
            } else {
                paint.textSize = px * (if (season == Season.NAVIDAD) 1.1f else 1.4f)
                paint.alpha = if (season == Season.NAVIDAD) 150 else 120
                paint.color = android.graphics.Color.argb(paint.alpha, 255, 255, 255)
                drawContext.canvas.nativeCanvas.drawText(glyphs[i % glyphs.size], x * size.width, y * size.height, paint)
            }
        }
    }
}

/**
 * Protector de pantalla propio: pósters de lo más nuevo con movimiento lento, reloj y logo.
 * Lo cierra cualquier tecla o toque (eso lo maneja la actividad).
 */
@Composable
fun Screensaver(modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val t = LocalEmby.current
    val flow = remember { c.catalog.recentMovies(30) }
    val movies by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val withArt = movies.filter { it.backdropUrl != null || it.posterUrl != null }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(withArt.size) { while (withArt.isNotEmpty()) { delay(9_000); index = (index + 1) % withArt.size } }
    Box(modifier.fillMaxSize().background(Color.Black).testTag("screensaver")) {
        if (withArt.isNotEmpty()) {
            val m = withArt[index % withArt.size]
            AnimatedContent(m, transitionSpec = { fadeIn(tween(1200)) togetherWith fadeOut(tween(1200)) }, label = "ss") { mv ->
                Box(Modifier.fillMaxSize()) {
                    MediaImage(mv.backdropUrl ?: mv.posterUrl, ContentKind.MOVIE, Modifier.fillMaxSize().kenBurns())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                    Text(mv.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 2,
                        modifier = Modifier.align(Alignment.BottomStart).padding(48.dp))
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(40.dp), horizontalAlignment = Alignment.End) {
            Clock(color = Color.White)
            Text(stringResource(R.string.screensaver_hint), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.6f))
        }
        val brand = c.config.config.value.brand
        BrandLogo(brand.logoVariant, brand.logoUrl, Modifier.align(Alignment.BottomEnd).padding(40.dp).size(72.dp).graphicsLayer { alpha = 0.8f })
    }
}

/** Evento destacado del panel: banner, cuenta regresiva y canales que lo transmiten. */
data class FeaturedEvent(val title: String, val subtitle: String?, val startMs: Long, val imageUrl: String?, val keywords: List<String>) {
    companion object {
        /** Desaparece 3 h después de empezar. */
        fun from(texts: Map<String, String>, now: Long = System.currentTimeMillis()): FeaturedEvent? {
            val title = texts["event.title"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val start = texts["event.start"]?.let { runCatching { java.time.Instant.parse(it.trim()).toEpochMilli() }.getOrNull() } ?: return null
            if (now > start + 3 * 3600_000L) return null
            return FeaturedEvent(title, texts["event.subtitle"]?.trim()?.ifEmpty { null }, start, texts["event.image"]?.trim()?.takeIf { it.startsWith("https://") },
                texts["event.channels"].orEmpty().split(',').map { it.trim() }.filter { it.length >= 2 })
        }
    }
}

@Composable
fun FeaturedEventCard(ev: FeaturedEvent, onChannel: (ItemRef) -> Unit) {
    val t = LocalEmby.current
    val c = LocalAppContainer.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ev) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    var channels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(ev.keywords) {
        val hidden = c.parental.hidden.first()
        channels = ev.keywords.flatMap { k -> c.database.channels().search(com.embyverse.core.m3u.M3uCatalogBuilder.normalize(k), 12) }
            .distinctBy { it.sourceId + it.providerId }.filter { it.category !in hidden }.take(16)
    }
    val left = (ev.startMs - now).coerceAtLeast(0) / 1000
    val live = now >= ev.startMs
    Column(Modifier.fillMaxWidth().padding(horizontal = t.screenPadding).testTag("featured_event")) {
        Box(Modifier.fillMaxWidth().height(if (t.device.isTv) 190.dp else 150.dp)
            .background(Brush.linearGradient(listOf(t.primary.copy(alpha = 0.7f), t.secondary.copy(alpha = 0.5f), t.surface)), RoundedCornerShape(t.cardRadius))) {
            ev.imageUrl?.let { MediaImage(it, ContentKind.MOVIE, Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f; clip = true; shape = RoundedCornerShape(t.cardRadius) }) }
            Column(Modifier.align(Alignment.CenterStart).padding(20.dp)) {
                Text(if (live) stringResource(R.string.event_live_now) else stringResource(R.string.event_featured), style = MaterialTheme.typography.labelLarge,
                    color = t.buttonText, modifier = Modifier.background(if (live) Color(0xFFE53935) else t.primary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                Spacer(Modifier.height(8.dp))
                Text(ev.title, style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ev.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1) }
            }
            if (!live) Row(Modifier.align(Alignment.CenterEnd).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(left / 86400 to R.string.cd_days, (left % 86400) / 3600 to R.string.cd_hours, (left % 3600) / 60 to R.string.cd_min, left % 60 to R.string.cd_sec)
                    .forEachIndexed { i, (v, label) ->
                        if (i == 0 && v == 0L) return@forEachIndexed
                        Column(Modifier.background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("%02d".format(v), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Black)
                            Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        }
                    }
            }
        }
        if (channels.isNotEmpty()) LazyRow(contentPadding = PaddingValues(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(channels, key = { it.sourceId + "|" + it.providerId }) { ch ->
                FocusableSurface(onClick = { onChannel(ItemRef(ContentKind.LIVE, ch.sourceId, ch.providerId)) }, focusScale = 1.06f) { focused ->
                    Row(Modifier.background(if (focused) t.surfaceVariant else t.surface).padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 40.dp, height = 24.dp)) { MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit) }
                        Spacer(Modifier.width(8.dp))
                        Text(ch.name, style = MaterialTheme.typography.labelLarge, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
                        Icon(Icons.Rounded.PlayArrow, null, tint = t.primary)
                    }
                }
            }
        }
    }
}

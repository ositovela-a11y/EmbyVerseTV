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

/**
 * Fondos animados que se eligen desde el panel (texto «season»):
 *  - uno fijo (p. ej. «cine»),
 *  - «rotar»: cambia solo entre los fondos generales cada «season.every» horas (3 por defecto),
 *  - «auto»: en fechas especiales pone el de la fecha (Navidad, Halloween, Muertos, Patrias…) y el resto
 *    del tiempo rota entre los generales.
 */
enum class Season(val key: String, val general: Boolean) {
    CINE("cine", true), PALOMITAS("palomitas", true), GALAXIA("galaxia", true), ESTRELLAS("estrellas", true),
    DEPORTES("deportes", true), MUSICA("musica", true), PRIMAVERA("primavera", true), VERANO("verano", true), BURBUJAS("burbujas", true),
    NAVIDAD("navidad", false), ANO_NUEVO("anonuevo", false), SAN_VALENTIN("sanvalentin", false), PATRIAS("patrias", false),
    HALLOWEEN("halloween", false), MUERTOS("muertos", false), MUNDIAL("mundial", false);

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.key == v?.trim()?.lowercase() }

        /** Fecha especial de hoy (si la hay). */
        fun holiday(cal: java.util.Calendar): Season? {
            val m = cal.get(java.util.Calendar.MONTH) + 1; val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
            return when {
                m == 12 && d == 31 || m == 1 && d <= 2 -> ANO_NUEVO
                m == 12 -> NAVIDAD
                m == 2 && d in 10..14 -> SAN_VALENTIN
                m == 9 && d in 10..16 -> PATRIAS
                m == 10 && d >= 24 -> HALLOWEEN
                m == 11 && d <= 2 -> MUERTOS
                else -> null
            }
        }

        fun resolve(texts: Map<String, String>, now: Long = System.currentTimeMillis()): Season? {
            val v = texts["season"]?.trim()?.lowercase().orEmpty()
            val every = (texts["season.every"]?.trim()?.toIntOrNull() ?: 3).coerceIn(1, 48)
            val general = entries.filter { it.general }
            val rotating = general[((now / (every * 3_600_000L)) % general.size).toInt()]
            return when (v) {
                "" , "ninguna", "none" -> null
                "rotar" -> rotating
                "auto" -> holiday(java.util.Calendar.getInstance().apply { timeInMillis = now }) ?: rotating
                else -> of(v)
            }
        }
    }
}

/** Fondo animado según el panel; se revisa cada minuto (para la rotación y las fechas). */
@Composable
fun SeasonLayer(texts: Map<String, String>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = System.currentTimeMillis() } }
    val season = remember(texts, now / 60_000) { Season.resolve(texts, now) } ?: return
    AnimatedContent(season, transitionSpec = { fadeIn(tween(1500)) togetherWith fadeOut(tween(1500)) }, label = "season") { SeasonOverlay(it) }
}

/** Partículas ligeras sobre el fondo. No captura toques. */
@Composable
fun SeasonOverlay(season: Season, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    val n = if (t.device.isTv) 22 else 16
    val seeds = remember(season) { List(n) { Triple(Random.nextFloat(), Random.nextFloat(), 0.6f + Random.nextFloat() * 0.8f) } }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(season) { val s = System.currentTimeMillis(); while (true) { now = System.currentTimeMillis() - s; delay(50) } }
    // Cómo se mueven: caen, suben (burbujas, globos, corazones) o titilan en su lugar (estrellas).
    val rises = season in setOf(Season.BURBUJAS, Season.SAN_VALENTIN, Season.MUSICA, Season.ANO_NUEVO)
    val twinkle = season in setOf(Season.GALAXIA, Season.ESTRELLAS)
    val confetti = season in setOf(Season.MUNDIAL, Season.PATRIAS, Season.ANO_NUEVO)
    val glyphs = when (season) {
        Season.NAVIDAD -> listOf("❄", "❅", "•")
        Season.HALLOWEEN -> listOf("🦇", "🎃", "🕸")
        Season.MUERTOS -> listOf("🌼", "💀", "🕯")
        Season.MUNDIAL -> listOf("⚽", "🏆", "🎉")
        Season.CINE -> listOf("🎬", "🎞", "⭐", "🎥")
        Season.PALOMITAS -> listOf("🍿", "🥤", "🎟")
        Season.GALAXIA -> listOf("✦", "✧", "🪐", "☄")
        Season.ESTRELLAS -> listOf("✦", "✧", "⋆")
        Season.DEPORTES -> listOf("⚽", "🏀", "🏈", "⚾", "🎾")
        Season.MUSICA -> listOf("🎵", "🎶", "🎧")
        Season.PRIMAVERA -> listOf("🌸", "🦋", "🌼")
        Season.VERANO -> listOf("🌴", "☀", "🏖", "🍉")
        Season.BURBUJAS -> listOf("○", "◦", "○")
        Season.ANO_NUEVO -> listOf("🎆", "🎇", "✨", "🥂")
        Season.SAN_VALENTIN -> listOf("❤", "💕", "💖")
        Season.PATRIAS -> listOf("🎉", "🎊", "⭐")
    }
    val confettiColors = if (season == Season.PATRIAS) listOf(Color(0xFF006847), Color.White, Color(0xFFCE1126))
        else listOf(Color(0xFFFFC107), Color(0xFF29B6F6), Color(0xFFEF5350), Color(0xFF66BB6A), Color(0xFFAB47BC))
    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    Canvas(modifier.fillMaxSize().testTag("season_overlay")) {
        val secs = now / 1000f
        // Papel picado (Día de Muertos / Fiestas Patrias) arriba: banderitas de colores.
        if (season == Season.MUERTOS || season == Season.PATRIAS) {
            val colors = if (season == Season.PATRIAS) listOf(Color(0xFF006847), Color.White, Color(0xFFCE1126))
                else listOf(Color(0xFFFF4FA3), Color(0xFFFFB300), Color(0xFF7C4DFF), Color(0xFF00C2A8), Color(0xFFFF6D00))
            val flags = 14; val w = size.width / flags
            repeat(flags) { i ->
                drawRect(colors[i % colors.size].copy(alpha = 0.32f), Offset(i * w + 2f, 0f), androidx.compose.ui.geometry.Size(w - 4f, 26.dp.toPx()))
            }
        }
        seeds.forEachIndexed { i, (x0, y0, speed) ->
            val travel = secs * 0.035f * speed
            val y = if (twinkle) y0 else if (rises) 1.05f - ((y0 + travel) % 1.1f) else ((y0 + travel) % 1.1f) - 0.05f
            val x = x0 + (if (twinkle) 0f else sin(secs * speed + i) * 0.02f)
            val px = 14.dp.toPx() * speed
            val alpha = if (twinkle) (0.25f + 0.75f * ((sin(secs * 2f * speed + i * 1.7f) + 1f) / 2f)) else 1f
            when {
                confetti && i % 2 == 0 -> drawRect(confettiColors[i % confettiColors.size].copy(alpha = 0.55f),
                    Offset(x * size.width, y * size.height), androidx.compose.ui.geometry.Size(px * 0.45f, px * 0.75f * (0.4f + 0.6f * kotlin.math.abs(sin(secs * 3 * speed + i)))))
                (season == Season.NAVIDAD || season == Season.ESTRELLAS) && i % 3 == 2 ->
                    drawCircle(Color.White.copy(alpha = 0.55f * alpha), px * 0.25f, Offset(x * size.width, y * size.height))
                else -> {
                    paint.textSize = px * (if (season == Season.NAVIDAD || twinkle || season == Season.BURBUJAS) 1.1f else 1.4f)
                    val a = ((if (season == Season.NAVIDAD || twinkle || season == Season.BURBUJAS) 150 else 115) * alpha).toInt()
                    paint.color = android.graphics.Color.argb(a, 255, 255, 255)
                    drawContext.canvas.nativeCanvas.drawText(glyphs[i % glyphs.size], x * size.width, y * size.height, paint)
                }
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

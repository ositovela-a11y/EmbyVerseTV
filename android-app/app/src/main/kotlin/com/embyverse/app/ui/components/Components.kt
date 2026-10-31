package com.embyverse.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import com.embyverse.app.R
import com.embyverse.app.data.repo.MediaCard
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.BackgroundStyle
import com.embyverse.core.config.ButtonStyle
import com.embyverse.core.config.CardAspect
import com.embyverse.core.config.CardSize
import com.embyverse.core.config.LogoVariant
import com.embyverse.core.model.ContentKind

/** Fondo de pantalla según el estilo publicado (sólido, degradado o nebulosa discreta). */
@Composable
fun NebulaBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = LocalEmby.current
    val base = when (t.backgroundStyle) {
        BackgroundStyle.SOLID -> Modifier.background(t.background)
        else -> Modifier.background(Brush.verticalGradient(listOf(t.gradientStart, t.gradientEnd)))
    }
    val glow = if (t.backgroundStyle == BackgroundStyle.NEBULA && t.glowEnabled) Modifier.drawBehind {
        val a = 0.18f * t.glowIntensity
        drawCircle(Brush.radialGradient(listOf(t.primary.copy(alpha = a), Color.Transparent), center = Offset(size.width * 0.85f, size.height * 0.08f), radius = size.maxDimension * 0.55f))
        drawCircle(Brush.radialGradient(listOf(t.secondary.copy(alpha = a * 1.2f), Color.Transparent), center = Offset(size.width * 0.05f, size.height * 0.75f), radius = size.maxDimension * 0.6f))
    } else Modifier
    Box(modifier.then(base).then(glow), content = content)
}

/**
 * Logotipo oficial, sin fondo negro: el integrado ya es transparente y los subidos desde el panel se
 * recortan en óvalo. Detrás lleva un halo ovalado suave del color de la marca.
 */
@Composable
fun BrandLogo(variant: LogoVariant, url: String?, modifier: Modifier = Modifier, oval: Boolean = true) {
    val t = LocalEmby.current
    val fallback = if (variant == LogoVariant.BUNDLED_PLANET) R.drawable.embyverse_planet else R.drawable.embyverse_logo
    val ovalShape = androidx.compose.foundation.shape.GenericShape { size, _ -> addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }
    Box(modifier.then(if (oval) Modifier.drawBehind {
        drawOval(Brush.radialGradient(listOf(t.primary.copy(alpha = 0.22f), t.secondary.copy(alpha = 0.10f), Color.Transparent),
            center = center, radius = size.maxDimension * 0.6f))
    } else Modifier), contentAlignment = Alignment.Center) {
        if (variant == LogoVariant.ASSET && url != null) {
            SubcomposeAsyncImage(model = url, contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.fillMaxSize().then(if (oval) Modifier.clip(ovalShape) else Modifier), contentScale = ContentScale.Fit,
                error = { Image(painterResource(R.drawable.embyverse_logo), null, contentScale = ContentScale.Fit) })
        } else {
            Image(painterResource(fallback), contentDescription = stringResource(R.string.app_name), modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/**
 * Contenedor enfocable: en Android TV el foco del mando agranda la tarjeta y dibuja el
 * anillo de foco; en táctil se comporta como un botón normal.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(LocalEmby.current.cardRadius),
    focusScale: Float = 1.06f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val t = LocalEmby.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // Estilo de foco del tema: RING (borde de color), LIFT (se eleva y agranda, como en Apple TV), WHITE (borde blanco).
    val lift = t.focusStyle == com.embyverse.core.config.FocusStyle.LIFT
    val target = if (!focused) 1f else if (lift && focusScale > 1f) focusScale + 0.04f else focusScale
    val scale by animateFloatAsState(target, tween(120), label = "focusScale")
    // Foco MUY visible (mando de TV): borde grueso del color de foco + ligero aumento. Sin sombras
    // difuminadas: en TV Box modestos hacían que el desplazamiento se viera lento y borroso.
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale; scaleY = scale
                if (lift && focused) { shadowElevation = 18.dp.toPx(); this.shape = shape; clip = false }
            }
            .clip(shape)
            .then(when {
                !focused || lift -> Modifier
                t.focusStyle == com.embyverse.core.config.FocusStyle.WHITE -> Modifier.border(BorderStroke(2.5.dp, Color.White), shape)
                else -> Modifier.border(BorderStroke(3.dp, t.focusRing), shape)
            })
            .then(
                if (onLongClick != null) Modifier.combinedClickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(interactionSource = interaction, indication = androidx.compose.material3.ripple(), role = Role.Button, onClick = onClick)
            ),
    ) { content(focused) }
}

fun cardWidth(aspect: CardAspect, size: CardSize, tv: Boolean): Dp {
    val base = when (aspect) {
        // Más tarjetas por fila: en celular se ven ~4 pósters y en TV ~7 (antes 3 y 5).
        CardAspect.POSTER -> if (tv) 124 else 90
        CardAspect.LANDSCAPE -> if (tv) 224 else 168
        CardAspect.SQUARE -> if (tv) 118 else 88
    }
    return (base * size.scale).dp
}

/** Color de respaldo estable por título: el hueco de una portada sin imagen nunca se ve vacío. */
fun fallbackBrush(seed: String, surface: Color): Brush {
    val h = ((seed.hashCode() % 360) + 360) % 360
    return Brush.linearGradient(listOf(Color.hsv(h.toFloat(), 0.55f, 0.32f), Color.hsv(((h + 60) % 360).toFloat(), 0.6f, 0.12f), surface))
}

/**
 * Imagen de contenido. Usa AsyncImage (mucho más rápida en listas que SubcomposeAsyncImage):
 * el fondo de respaldo se dibuja debajo y la imagen aparece encima cuando carga.
 */
@Composable
fun MediaImage(url: String?, kind: ContentKind, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, fallbackText: String? = null) {
    val t = LocalEmby.current
    var failed by remember(url) { mutableStateOf(url.isNullOrBlank()) }
    Box(modifier.background(if (kind == ContentKind.LIVE) Brush.linearGradient(listOf(t.surfaceVariant, t.surface)) else fallbackBrush(fallbackText ?: url ?: "", t.surface))) {
        if (failed) {
            if (fallbackText != null && kind != ContentKind.LIVE) Text(fallbackText, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f),
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            else Icon(if (kind == ContentKind.LIVE) Icons.Rounded.LiveTv else Icons.Rounded.Movie, null, tint = t.textSecondary.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center).size(30.dp))
        } else {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale, onError = { failed = true })
        }
    }
}

/** Tarjeta de contenido (póster, apaisada o cuadrada) con título, subtítulo y progreso opcional. */
@Composable
fun MediaCardView(card: MediaCard, aspect: CardAspect, size: CardSize, onClick: () -> Unit, modifier: Modifier = Modifier, showText: Boolean = true) {
    val t = LocalEmby.current
    val w = cardWidth(aspect, size, t.device.isTv)
    val live = card.ref.kind == ContentKind.LIVE
    Column(modifier.width(w)) {
        FocusableSurface(onClick = onClick, modifier = Modifier.fillMaxWidth().aspectRatio(aspect.ratio).testTag("card:${card.ref.kind}:${card.ref.id}"), focusScale = 1.07f) { focused ->
            Box(Modifier.fillMaxSize().border(1.dp, if (focused) Color.Transparent else Color.White.copy(alpha = 0.07f), RoundedCornerShape(t.cardRadius))) {
                MediaImage(if (aspect == CardAspect.LANDSCAPE && !live) card.backdropUrl ?: card.imageUrl else card.imageUrl, card.ref.kind, Modifier.fillMaxSize().then(if (live) Modifier.padding(horizontal = 18.dp, vertical = 12.dp) else Modifier),
                    if (live) ContentScale.Fit else ContentScale.Crop, fallbackText = card.title)
                if (!live) Box(Modifier.fillMaxWidth().fillMaxHeight(0.35f).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
                card.progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                        color = t.primary, trackColor = Color.White.copy(alpha = 0.18f), drawStopIndicator = {}, gapSize = 0.dp)
                }
            }
        }
        // Estilo "limpio" (tipo Netflix/HBO): solo la imagen; el título aparece en la ficha.
        if (!showText || (t.cardStyle == com.embyverse.core.config.CardStyle.CLEAN && card.imageUrl != null && !live && card.progress == null)) return@Column
        Spacer(Modifier.height(8.dp))
        Text(card.title, style = MaterialTheme.typography.titleSmall, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        card.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String?, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    Row(modifier.fillMaxWidth().padding(horizontal = t.screenPadding), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleLarge, color = t.onBackground)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
        }
        if (onSeeAll != null) TextButton(onClick = onSeeAll) {
            Text(stringResource(R.string.see_all), color = t.accent)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = t.accent)
        }
    }
}

@Composable
fun CardRow(cards: List<MediaCard>, aspect: CardAspect, size: CardSize, onClick: (MediaCard) -> Unit) {
    val t = LocalEmby.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(t.cardGap),
    ) {
        items(cards, key = { "${it.ref.kind}|${it.ref.sourceId}|${it.ref.id}" }) { c -> MediaCardView(c, aspect, size, { onClick(c) }) }
    }
}

/** Cuadrícula no perezosa para incrustar en listas (número de elementos acotado por maxItems). */
@Composable
fun CardGrid(cards: List<MediaCard>, aspect: CardAspect, size: CardSize, columns: Int, onClick: (MediaCard) -> Unit) {
    val t = LocalEmby.current
    Column(Modifier.padding(horizontal = t.screenPadding), verticalArrangement = Arrangement.spacedBy(t.cardGap)) {
        cards.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(t.cardGap)) { row.forEach { c -> MediaCardView(c, aspect, size, { onClick(c) }) } }
        }
    }
}

/** Botón principal que respeta el estilo (relleno, tonal o contorno) y los colores publicados. */
@Composable
fun EmbyButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, secondary: Boolean = false) {
    val t = LocalEmby.current
    val shape = RoundedCornerShape(t.buttonRadius)
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    val style = if (secondary) ButtonStyle.OUTLINED else t.buttonStyle
    when (style) {
        ButtonStyle.FILLED -> Button(onClick, modifier, enabled, shape, ButtonDefaults.buttonColors(containerColor = t.buttonBackground, contentColor = t.buttonText)) { content() }
        ButtonStyle.TONAL -> FilledTonalButton(onClick, modifier, enabled, shape, ButtonDefaults.filledTonalButtonColors(containerColor = t.buttonBackground.copy(alpha = 0.22f), contentColor = t.buttonBackground)) { content() }
        ButtonStyle.OUTLINED -> OutlinedButton(onClick, modifier, enabled, shape, ButtonDefaults.outlinedButtonColors(contentColor = t.onBackground), border = BorderStroke(1.5.dp, t.buttonBackground.copy(alpha = 0.8f))) { content() }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = LocalEmby.current.primary) }
}

@Composable
fun MessageState(icon: ImageVector, title: String, message: String?, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val t = LocalEmby.current
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(t.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = t.accent, modifier = Modifier.size(36.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.onBackground)
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary) }
        if (actionLabel != null && onAction != null) EmbyButton(actionLabel, onAction)
    }
}

/** Chip de categoría enfocable. */
@Composable
fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalEmby.current
    FocusableSurface(onClick = onClick, shape = RoundedCornerShape(50), focusScale = 1.04f) { _ ->
        Box(
            Modifier.background(if (selected) t.primary else t.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) t.buttonText else t.onSurface, maxLines = 1) }
    }
}

/** Reloj (hora local del equipo). Se actualiza al cambiar el minuto, sin animaciones continuas. */
@Composable
fun Clock(modifier: Modifier = Modifier, color: Color = LocalEmby.current.onBackground, small: Boolean = false) {
    val fmt = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(60_000 - now % 60_000 + 50)
        }
    }
    Text(fmt.format(java.util.Date(now)), style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium, color = color, maxLines = 1,
        modifier = modifier.testTag("clock"))
}

/**
 * Botón «Actualizar» siempre a la vista: vuelve a cargar canales, películas, series y guía de todas
 * las cuentas. Mientras trabaja muestra «Actualizando…».
 */
@Composable
fun RefreshListsButton(modifier: Modifier = Modifier, compact: Boolean = false) {
    val t = LocalEmby.current
    val c = com.embyverse.app.ui.LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val states by c.sources.syncState.collectAsStateWithLifecycle()
    val sources by c.sources.sources.collectAsStateWithLifecycle(initialValue = emptyList())
    val running = states.values.any { it is com.embyverse.app.data.repo.SyncState.Running }
    FocusableSurface(onClick = { if (!running) sources.forEach { com.embyverse.app.work.SyncWorker.syncNow(context, it.id) } },
        shape = RoundedCornerShape(50), focusScale = 1.06f, modifier = modifier.testTag("refresh_lists")) { focused ->
        Row(Modifier.background(if (focused) t.primary else t.surfaceVariant.copy(alpha = 0.9f)).padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (running) CircularProgressIndicator(Modifier.size(16.dp), color = if (focused) t.buttonText else t.primary, strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, null, tint = if (focused) t.buttonText else t.onBackground, modifier = Modifier.size(18.dp))
            if (!compact || running) {
                Spacer(Modifier.width(6.dp))
                Text(stringResource(if (running) R.string.refresh_running else R.string.refresh_lists), style = MaterialTheme.typography.labelLarge,
                    color = if (focused) t.buttonText else t.onBackground, maxLines = 1)
            }
        }
    }
}

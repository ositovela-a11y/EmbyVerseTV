package com.embyverse.app.player

import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.animation.core.tween
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.embyverse.app.R
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.components.MediaImage
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.model.ContentKind

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, onClose: () -> Unit, onPip: (() -> Unit)? = null, castEnabled: Boolean = false) {
    val t = LocalEmby.current
    val player by vm.player.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val controls by vm.controlsVisible.collectAsStateWithLifecycle()
    val listVisible by vm.channelListVisible.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val program by vm.currentProgram.collectAsStateWithLifecycle()
    val next by vm.nextEpisode.collectAsStateWithLifecycle()
    val zap by vm.zapBanner.collectAsStateWithLifecycle()
    val optionsVisible by vm.optionsVisible.collectAsStateWithLifecycle()
    val listCategory by vm.listCategory.collectAsStateWithLifecycle()
    val pip by vm.inPip.collectAsStateWithLifecycle()
    val zapTarget by vm.zapTarget.collectAsStateWithLifecycle()
    val digits by vm.digits.collectAsStateWithLifecycle()
    val subStyle by vm.subtitleStyle.collectAsStateWithLifecycle()
    val nextProg by vm.nextProgram.collectAsStateWithLifecycle()
    val kidsTimeUp by vm.kidsTimeUp.collectAsStateWithLifecycle()
    val castingTo by vm.castingTo.collectAsStateWithLifecycle()
    val aspectMode by vm.aspect.collectAsStateWithLifecycle()
    // Calidad real del video que se está viendo (ExoPlayer ya elige la mejor según la red).
    var videoHeight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(player) {
        while (player != null) { videoHeight = player?.videoSize?.height ?: 0; kotlinx.coroutines.delay(1500) }
    }

    BackHandler {
        when {
            optionsVisible -> vm.showOptions(false)
            listVisible -> vm.toggleChannelList()
            else -> onClose()
        }
    }
    KeepScreenOnWhilePlaying(player)
    // Celular: bloqueo de pantalla (niños) y referencia al reproductor para mostrar controles con un toque.
    var locked by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val viewRef = androidx.compose.runtime.remember { arrayOfNulls<PlayerView>(1) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Formato de pantalla: ajustar / llenar / zoom / 16:9 / 4:3 (para canales con bordes negros o estirados).
        AndroidView(
            modifier = (when (aspectMode) { 3 -> Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                4 -> Modifier.fillMaxHeight().aspectRatio(4f / 3f, matchHeightConstraintsFirst = true); else -> Modifier.fillMaxSize() }).align(Alignment.Center),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    setShowSubtitleButton(true)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) // indicador propio
                    keepScreenOn = true
                    controllerAutoShow = !vm.isLive
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> vm.controlsVisible.value = v == View.VISIBLE })
                }.also { viewRef[0] = it }
            },
            update = { view ->
                view.resizeMode = when (aspectMode) { 1, 3, 4 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                    2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM; else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
                // Subtítulos a gusto del cliente (Ajustes › Reproducción): tamaño, color y fondo.
                view.subtitleView?.let { sv ->
                    val fg = if (subStyle.subtitleYellow) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                    val bg = when (subStyle.subtitleBackground) { 2 -> android.graphics.Color.BLACK; 1 -> android.graphics.Color.argb(150, 0, 0, 0); else -> android.graphics.Color.TRANSPARENT }
                    sv.setStyle(androidx.media3.ui.CaptionStyleCompat(fg, bg, android.graphics.Color.TRANSPARENT,
                        if (subStyle.subtitleBackground == 0) androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE,
                        android.graphics.Color.BLACK, null))
                    sv.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * listOf(0.8f, 1f, 1.3f, 1.65f)[subStyle.subtitleSize.coerceIn(0, 3)])
                    sv.setApplyEmbeddedStyles(false)
                }
                view.player = player; view.useController = !pip && !locked; if (listVisible || optionsVisible || pip || locked) view.hideController() },
            onRelease = { view -> view.player = null },
        )

        // Gestos del celular: toque, doble toque ±10 s, brillo y volumen (con los controles ocultos).
        if (!t.device.isTv && !pip && !locked && !controls && !listVisible && !optionsVisible && error == null && castingTo == null)
            PhoneGestureLayer(player, !vm.isLive, { viewRef[0]?.showController() })

        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = t.primary, strokeWidth = 4.dp)

        // Cabecera: título, canal / episodio y programa actual.
        if (pip) return@Box
        castingTo?.let { name ->
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.cast_playing_on, name), style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text(stringResource(R.string.cast_hint), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
            }
        }
        // Cambio de canal al instante: número tecleado o canal al que se va con ▲▼.
        if (digits.isNotEmpty() || zapTarget != null) {
            Row(Modifier.align(Alignment.TopCenter).padding(top = 28.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(14.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp).testTag("zap_target"), verticalAlignment = Alignment.CenterVertically) {
                if (digits.isNotEmpty()) Text(digits.padEnd(3, '_').toList().joinToString(" "), style = MaterialTheme.typography.headlineMedium,
                    color = t.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                zapTarget?.let { ch ->
                    if (ch.logoUrl != null) Box(Modifier.size(width = 54.dp, height = 34.dp)) { MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit) }
                    Spacer(Modifier.width(10.dp))
                    Text(listOfNotNull(ch.number?.toString(), ch.name).joinToString("  "), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
                }
            }
        }
        AnimatedVisibility(visible = controls || zap || error != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopStart)) {
            Row(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = Color.White) }
                if (vm.isLive && np?.imageUrl != null) Box(Modifier.size(width = 72.dp, height = 44.dp)) {
                    MediaImage(np?.imageUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(np?.title.orEmpty(), style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = if (vm.isLive) program?.title ?: np?.subtitle else np?.subtitle
                    sub?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (vm.isLive && channels.size > 1) Text(stringResource(R.string.player_channels_hint), style = MaterialTheme.typography.labelMedium, color = t.primary)
                }
                qualityLabel(videoHeight)?.let { q ->
                    Text(q, style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        modifier = Modifier.background(if (q == "4K") t.primary else Color.White.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp).testTag("quality_badge"))
                }
                com.embyverse.app.ui.components.Clock(Modifier.padding(horizontal = 8.dp), color = Color.White)
                if (castEnabled) AndroidView(factory = { ctx ->
                    // Tema oscuro: el ícono de transmitir sale blanco sobre el video.
                    runCatching { androidx.mediarouter.app.MediaRouteButton(android.view.ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat)).also {
                        com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it)
                    } }.getOrElse { android.view.View(ctx) }
                }, modifier = Modifier.size(48.dp).testTag("cast"))
                if (onPip != null) IconButton(onClick = onPip) {
                    Icon(Icons.Rounded.PictureInPictureAlt, stringResource(R.string.player_pip), tint = Color.White)
                }
                IconButton(onClick = { vm.showOptions(true) }, modifier = Modifier.testTag("player_options")) {
                    Icon(Icons.Rounded.Subtitles, stringResource(R.string.player_options), tint = Color.White)
                }
                if (vm.isLive && channels.size > 1) IconButton(onClick = { vm.toggleChannelList() }) {
                    Icon(Icons.AutoMirrored.Rounded.List, stringResource(R.string.player_channels), tint = Color.White)
                }
            }
        }

        // Mini guía: qué pasa ahora (con avance) y qué sigue en este canal.
        if (vm.isLive && (controls || zap) && program != null && !listVisible && !optionsVisible) {
            MiniGuide(program!!, nextProg, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = if (controls) 96.dp else 28.dp))
        }

        // Error amable: qué pasó, reintentar, pasar al siguiente canal o avisar al proveedor.
        error?.let { e ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val cfg by com.embyverse.app.ui.LocalAppContainer.current.config.config.collectAsStateWithLifecycle()
            Column(Modifier.align(Alignment.Center).widthIn(max = 520.dp).padding(24.dp)
                .background(t.surface.copy(alpha = 0.96f), RoundedCornerShape(t.cardRadius)).padding(24.dp).testTag("player_error"),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = t.error, modifier = Modifier.size(40.dp))
                Text(stringResource(if (vm.isLive) R.string.player_unavailable_live else R.string.player_unavailable_vod), style = MaterialTheme.typography.titleMedium,
                    color = t.onBackground, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(stringResource(e.messageRes), style = MaterialTheme.typography.bodyMedium, color = t.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmbyButton(stringResource(R.string.action_retry), { vm.retry() }, icon = Icons.Rounded.Refresh, modifier = Modifier.testTag("player_retry"))
                    if (vm.isLive) EmbyButton(stringResource(R.string.player_next_channel), { vm.error.value = null; vm.zap(1) }, icon = Icons.Rounded.SkipNext,
                        secondary = true, modifier = Modifier.testTag("player_next_channel"))
                    else EmbyButton(stringResource(R.string.action_close), onClose, secondary = true)
                }
                cfg.texts["support.whatsapp"]?.filter { it.isDigit() }?.takeIf { it.length >= 8 }?.let { n ->
                    androidx.compose.material3.TextButton(onClick = {
                        val msg = ctx.getString(R.string.player_report_msg, np?.title ?: "", ctx.getString(e.messageRes))
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://wa.me/$n?text=" + android.net.Uri.encode(msg))).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text(stringResource(R.string.player_report), color = t.accent) }
                }
            }
        }

        // Episodios: «Saltar intro» al inicio y cuenta regresiva del siguiente al final.
        if (!vm.isLive) EpisodeExtras(vm, player, np?.ref?.kind == ContentKind.EPISODE, Modifier.align(Alignment.BottomEnd))

        // Siguiente episodio.
        next?.let {
            EmbyButton(stringResource(R.string.player_next_episode), { vm.playNext() }, icon = Icons.Rounded.SkipNext,
                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp))
        }

        // Lista de canales para cambiar sin salir del reproductor.
        // Se abre con ◀ (o GUÍA); ◀ ▶ cambian de carpeta y el foco va al canal actual.
        AnimatedVisibility(visible = listVisible, enter = slideInHorizontally(tween(160)) { -it }, exit = slideOutHorizontally(tween(140)) { -it }, modifier = Modifier.align(Alignment.CenterStart)) {
            PlayerChannelList(vm, channels, listCategory, np?.ref)
        }

        // Opciones: audio, subtítulos y (en vivo) lista de canales. OK o MENÚ la abren.
        AnimatedVisibility(visible = optionsVisible, enter = slideInHorizontally(tween(160)) { it }, exit = slideOutHorizontally(tween(140)) { it }, modifier = Modifier.align(Alignment.CenterEnd)) {
            PlayerOptions(vm)
        }

        // Candado (celular): bloquea la pantalla para que los niños no toquen nada.
        if (!t.device.isTv && !pip && controls && !locked && error == null) LockButton({ locked = true; viewRef[0]?.hideController() },
            Modifier.align(Alignment.CenterEnd).padding(end = 18.dp))
        if (locked) LockedOverlay { locked = false; viewRef[0]?.showController() }
    }
    if (kidsTimeUp) com.embyverse.app.ui.components.PinDialog(stringResource(R.string.kids_time_up), stringResource(R.string.kids_time_up_sub), onDismiss = onClose) { pin ->
        vm.kidsPinOk(pin)
    }
    LaunchedEffect(np?.ref) { if (!vm.isLive) vm.controlsVisible.value = false }
}


@Composable
private fun EpisodeExtras(vm: PlayerViewModel, player: androidx.media3.exoplayer.ExoPlayer?, isEpisode: Boolean, modifier: Modifier) {
    val t = LocalEmby.current
    val upNext by vm.upNext.collectAsStateWithLifecycle()
    val cancelled by vm.upNextCancelled.collectAsStateWithLifecycle()
    var pos by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var dur by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var introSkipped by androidx.compose.runtime.remember(upNext) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(player) {
        while (player != null) {
            pos = player.currentPosition
            dur = player.duration.takeIf { it > 0 } ?: 0L
            kotlinx.coroutines.delay(500)
        }
    }
    val remaining = if (dur > 0) (dur - pos) / 1000 else Long.MAX_VALUE
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isEpisode && !introSkipped && pos in 8_000L..300_000L && dur > 600_000L) {
            // El botón toma el foco solo al aparecer: con OK se salta (antes costaba llegar a él con el control).
            val skipFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { skipFocus.requestFocus() } }
            androidx.compose.runtime.DisposableEffect(Unit) {
                vm.skipIntroAction = { player?.seekTo((player.currentPosition) + 85_000); introSkipped = true }
                onDispose { vm.skipIntroAction = null }
            }
            EmbyButton(stringResource(R.string.player_skip_intro), { player?.seekTo(pos + 85_000); introSkipped = true },
                icon = Icons.Rounded.SkipNext, modifier = Modifier.focusRequester(skipFocus).testTag("skip_intro"))
        }
        val n = upNext
        if (n != null && !cancelled && vm.autoplayNext && remaining <= 20) {
            val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Column(Modifier.widthIn(max = 380.dp).background(t.surface.copy(alpha = 0.95f), RoundedCornerShape(t.cardRadius)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.player_up_next_in, remaining.coerceAtLeast(0)), style = MaterialTheme.typography.labelLarge, color = t.primary)
                Text(n.second, style = MaterialTheme.typography.titleMedium, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmbyButton(stringResource(R.string.action_watch), { vm.playUpNext() }, icon = Icons.Rounded.SkipNext,
                        modifier = Modifier.focusRequester(focus))
                    EmbyButton(stringResource(R.string.action_cancel), { vm.cancelUpNext() }, secondary = true)
                }
            }
        }
    }
}

/** HD / FHD / 4K según la altura real del video. */
internal fun qualityLabel(height: Int): String? = when {
    height >= 2000 -> "4K"
    height >= 1000 -> "FHD"
    height >= 700 -> "HD"
    height > 0 -> "SD"
    else -> null
}

@Composable
private fun MiniGuide(now: com.embyverse.app.data.db.EpgEntity, next: com.embyverse.app.data.db.EpgEntity?, modifier: Modifier) {
    val t = LocalEmby.current
    val fmt = androidx.compose.runtime.remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    var nowSec by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(now) { while (true) { nowSec = System.currentTimeMillis() / 1000; kotlinx.coroutines.delay(30_000) } }
    val frac = if (now.end > now.start) ((nowSec - now.start).toFloat() / (now.end - now.start)).coerceIn(0f, 1f) else 0f
    Column(modifier.widthIn(max = 520.dp).background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(12.dp)).padding(14.dp).testTag("mini_guide")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.guide_now), style = MaterialTheme.typography.labelMedium, color = t.primary)
            Spacer(Modifier.width(8.dp))
            Text(now.title, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Text("${fmt.format(java.util.Date(now.start * 1000))} – ${fmt.format(java.util.Date(now.end * 1000))}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
        }
        Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().then(Modifier.height(4.dp)).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(t.primary, RoundedCornerShape(2.dp)))
        }
        next?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.guide_next), style = MaterialTheme.typography.labelMedium, color = t.textSecondary)
                Spacer(Modifier.width(8.dp))
                Text("${fmt.format(java.util.Date(n.start * 1000))}  ${n.title}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

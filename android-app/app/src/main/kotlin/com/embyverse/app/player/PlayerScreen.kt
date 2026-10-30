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
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
fun PlayerScreen(vm: PlayerViewModel, onClose: () -> Unit) {
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

    BackHandler { if (listVisible) vm.toggleChannelList() else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
                }
            },
            update = { view -> view.player = player; if (listVisible) view.hideController() },
            onRelease = { view -> view.player = null },
        )

        if (buffering && error == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp), color = t.primary, strokeWidth = 4.dp)

        // Cabecera: título, canal / episodio y programa actual.
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
                if (vm.isLive && channels.size > 1) IconButton(onClick = { vm.toggleChannelList() }) {
                    Icon(Icons.AutoMirrored.Rounded.List, stringResource(R.string.player_channels), tint = Color.White)
                }
            }
        }

        // Error comprensible con reintento.
        error?.let { e ->
            Column(Modifier.align(Alignment.Center).widthIn(max = 480.dp).padding(24.dp)
                .background(t.surface.copy(alpha = 0.95f), RoundedCornerShape(t.cardRadius)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = t.error, modifier = Modifier.size(40.dp))
                Text(stringResource(e.messageRes), style = MaterialTheme.typography.titleMedium, color = t.onBackground)
                (e as? PlayerUiError.Message)?.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmbyButton(stringResource(R.string.action_retry), { vm.retry() }, icon = Icons.Rounded.Refresh)
                    EmbyButton(stringResource(R.string.action_close), onClose, secondary = true)
                }
            }
        }

        // Siguiente episodio.
        next?.let {
            EmbyButton(stringResource(R.string.player_next_episode), { vm.playNext() }, icon = Icons.Rounded.SkipNext,
                modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp))
        }

        // Lista de canales para cambiar sin salir del reproductor.
        // Se abre con ◀ (o MENÚ/GUÍA) y el foco va directo al canal actual: se elige otro sin salir.
        AnimatedVisibility(visible = listVisible, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }, modifier = Modifier.align(Alignment.CenterStart)) {
            val currentIdx = channels.indexOfFirst { it.sourceId == np?.ref?.sourceId && it.providerId == np?.ref?.id }.coerceAtLeast(0)
            val state = rememberLazyListState((currentIdx - 3).coerceAtLeast(0))
            val currentFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { currentFocus.requestFocus() } }
            Column(Modifier.fillMaxHeight().width(340.dp).testTag("channel_list").background(t.surface.copy(alpha = 0.94f)).padding(vertical = 12.dp)) {
                Text(stringResource(R.string.player_channels), style = MaterialTheme.typography.titleMedium, color = t.onBackground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(channels, key = { _, ch -> "${ch.sourceId}|${ch.providerId}" }) { i, ch ->
                        FocusableSurface(onClick = { vm.playChannel(ch) }, focusScale = 1.02f,
                            modifier = Modifier.fillMaxWidth().then(if (i == currentIdx) Modifier.focusRequester(currentFocus) else Modifier)) { focused ->
                            Row(Modifier.fillMaxWidth().background(when { focused -> t.primary.copy(alpha = 0.25f); i == currentIdx -> t.surfaceVariant; else -> Color.Transparent }).padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("${ch.number ?: i + 1}", color = t.textSecondary, modifier = Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium)
                                Text(ch.name, color = if (i == currentIdx) t.primary else t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(np?.ref) { if (!vm.isLive) vm.controlsVisible.value = false }
}

package com.embyverse.app.ui.screens.multiview

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.embyverse.app.R
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.player.StreamSource
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.components.MediaImage
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.screens.library.SearchField
import com.embyverse.app.ui.screens.sources.SubScreenTop
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Multivista: 2 o 4 canales a la vez. Solo suena la ventana enfocada (borde de color).
 * Cada ventana usa UNA conexión del proveedor: se avisa antes de empezar.
 */
@OptIn(UnstableApi::class)
@Composable
fun MultiviewScreen(nav: Navigator) {
    val t = LocalEmby.current
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var warned by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(2) }
    val slots = remember { mutableStateListOf<ChannelEntity?>(null, null, null, null) }
    val players = remember { arrayOfNulls<ExoPlayer>(4) }
    var active by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }

    fun release(i: Int) { players[i]?.release(); players[i] = null }
    fun play(i: Int, ch: ChannelEntity) {
        slots[i] = ch
        scope.launch {
            val url = c.sources.resolveStreamUrl(ch.sourceId, ch.streamUrl)
            val p = players[i] ?: StreamSource.player(context).also { players[i] = it }
            p.setMediaSource(StreamSource.mediaSource(c.okHttp, url, ch.userAgent, ch.referrer, ch.name))
            p.volume = if (i == active) 1f else 0f
            p.prepare(); p.playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { (0 until 4).forEach(::release) } }
    LaunchedEffect(count) { (count until 4).forEach { release(it); slots[it] = null }; if (active >= count) active = 0 }
    LaunchedEffect(active) { players.forEachIndexed { i, p -> p?.volume = if (i == active) 1f else 0f } }

    if (!warned) {
        AlertDialog(onDismissRequest = nav::back, title = { Text(stringResource(R.string.multiview_title)) },
            text = { Text(stringResource(R.string.multiview_warning)) },
            confirmButton = { TextButton(onClick = { warned = true }, modifier = Modifier.testTag("multiview_ok")) { Text(stringResource(R.string.action_ok)) } },
            dismissButton = { TextButton(onClick = nav::back) { Text(stringResource(R.string.action_cancel)) } })
        return
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        SubScreenTop(stringResource(R.string.multiview_title), nav::back) {
            listOf(2, 4).forEach { n ->
                EmbyButton(stringResource(R.string.multiview_n, n), { count = n }, secondary = count != n, modifier = Modifier.padding(end = 8.dp))
            }
            slots[active]?.let { ch ->
                EmbyButton(stringResource(R.string.multiview_full), {
                    (0 until 4).forEach(::release)
                    nav.openItem(ItemRef(ContentKind.LIVE, ch.sourceId, ch.providerId))
                }, icon = Icons.Rounded.Fullscreen, secondary = true)
            }
        }
        Text(stringResource(R.string.multiview_hint), style = MaterialTheme.typography.bodySmall, color = t.textSecondary,
            modifier = Modifier.padding(horizontal = t.screenPadding, vertical = 4.dp))
        val rows = if (count == 2) listOf(listOf(0, 1)) else listOf(listOf(0, 1), listOf(2, 3))
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { i ->
                        val ch = slots[i]
                        val isActive = i == active
                        FocusableSurface(onClick = { picking = i }, focusScale = 1f,
                            modifier = Modifier.weight(1f).fillMaxSize().onFocusChanged { if (it.hasFocus || it.isFocused) active = i }.testTag("mv_slot_$i"),
                            shape = RoundedCornerShape(10.dp)) { _ ->
                            Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10))
                                .border(if (isActive) 3.dp else 1.dp, if (isActive) t.primary else Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))) {
                                if (ch == null) {
                                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Rounded.Add, null, tint = Color.White, modifier = Modifier.size(40.dp))
                                        Text(stringResource(R.string.multiview_pick), color = Color.White, style = MaterialTheme.typography.labelLarge)
                                    }
                                } else {
                                    androidx.compose.runtime.key(ch.sourceId + ch.providerId) {
                                        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                                            update = { v -> v.player = players[i] }, onRelease = { v -> v.player = null }, modifier = Modifier.fillMaxSize().padding(3.dp))
                                    }
                                    Row(Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(bottomEnd = 8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = t.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                                        Text(ch.name, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    picking?.let { slot ->
        ChannelPicker(onDismiss = { picking = null }) { ch -> picking = null; play(slot, ch) }
    }
}

/** Elegir canal para una ventana: buscador + lista (respeta el control parental del perfil). */
@Composable
private fun ChannelPicker(onDismiss: () -> Unit, onPick: (ChannelEntity) -> Unit) {
    val t = LocalEmby.current
    val c = LocalAppContainer.current
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(q) {
        val hidden = c.parental.hidden.first()
        val raw = if (q.isBlank()) c.database.channels().listForZapping(null).take(400)
        else c.database.channels().search(com.embyverse.core.m3u.M3uCatalogBuilder.normalize(q), 300)
        list = raw.filter { it.category !in hidden }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.multiview_pick)) },
        text = {
            Column {
                SearchField(q, { q = it }, stringResource(R.string.search_channels), Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(360.dp), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(list, key = { it.sourceId + "|" + it.providerId }) { ch ->
                        FocusableSurface(onClick = { onPick(ch) }, focusScale = 1.02f, modifier = Modifier.fillMaxWidth()) { _ ->
                            Row(Modifier.fillMaxWidth().background(t.surface).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(width = 48.dp, height = 30.dp)) { MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), androidx.compose.ui.layout.ContentScale.Fit) }
                                Spacer(Modifier.width(10.dp))
                                Text(ch.name, color = t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } })
}

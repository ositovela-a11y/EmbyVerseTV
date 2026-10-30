package com.embyverse.app.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.R
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.theme.LocalEmby
import kotlinx.coroutines.delay

/**
 * Lista lateral de canales dentro del reproductor.
 * ◀ ▶ (o las flechas de la cabecera en celular) cambian de carpeta sin cerrar la lista.
 */
@Composable
internal fun PlayerChannelList(vm: PlayerViewModel, channels: List<ChannelEntity>, category: String?, current: ItemRef?) {
    val t = LocalEmby.current
    Column(Modifier.fillMaxHeight().width(if (t.device.isTv) 380.dp else 320.dp).testTag("channel_list")
        .background(t.surface.copy(alpha = 0.96f)).padding(vertical = 10.dp)) {
        // Cabecera: ◀ Carpeta ▶
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.shiftListCategory(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, null, tint = t.onBackground) }
            Text(category ?: stringResource(R.string.category_all), style = MaterialTheme.typography.titleMedium, color = t.primary,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).testTag("channel_list_folder"))
            IconButton(onClick = { vm.shiftListCategory(+1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = t.onBackground) }
        }
        Text(stringResource(R.string.player_folder_hint), style = MaterialTheme.typography.labelSmall, color = t.textSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))

        // Cada carpeta tiene su propio estado de lista: al cambiar de carpeta empieza arriba (o en el canal actual).
        key(category) {
            val currentIdx = channels.indexOfFirst { it.sourceId == current?.sourceId && it.providerId == current?.id }
            val focusIdx = currentIdx.coerceAtLeast(0)
            val state = rememberLazyListState((focusIdx - 3).coerceAtLeast(0))
            val requester = remember { FocusRequester() }
            LaunchedEffect(channels) {
                if (channels.isEmpty()) return@LaunchedEffect
                state.scrollToItem((focusIdx - 3).coerceAtLeast(0))
                delay(32)
                runCatching { requester.requestFocus() }
            }
            LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(channels, key = { _, ch -> "${ch.sourceId}|${ch.providerId}" }, contentType = { _, _ -> 0 }) { i, ch ->
                    val isCurrent = i == currentIdx
                    FocusableSurface(onClick = { vm.playChannel(ch) }, focusScale = 1f,
                        modifier = Modifier.fillMaxWidth().then(if (i == focusIdx) Modifier.focusRequester(requester) else Modifier)) { focused ->
                        Row(Modifier.fillMaxWidth().background(when { focused -> t.primary.copy(alpha = 0.3f); isCurrent -> t.surfaceVariant; else -> Color.Transparent })
                            .padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${ch.number ?: i + 1}", color = t.textSecondary, modifier = Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
                            Text(ch.name, color = if (isCurrent) t.primary else t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = if (focused) FontWeight.Bold else null)
                        }
                    }
                }
            }
        }
    }
}

/** Panel de opciones: pista de audio, subtítulos y acceso a la lista de canales. */
@Composable
internal fun PlayerOptions(vm: PlayerViewModel) {
    val t = LocalEmby.current
    val audio by vm.audioTracks.collectAsStateWithLifecycle()
    val text by vm.textTracks.collectAsStateWithLifecycle()
    val textOn by vm.textEnabled.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    LazyColumn(Modifier.fillMaxHeight().width(if (t.device.isTv) 380.dp else 320.dp).testTag("player_options_panel")
        .background(t.surface.copy(alpha = 0.96f)),
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text(stringResource(R.string.player_options), style = MaterialTheme.typography.titleLarge, color = t.onBackground, modifier = Modifier.padding(6.dp)) }
        var firstUsed = false
        fun claim(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier

        if (vm.isLive) {
            val m = claim()
            item { OptionRow(stringResource(R.string.player_channels), selected = false, icon = true, modifier = m) { vm.openChannelList() } }
        }
        item { SectionTitle(stringResource(R.string.options_audio)) }
        if (audio.isEmpty()) item { Hint(stringResource(R.string.options_audio_default)) }
        else {
            val m = claim()
            itemsIndexed(audio, key = { _, a -> a.key }) { i, a -> OptionRow(a.label, a.selected, modifier = if (i == 0) m else Modifier) { vm.selectTrack(a) } }
            if (audio.size == 1) item { Hint(stringResource(R.string.options_audio_single)) }
        }
        item { SectionTitle(stringResource(R.string.options_subtitles)) }
        if (text.isEmpty()) item { Hint(stringResource(R.string.options_subtitles_none)) }
        else {
            item { OptionRow(stringResource(R.string.options_subtitles_off), !textOn) { vm.subtitlesOff() } }
            items(text, key = { it.key }) { s -> OptionRow(s.label, textOn && s.selected) { vm.selectTrack(s) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val t = LocalEmby.current
    Text(text, style = MaterialTheme.typography.labelLarge, color = t.textSecondary, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    val t = LocalEmby.current
    Text(text, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, icon: Boolean = false, onClick: () -> Unit) {
    val t = LocalEmby.current
    FocusableSurface(onClick = onClick, focusScale = 1f, modifier = modifier.fillMaxWidth()) { focused ->
        Row(Modifier.fillMaxWidth().background(if (focused) t.primary.copy(alpha = 0.3f) else Color.Transparent).padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                when {
                    icon -> Icon(Icons.AutoMirrored.Rounded.List, null, tint = t.onBackground, modifier = Modifier.size(20.dp))
                    selected -> Icon(Icons.Rounded.Check, null, tint = t.primary, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, color = if (selected) t.primary else t.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected || focused) FontWeight.Bold else null)
        }
    }
}

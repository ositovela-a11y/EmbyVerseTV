package com.embyverse.app.ui.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.embyverse.app.R
import com.embyverse.app.data.db.ChannelEntity
import com.embyverse.app.data.db.EpgEntity
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.di.AppContainer
import com.embyverse.app.ui.appViewModel
import com.embyverse.app.ui.components.CategoryChip
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.components.LoadingBox
import com.embyverse.app.ui.components.MediaImage
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date

private const val WINDOW_MIN = 180          // 3 horas visibles
private const val STEP_MIN = 90             // ◀ ▶ mueven hora y media

data class GuideRow(val channel: ChannelEntity, val programs: List<EpgEntity>)
data class GuideState(val loading: Boolean = true, val categories: List<String> = emptyList(), val category: String? = null,
                      val from: Long = 0, val to: Long = 0, val rows: List<GuideRow> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
class GuideViewModel(private val c: AppContainer) : ViewModel() {
    private val category = MutableStateFlow<String?>(null)
    /** Inicio de la ventana visible (segundos), alineado a la media hora. */
    private val from = MutableStateFlow(alignedNow())
    val focused = MutableStateFlow<Pair<ChannelEntity, EpgEntity?>?>(null)

    val state: StateFlow<GuideState> = combine(c.catalog.categories(ContentKind.LIVE), category, from) { cats, cat, f -> Triple(cats, cat, f) }
        .flatMapLatest { (cats, cat, f) ->
            c.catalog.channels(cat, 400).mapLatest { chans ->
                val to = f + WINDOW_MIN * 60
                val epg = HashMap<Pair<String, String>, MutableList<EpgEntity>>()
                chans.filter { it.epgChannelId != null }.groupBy { it.sourceId }.forEach { (sid, list) ->
                    list.mapNotNull { it.epgChannelId }.distinct().chunked(800).forEach { ids ->
                        c.database.epg().range(sid, ids, f, to).forEach { epg.getOrPut(sid to it.channelId) { ArrayList() } += it }
                    }
                }
                GuideState(false, cats, cat, f, to, chans.map { ch -> GuideRow(ch, ch.epgChannelId?.let { epg[ch.sourceId to it] }.orEmpty()) })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuideState())

    fun setCategory(cat: String?) { category.value = cat }
    fun shift(steps: Int) { from.value = maxOf(alignedNow(), from.value + steps * STEP_MIN * 60L) }

    companion object {
        fun alignedNow(): Long { val now = System.currentTimeMillis() / 1000; return now - now % 1800 }
    }
}

/**
 * Guía de TV en cuadrícula (como la del cable): canales hacia abajo y horas hacia la derecha.
 * Arriba se ve la información del programa enfocado. OK en un programa en curso = verlo.
 */
@Composable
fun GuideScreen(nav: Navigator) {
    val vm = appViewModel { GuideViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val focused by vm.focused.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val channelW = if (t.device.isTv) 220.dp else 150.dp

    Column(Modifier.fillMaxSize().testTag("guide")) {
        // Cabecera: título + información del programa enfocado.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = t.screenPadding, vertical = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineSmall, color = t.onBackground, fontWeight = FontWeight.Bold)
                val f = focused
                if (f != null) {
                    val p = f.second
                    Text(p?.title ?: f.first.name, style = MaterialTheme.typography.titleMedium, color = t.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(f.first.name, p?.let { "${fmt.format(Date(it.start * 1000))} – ${fmt.format(Date(it.end * 1000))}" }).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelMedium, color = t.textSecondary, maxLines = 1)
                    p?.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                } else Text(stringResource(R.string.guide_hint), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
            }
        }
        // Carpetas.
        LazyRow(contentPadding = PaddingValues(horizontal = t.screenPadding, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { CategoryChip(stringResource(R.string.category_all), s.category == null) { vm.setCategory(null) } }
            items(s.categories, key = { it }) { cat -> CategoryChip(cat, s.category == cat) { vm.setCategory(cat) } }
        }
        if (s.loading) { LoadingBox(); return@Column }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timelineW = maxWidth - channelW - t.screenPadding
            val dpPerSec = timelineW / (WINDOW_MIN * 60f)
            Column(Modifier.fillMaxSize()) {
                // Regla de horas con ◀ ▶.
                Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width(channelW), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, stringResource(R.string.guide_earlier), tint = t.onBackground) }
                        Icon(Icons.Rounded.Schedule, null, tint = t.textSecondary, modifier = Modifier.size(18.dp))
                        IconButton(onClick = { vm.shift(+1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, stringResource(R.string.guide_later), tint = t.onBackground) }
                    }
                    Box(Modifier.width(timelineW).fillMaxHeight()) {
                        // En pantallas angostas (celular) una marca por hora para que las horas no se encimen.
                        val step = if (dpPerSec * 1800f < 84.dp) 3600 else 1800
                        for (i in 0 until WINDOW_MIN * 60 / step) {
                            val sec = s.from + i * step
                            Text(fmt.format(Date(sec * 1000)), style = MaterialTheme.typography.labelMedium, color = t.textSecondary, maxLines = 1,
                                modifier = Modifier.offset(x = dpPerSec * (i * step.toFloat())).padding(start = 4.dp).align(Alignment.CenterStart))
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(s.rows, key = { "${it.channel.sourceId}|${it.channel.providerId}" }, contentType = { 0 }) { row ->
                        GuideRowView(row, s.from, s.to, channelW, timelineW, dpPerSec,
                            onFocus = { p -> vm.focused.value = row.channel to p },
                            onPlay = { nav.play(ItemRef(ContentKind.LIVE, row.channel.sourceId, row.channel.providerId), s.category) })
                    }
                }
            }
            // Línea de "ahora".
            val nowSec = System.currentTimeMillis() / 1000
            if (nowSec in s.from until s.to) Box(Modifier.offset(x = channelW + dpPerSec * (nowSec - s.from).toFloat()).width(2.dp).fillMaxHeight().background(t.primary.copy(alpha = 0.8f)))
        }
    }
}

@Composable
private fun GuideRowView(row: GuideRow, from: Long, to: Long, channelW: androidx.compose.ui.unit.Dp, timelineW: androidx.compose.ui.unit.Dp,
                         dpPerSec: androidx.compose.ui.unit.Dp, onFocus: (EpgEntity?) -> Unit, onPlay: () -> Unit) {
    val t = LocalEmby.current
    val ch = row.channel
    val nowSec = System.currentTimeMillis() / 1000
    Row(Modifier.fillMaxWidth().height(58.dp).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(channelW).fillMaxHeight().padding(start = t.screenPadding, end = 6.dp).background(t.surface, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 40.dp, height = 28.dp)) { MediaImage(ch.logoUrl, ContentKind.LIVE, Modifier.fillMaxSize(), ContentScale.Fit) }
            Spacer(Modifier.width(6.dp))
            Text(ch.name, style = MaterialTheme.typography.labelMedium, color = t.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.width(timelineW).fillMaxHeight()) {
            if (row.programs.isEmpty()) {
                // Sin guía: un solo bloque con el nombre del canal (en los de eventos dice el partido y la hora).
                GuideCell(ch.name, null, true, Modifier.fillMaxSize(), onFocus = { onFocus(null) }, onClick = onPlay)
            } else row.programs.forEach { p ->
                val start = maxOf(p.start, from); val end = minOf(p.end, to)
                if (end <= start) return@forEach
                val live = p.start <= nowSec && p.end > nowSec
                GuideCell(p.title, p, live,
                    Modifier.offset(x = dpPerSec * (start - from).toFloat()).width(dpPerSec * (end - start).toFloat()).fillMaxHeight(),
                    onFocus = { onFocus(p) }, onClick = { if (live || p.start <= nowSec) onPlay() else onFocus(p) })
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, p: EpgEntity?, live: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val t = LocalEmby.current
    FocusableSurface(onClick = onClick, focusScale = 1f, shape = RoundedCornerShape(6.dp),
        modifier = modifier.padding(horizontal = 1.dp).onFocusChanged { if (it.isFocused) onFocus() }.testTag("guide_cell")) { focused ->
        Box(Modifier.fillMaxSize().background(when { focused -> t.primary.copy(alpha = 0.35f); live -> t.surfaceVariant; else -> t.surface.copy(alpha = 0.75f) })
            .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = if (live) t.onBackground else t.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (focused) FontWeight.Bold else null)
            if (live && p != null) {
                val f = ((System.currentTimeMillis() / 1000 - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = 4.dp).fillMaxWidth(f).height(2.dp).background(t.primary))
            }
        }
    }
}

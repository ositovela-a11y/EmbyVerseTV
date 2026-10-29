package com.embyverse.app.ui.screens.settings

import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.embyverse.app.BuildConfig
import com.embyverse.app.R
import com.embyverse.app.data.db.ProfileEntity
import com.embyverse.app.data.prefs.PlaybackPrefs
import com.embyverse.app.data.repo.ConfigStatus
import com.embyverse.app.di.AppContainer
import com.embyverse.app.ui.appViewModel
import com.embyverse.app.ui.components.CategoryChip
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.navigation.Routes
import com.embyverse.app.ui.screens.library.ScreenTitle
import com.embyverse.app.ui.screens.sources.SubScreenTop
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.app.ui.theme.toColor
import com.embyverse.core.config.CardSize
import com.embyverse.core.config.ConfigResolver
import com.embyverse.core.config.UserAppearance
import com.embyverse.core.xtream.LiveFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Composable
private fun SettingsItem(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    val t = LocalEmby.current
    FocusableSurface(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) { focused ->
        Row(Modifier.fillMaxWidth().background(if (focused) t.surfaceVariant else t.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = t.accent)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = t.onBackground)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = t.textSecondary)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = LocalEmby.current
    FocusableSurface(onClick = { onChange(!checked) }, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) { focused ->
        Row(Modifier.fillMaxWidth().background(if (focused) t.surfaceVariant else t.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = t.onBackground)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
            }
            Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = t.primary, checkedThumbColor = t.buttonText))
        }
    }
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val t = LocalEmby.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxSize(), contentPadding = PaddingValues(t.screenPadding), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

// ------------------------------------------------------------------ MENÚ PRINCIPAL
@Composable
fun SettingsScreen(nav: Navigator) {
    var langDialog by remember { mutableStateOf(false) }
    val t = LocalEmby.current
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.nav_settings))
        SettingsList {
            item { SettingsItem(Icons.Rounded.Dns, stringResource(R.string.nav_sources), stringResource(R.string.settings_sources_sub)) { nav.go(Routes.SOURCES) } }
            item { SettingsItem(Icons.Rounded.Person, stringResource(R.string.profiles_title), stringResource(R.string.settings_profiles_sub)) { nav.go(Routes.PROFILES) } }
            item { SettingsItem(Icons.Rounded.Palette, stringResource(R.string.appearance_title), stringResource(R.string.settings_appearance_sub)) { nav.go(Routes.APPEARANCE) } }
            item { SettingsItem(Icons.Rounded.PlayCircle, stringResource(R.string.playback_title), stringResource(R.string.settings_playback_sub)) { nav.go(Routes.PLAYBACK) } }
            item { SettingsItem(Icons.Rounded.Favorite, stringResource(R.string.favorites_title), stringResource(R.string.settings_favorites_sub)) { nav.go(Routes.FAVORITES) } }
            item { SettingsItem(Icons.Rounded.Language, stringResource(R.string.language_title), currentLanguageLabel()) { langDialog = true } }
            item { SettingsItem(Icons.Rounded.Policy, stringResource(R.string.privacy_title), null) { nav.go(Routes.PRIVACY) } }
            item { SettingsItem(Icons.Rounded.Info, stringResource(R.string.about_title), stringResource(R.string.settings_about_sub, BuildConfig.VERSION_NAME)) { nav.go(Routes.ABOUT) } }
        }
    }
    if (langDialog) AlertDialog(
        onDismissRequest = { langDialog = false },
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column {
                listOf("" to R.string.language_system, "es" to R.string.language_es, "en" to R.string.language_en).forEach { (tag, label) ->
                    TextButton(onClick = {
                        AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                        langDialog = false
                    }) { Text(stringResource(label), color = t.onBackground) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { langDialog = false }) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun currentLanguageLabel(): String {
    val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    return stringResource(when { tag.startsWith("es") -> R.string.language_es; tag.startsWith("en") -> R.string.language_en; else -> R.string.language_system })
}

// ------------------------------------------------------------------ APARIENCIA (personalización del usuario)
@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModel(private val c: AppContainer) : ViewModel() {
    val config = c.config.config
    val appearance = c.prefs.activeProfileId.flatMapLatest { c.prefs.appearance(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserAppearance.ORIGINAL)

    fun update(f: (UserAppearance) -> UserAppearance) = viewModelScope.launch { c.prefs.updateAppearance(c.prefs.activeProfile(), f) }
    fun reset() = viewModelScope.launch { c.prefs.resetAppearance(c.prefs.activeProfile()) }
}

@Composable
fun AppearanceScreen(nav: Navigator) {
    val vm = appViewModel { AppearanceViewModel(it) }
    val cfg by vm.config.collectAsStateWithLifecycle()
    val a by vm.appearance.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    val uc = cfg.userCustomization
    Column(Modifier.fillMaxSize()) {
        SubScreenTop(stringResource(R.string.appearance_title), nav::back)
        SettingsList {
            item { Text(stringResource(R.string.appearance_intro), style = MaterialTheme.typography.bodyMedium, color = t.textSecondary) }
            if (uc.allowThemeSelection && cfg.themes.size > 1) {
                item { Text(stringResource(R.string.appearance_theme), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cfg.themes, key = { it.id }) { th ->
                            val pal = cfg.theme.palette.merge(th.palette)
                            val selected = (a.themeId ?: cfg.themes.first().id) == th.id
                            FocusableSurface(onClick = { vm.update { it.copy(themeId = th.id) } }, modifier = Modifier.width(140.dp)) { _ ->
                                Column(Modifier.background(pal.surface.toColor()).then(if (selected) Modifier.border(2.dp, t.focusRing, RoundedCornerShape(t.cardRadius)) else Modifier).padding(12.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(pal.primary, pal.secondary, pal.accent, pal.background).forEach { Box(Modifier.size(18.dp).background(it.toColor(), CircleShape)) }
                                    }
                                    Spacer(Modifier.size(8.dp))
                                    Text(th.name, style = MaterialTheme.typography.labelLarge, color = pal.onSurface.toColor(), maxLines = 2)
                                }
                            }
                        }
                    }
                }
            }
            if (uc.allowAccentColor && uc.accentChoices.isNotEmpty()) {
                item { Text(stringResource(R.string.appearance_accent), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            FocusableSurface(onClick = { vm.update { it.copy(accentColor = null) } }, shape = CircleShape) { _ ->
                                Box(Modifier.size(44.dp).background(t.surfaceVariant), contentAlignment = Alignment.Center) { Text("A", color = t.onSurface) }
                            }
                        }
                        items(uc.accentChoices) { col ->
                            FocusableSurface(onClick = { vm.update { it.copy(accentColor = col) } }, shape = CircleShape) { _ ->
                                Box(Modifier.size(44.dp).background(col.toColor()), contentAlignment = Alignment.Center) {
                                    if (a.accentColor == col) Icon(Icons.Rounded.Check, null, tint = Color(ConfigResolver.readableOn(col)))
                                }
                            }
                        }
                    }
                }
            }
            if (uc.allowCardSize) {
                item { Text(stringResource(R.string.appearance_card_size), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryChip(stringResource(R.string.appearance_default), a.cardSize == null) { vm.update { it.copy(cardSize = null) } }
                        CardSize.entries.forEach { size ->
                            val label = stringResource(when (size) { CardSize.S -> R.string.size_small; CardSize.M -> R.string.size_medium; CardSize.L -> R.string.size_large })
                            CategoryChip(label, a.cardSize == size) { vm.update { it.copy(cardSize = size) } }
                        }
                    }
                }
            }
            if (uc.allowHideSections || uc.allowReorderSections) {
                item { Text(stringResource(R.string.appearance_sections), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
                // Lista en el orden efectivo (incluidas las ocultas, en su posición original).
                val all = cfg.sections.filter { it.enabled && it.type != null }
                val visibleOrder = ConfigResolver.sections(cfg, a.copy(hiddenSections = emptySet())).map { it.id }
                val ordered = visibleOrder.mapNotNull { id -> all.firstOrNull { it.id == id } }
                itemsIndexed(ordered, key = { _, s -> s.id }) { idx, sec ->
                    val locked = sec.id in uc.lockedSections
                    val hidden = sec.id in a.hiddenSections && !locked
                    Row(Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(t.cardRadius)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (locked) Icon(Icons.Rounded.Lock, stringResource(R.string.appearance_locked), tint = t.textSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(sec.title.ifBlank { sec.rawType }, style = MaterialTheme.typography.titleSmall, color = if (hidden) t.textSecondary else t.onBackground, modifier = Modifier.weight(1f))
                        if (uc.allowReorderSections && !locked) {
                            IconButton(onClick = { vm.update { it.copy(sectionOrder = move(visibleOrder, sec.id, -1, uc.lockedSections)) } }, enabled = idx > 0) {
                                Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.action_move_up), tint = t.onBackground)
                            }
                            IconButton(onClick = { vm.update { it.copy(sectionOrder = move(visibleOrder, sec.id, +1, uc.lockedSections)) } }, enabled = idx < ordered.lastIndex) {
                                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.action_move_down), tint = t.onBackground)
                            }
                        }
                        if (uc.allowHideSections && !locked) Switch(checked = !hidden, onCheckedChange = { show ->
                            vm.update { it.copy(hiddenSections = if (show) it.hiddenSections - sec.id else it.hiddenSections + sec.id) }
                        }, colors = SwitchDefaults.colors(checkedTrackColor = t.primary, checkedThumbColor = t.buttonText))
                    }
                }
            }
            if (!uc.allowThemeSelection && !uc.allowAccentColor && !uc.allowCardSize && !uc.allowHideSections && !uc.allowReorderSections) {
                item { Text(stringResource(R.string.appearance_disabled_by_admin), color = t.textSecondary) }
            }
            item { Spacer(Modifier.size(8.dp)); EmbyButton(stringResource(R.string.appearance_reset), { vm.reset() }, secondary = true) }
        }
    }
}

/** Mueve una sección no bloqueada saltando las bloqueadas (que mantienen su posición). */
private fun move(order: List<String>, id: String, delta: Int, locked: Set<String>): List<String> {
    val movable = order.filter { it !in locked }.toMutableList()
    val i = movable.indexOf(id)
    val j = i + delta
    if (i < 0 || j !in movable.indices) return movable
    movable[i] = movable[j].also { movable[j] = movable[i] }
    return movable
}

// ------------------------------------------------------------------ REPRODUCCIÓN
class PlaybackViewModel(private val c: AppContainer) : ViewModel() {
    val prefs = c.prefs.playback.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackPrefs())
    fun update(f: (PlaybackPrefs) -> PlaybackPrefs) = viewModelScope.launch { c.prefs.updatePlayback(f) }
}

@Composable
fun PlaybackSettingsScreen(nav: Navigator) {
    val vm = appViewModel { PlaybackViewModel(it) }
    val p by vm.prefs.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    Column(Modifier.fillMaxSize()) {
        SubScreenTop(stringResource(R.string.playback_title), nav::back)
        SettingsList {
            item { Text(stringResource(R.string.playback_live_format), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryChip("MPEG-TS (.ts)", p.liveFormat == LiveFormat.TS) { vm.update { it.copy(liveFormat = LiveFormat.TS) } }
                    CategoryChip("HLS (.m3u8)", p.liveFormat == LiveFormat.HLS) { vm.update { it.copy(liveFormat = LiveFormat.HLS) } }
                }
            }
            item { Text(stringResource(R.string.playback_live_format_hint), style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
            item { SwitchRow(stringResource(R.string.playback_lock_landscape), null, p.lockLandscape) { v -> vm.update { it.copy(lockLandscape = v) } } }
            item { SwitchRow(stringResource(R.string.playback_autoplay_next), null, p.autoplayNextEpisode) { v -> vm.update { it.copy(autoplayNextEpisode = v) } } }
            item { SwitchRow(stringResource(R.string.playback_reconnect), stringResource(R.string.playback_reconnect_sub), p.autoReconnect) { v -> vm.update { it.copy(autoReconnect = v) } } }
            item { SwitchRow(stringResource(R.string.playback_subtitles), null, p.subtitlesEnabled) { v -> vm.update { it.copy(subtitlesEnabled = v) } } }
            item { Text(stringResource(R.string.playback_audio_lang), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
            item { LangChips(p.preferredAudioLanguage) { l -> vm.update { it.copy(preferredAudioLanguage = l) } } }
            item { Text(stringResource(R.string.playback_sub_lang), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
            item { LangChips(p.preferredSubtitleLanguage) { l -> vm.update { it.copy(preferredSubtitleLanguage = l) } } }
        }
    }
}

@Composable
private fun LangChips(selected: String?, onSelect: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryChip(stringResource(R.string.language_auto), selected == null) { onSelect(null) }
        listOf("es" to "Español", "en" to "English", "pt" to "Português", "fr" to "Français").forEach { (code, name) ->
            CategoryChip(name, selected == code) { onSelect(code) }
        }
    }
}

// ------------------------------------------------------------------ PERFILES
class ProfilesViewModel(private val c: AppContainer) : ViewModel() {
    val profiles = c.library.profiles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val active = c.prefs.activeProfileId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1L)
    fun select(id: Long) = viewModelScope.launch { c.prefs.setActiveProfile(id) }
    fun create(name: String) = viewModelScope.launch {
        val colors = listOf(0xFF7BE52F, 0xFF22D3EE, 0xFF1E6BFF, 0xFFA855F7, 0xFFF59E0B, 0xFFF43F5E)
        val id = c.library.createProfile(name, colors[profiles.value.size % colors.size].toInt())
        c.prefs.setActiveProfile(id)
    }
    fun rename(p: ProfileEntity, name: String) = viewModelScope.launch { c.library.renameProfile(p, name) }
    fun delete(id: Long) = viewModelScope.launch { c.library.deleteProfile(id) }
    fun clearHistory() = viewModelScope.launch { c.library.clearHistory() }
}

@Composable
fun ProfilesScreen(nav: Navigator) {
    val vm = appViewModel { ProfilesViewModel(it) }
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    var editing by remember { mutableStateOf<ProfileEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SubScreenTop(stringResource(R.string.profiles_title), nav::back)
        SettingsList {
            item { Text(stringResource(R.string.profiles_intro), style = MaterialTheme.typography.bodyMedium, color = t.textSecondary) }
            items(profiles, key = { it.id }) { p ->
                FocusableSurface(onClick = { vm.select(p.id) }, modifier = Modifier.fillMaxWidth(), focusScale = 1.02f) { focused ->
                    Row(Modifier.fillMaxWidth().background(if (focused) t.surfaceVariant else t.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).background(p.colorArgb.toColor(), CircleShape), contentAlignment = Alignment.Center) {
                            Text(p.name.take(1).uppercase(), color = Color(ConfigResolver.readableOn(p.colorArgb)), style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(p.name, style = MaterialTheme.typography.titleMedium, color = t.onBackground, modifier = Modifier.weight(1f))
                        if (p.id == active) Icon(Icons.Rounded.Check, stringResource(R.string.profile_active), tint = t.primary)
                        TextButton(onClick = { editing = p }) { Text(stringResource(R.string.action_edit), color = t.accent) }
                    }
                }
            }
            item { EmbyButton(stringResource(R.string.profile_create), { creating = true }, secondary = true) }
            item { EmbyButton(stringResource(R.string.history_clear), { vm.clearHistory() }, secondary = true) }
        }
    }
    if (creating) NameDialog(stringResource(R.string.profile_create), "", onDismiss = { creating = false }) { vm.create(it); creating = false }
    editing?.let { p ->
        NameDialog(stringResource(R.string.profile_edit), p.name, onDismiss = { editing = null },
            onDelete = if (profiles.size > 1) ({ vm.delete(p.id); editing = null }) else null) { vm.rename(p, it); editing = null }
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val t = LocalEmby.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(30) }, singleLine = true, label = { Text(stringResource(R.string.profile_name)) }) },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onConfirm(name) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = t.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

// ------------------------------------------------------------------ INFORMACIÓN, SOPORTE Y PRIVACIDAD
class AboutViewModel(private val c: AppContainer) : ViewModel() {
    val config = c.config.config
    val status = c.config.status
    fun check() = viewModelScope.launch { c.config.refresh() }
    fun rollback() = c.config.rollbackCurrent()
}

@Composable
fun AboutScreen(nav: Navigator) {
    val vm = appViewModel { AboutViewModel(it) }
    val cfg by vm.config.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val t = LocalEmby.current
    Column(Modifier.fillMaxSize()) {
        SubScreenTop(stringResource(R.string.about_title), nav::back)
        SettingsList {
            item { com.embyverse.app.ui.components.BrandLogo(cfg.brand.logoVariant, cfg.brand.logoUrl, Modifier.fillMaxWidth().size(160.dp)) }
            item { InfoLine(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") }
            item { InfoLine(stringResource(R.string.about_design_version), if (cfg.version == 0) stringResource(R.string.about_design_bundled) else "v${cfg.version}") }
            item { InfoLine(stringResource(R.string.about_design_status), statusText(status)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmbyButton(stringResource(R.string.about_check_design), { vm.check() }, secondary = true)
                    if (cfg.version > 0) EmbyButton(stringResource(R.string.about_rollback_design), { vm.rollback() }, secondary = true)
                }
            }
            item { Text(stringResource(R.string.support_title), style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SupportAgent, null, tint = t.accent); Spacer(Modifier.width(10.dp))
                    Text(cfg.text("support.contact", stringResource(R.string.support_default)), color = t.onSurface)
                }
            }
            item { Text(stringResource(R.string.about_disclaimer), style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
            item { Text(stringResource(R.string.about_licenses), style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
        }
    }
}

@Composable
private fun statusText(s: ConfigStatus): String = when (s) {
    ConfigStatus.NotConfigured -> stringResource(R.string.config_not_configured)
    ConfigStatus.Idle -> "—"
    ConfigStatus.Checking -> stringResource(R.string.config_checking)
    is ConfigStatus.UpToDate -> stringResource(R.string.config_up_to_date)
    is ConfigStatus.Updated -> stringResource(R.string.config_updated, s.version)
    is ConfigStatus.Rejected -> stringResource(R.string.config_rejected, s.reasons.take(3).joinToString("; "))
    is ConfigStatus.NeedsAppUpdate -> stringResource(R.string.config_needs_update)
    is ConfigStatus.Offline -> stringResource(R.string.config_offline)
    is ConfigStatus.RolledBack -> stringResource(R.string.config_rolled_back)
}

@Composable
private fun InfoLine(label: String, value: String) {
    val t = LocalEmby.current
    Row(Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(t.cardRadius)).padding(14.dp)) {
        Text(label, color = t.textSecondary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = t.onBackground, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PrivacyScreen(nav: Navigator) {
    val t = LocalEmby.current
    Column(Modifier.fillMaxSize()) {
        SubScreenTop(stringResource(R.string.privacy_title), nav::back)
        SettingsList {
            listOf(R.string.privacy_p1, R.string.privacy_p2, R.string.privacy_p3, R.string.privacy_p4, R.string.privacy_p5, R.string.privacy_p6).forEach { res ->
                item { Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = t.onSurface) }
            }
        }
    }
}

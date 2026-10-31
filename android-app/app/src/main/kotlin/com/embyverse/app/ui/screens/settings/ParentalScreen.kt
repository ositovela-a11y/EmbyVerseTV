package com.embyverse.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.R
import com.embyverse.app.data.repo.ParentalControl
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.components.PinDialog
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.screens.sources.SubScreenTop
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Control parental del PERFIL ACTIVO: PIN propio, bloqueo automático de contenido para adultos,
 * carpetas bloqueadas (TV, películas y series) y PIN al entrar al perfil.
 */
@Composable
fun ParentalScreen(nav: Navigator) {
    val c = LocalAppContainer.current
    val t = LocalEmby.current
    val scope = rememberCoroutineScope()
    val lockFlow = remember { c.parental.activeLock }
    val active by lockFlow.collectAsStateWithLifecycle(initialValue = null)
    val profilesFlow = remember { c.library.profiles }
    val profiles by profilesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val catsFlow = remember {
        combine(c.database.categories().observeNames(ContentKind.LIVE.name), c.database.categories().observeNames(ContentKind.MOVIE.name),
            c.database.categories().observeNames(ContentKind.SERIES.name)) { l, m, s -> Triple(l, m, s) }
    }
    val cats by catsFlow.collectAsStateWithLifecycle(initialValue = Triple(emptyList(), emptyList(), emptyList()))
    var verified by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) } // "create", "change", "remove"

    val (profileId, lock) = active ?: return
    val profileName = profiles.firstOrNull { it.id == profileId }?.name.orEmpty()
    fun update(block: (com.embyverse.app.data.prefs.ProfileLock) -> com.embyverse.app.data.prefs.ProfileLock) =
        scope.launch { c.prefs.updateLock(profileId, block) }

    // Si el perfil ya tiene PIN, hay que escribirlo para cambiar su control parental.
    if (lock.pin != null && !verified) {
        PinDialog(stringResource(R.string.parental_title), stringResource(R.string.parental_enter_pin, profileName), onDismiss = nav::back) { pin ->
            (pin == lock.pin).also { if (it) verified = true }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        SubScreenTop(stringResource(R.string.parental_title), nav::back)
        SettingsList {
            item {
                Row(Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(t.cardRadius)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = t.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.parental_profile, profileName), style = MaterialTheme.typography.titleMedium, color = t.onBackground)
                        Text(stringResource(R.string.parental_intro), style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                    }
                }
            }
            if (lock.pin == null) {
                item { EmbyButton(stringResource(R.string.parental_create_pin), { dialog = "create" }, modifier = Modifier.testTag("parental_create")) }
                return@SettingsList
            }
            item { SwitchRow(stringResource(R.string.kids_profile), stringResource(R.string.kids_profile_sub), lock.kids) { v -> update { it.copy(kids = v, pinOnEnter = if (v) it.pinOnEnter else it.pinOnEnter) } } }
            item { SwitchRow(stringResource(R.string.parental_adult), stringResource(R.string.parental_adult_sub), lock.blockAdult) { v -> update { it.copy(blockAdult = v) } } }
            item { SwitchRow(stringResource(R.string.parental_pin_on_enter), stringResource(R.string.parental_pin_on_enter_sub), lock.pinOnEnter) { v -> update { it.copy(pinOnEnter = v) } } }
            item {
                Row {
                    EmbyButton(stringResource(R.string.parental_change_pin), { dialog = "change" }, secondary = true)
                    Spacer(Modifier.width(12.dp))
                    EmbyButton(stringResource(R.string.parental_remove), { dialog = "remove" }, secondary = true)
                }
            }
            item { Text(stringResource(R.string.parental_folders), style = MaterialTheme.typography.titleMedium, color = t.onBackground, modifier = Modifier.padding(top = 8.dp)) }
            item { Text(stringResource(R.string.parental_folders_sub), style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
            listOf(R.string.nav_live to cats.first, R.string.nav_movies to cats.second, R.string.nav_series to cats.third).forEach { (label, names) ->
                if (names.isEmpty()) return@forEach
                item(key = "h$label") { Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = t.primary, modifier = Modifier.padding(top = 6.dp)) }
                items(names.distinct(), key = { "$label|$it" }) { name ->
                    val auto = lock.blockAdult && ParentalControl.isAdult(name)
                    SwitchRow(name, if (auto) stringResource(R.string.parental_auto_adult) else null, auto || name in lock.blocked) { v ->
                        if (!auto) update { it.copy(blocked = if (v) it.blocked + name else it.blocked - name) }
                    }
                }
            }
        }
    }

    when (dialog) {
        "create", "change" -> PinDialog(stringResource(R.string.parental_new_pin), stringResource(R.string.parental_new_pin_sub), confirm = true,
            onDismiss = { dialog = null }) { pin ->
            c.prefs.updateLock(profileId) { it.copy(pin = pin, blockAdult = if (dialog == "create") true else it.blockAdult) }
            verified = true; dialog = null; true
        }
        "remove" -> PinDialog(stringResource(R.string.parental_remove), stringResource(R.string.parental_remove_sub), onDismiss = { dialog = null }) { pin ->
            if (pin != lock.pin) false
            else { c.prefs.updateLock(profileId) { it.copy(pin = null, pinOnEnter = false, blocked = emptySet(), blockAdult = false) }; dialog = null; true }
        }
    }
}

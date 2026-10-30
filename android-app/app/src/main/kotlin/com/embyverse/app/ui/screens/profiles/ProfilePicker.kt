package com.embyverse.app.ui.screens.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.embyverse.app.R
import com.embyverse.app.data.db.ProfileEntity
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.FocusableSurface
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.ConfigResolver
import kotlinx.coroutines.launch

/**
 * «¿Quién está viendo?» al abrir la app cuando hay más de un perfil. Cada perfil guarda sus propios
 * favoritos, historial y «Continuar viendo», así lo que ve una persona no se mezcla con lo de otra.
 */
@Composable
fun ProfilePicker(profiles: List<ProfileEntity>, activeId: Long, onChosen: () -> Unit) {
    val t = LocalEmby.current
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.who_is_watching), style = MaterialTheme.typography.headlineMedium, color = t.onBackground)
        Spacer(Modifier.height(28.dp))
        val start = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            itemsIndexed(profiles, key = { _, p -> p.id }) { i, p ->
                FocusableSurface(onClick = { scope.launch { c.prefs.setActiveProfile(p.id); onChosen() } }, focusScale = 1.1f,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    modifier = if (i == start) Modifier.focusRequester(first) else Modifier) { focused ->
                    Column(Modifier.width(132.dp).background(if (focused) t.surfaceVariant else Color.Transparent).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(96.dp).background(Color(p.colorArgb), CircleShape), contentAlignment = Alignment.Center) {
                            Text(p.name.take(1).uppercase(), color = Color(ConfigResolver.readableOn(p.colorArgb)), style = MaterialTheme.typography.displaySmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(p.name, style = MaterialTheme.typography.titleMedium, color = if (focused) t.onBackground else t.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.profiles_intro), style = MaterialTheme.typography.bodySmall, color = t.textSecondary, textAlign = TextAlign.Center)
    }
}

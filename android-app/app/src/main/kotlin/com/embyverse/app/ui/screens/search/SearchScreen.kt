package com.embyverse.app.ui.screens.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.embyverse.app.R
import com.embyverse.app.data.repo.SearchResults
import com.embyverse.app.data.repo.toCard
import com.embyverse.app.di.AppContainer
import com.embyverse.app.ui.appViewModel
import com.embyverse.app.ui.components.CardRow
import com.embyverse.app.ui.components.MessageState
import com.embyverse.app.ui.components.SectionHeader
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.screens.library.ScreenTitle
import com.embyverse.app.ui.screens.library.SearchField
import com.embyverse.app.ui.theme.LocalEmby
import com.embyverse.core.config.CardAspect
import com.embyverse.core.config.CardSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val results = query.debounce(300).mapLatest { q -> if (q.isBlank()) null else c.catalog.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null as SearchResults?)
}

@Composable
fun SearchScreen(nav: Navigator) {
    val vm = appViewModel { SearchViewModel(it) }
    val results by vm.results.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val t = LocalEmby.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val voiceAvailable = remember { android.speech.SpeechRecognizer.isRecognitionAvailable(context) ||
        context.packageManager.queryIntentActivities(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty() }
    val voicePrompt = stringResource(R.string.search_voice_prompt)
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said -> text = said; vm.query.value = said }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle(stringResource(R.string.nav_search)) }
        item {
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = t.screenPadding), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                SearchField(text, { text = it; vm.query.value = it }, stringResource(R.string.search_everything),
                    Modifier.weight(1f).focusRequester(focus))
                // Búsqueda por voz (micrófono del control de Android TV o del celular).
                if (voiceAvailable) {
                    Spacer(Modifier.width(8.dp))
                    com.embyverse.app.ui.components.FocusableSurface(onClick = {
                        runCatching { voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, voicePrompt)) }
                    }, shape = androidx.compose.foundation.shape.CircleShape, modifier = Modifier.testTag("voice_search")) { _ ->
                        androidx.compose.foundation.layout.Box(Modifier.size(52.dp).background(t.primary), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Icon(Icons.Rounded.Mic, stringResource(R.string.search_voice), tint = t.onPrimary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        val r = results
        if (r != null && r.isEmpty) item { MessageState(Icons.Rounded.SearchOff, stringResource(R.string.search_no_results), null) }
        if (r != null && r.channels.isNotEmpty()) item {
            Column { SectionHeader(stringResource(R.string.nav_live), null, null); CardRow(r.channels.map { it.toCard() }, CardAspect.LANDSCAPE, CardSize.S) { nav.openItem(it.ref) } }
        }
        if (r != null && r.movies.isNotEmpty()) item {
            Column { SectionHeader(stringResource(R.string.nav_movies), null, null); CardRow(r.movies.map { it.toCard() }, CardAspect.POSTER, CardSize.M) { nav.openItem(it.ref) } }
        }
        if (r != null && r.series.isNotEmpty()) item {
            Column { SectionHeader(stringResource(R.string.nav_series), null, null); CardRow(r.series.map { it.toCard() }, CardAspect.POSTER, CardSize.M) { nav.openItem(it.ref) } }
        }
    }
}

package com.embyverse.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.LoadingBox
import com.embyverse.app.ui.components.NebulaBackground
import com.embyverse.app.ui.navigation.AppShell
import com.embyverse.app.ui.screens.splash.SplashScreen
import com.embyverse.app.ui.theme.EmbyTheme
import com.embyverse.core.config.ConfigResolver
import com.embyverse.core.config.UserAppearance
import kotlinx.coroutines.flow.map

/**
 * Actividad principal (AppCompat para el cambio de idioma por app en Android < 13).
 * El diseño se construye a partir de la configuración remota validada + preferencias del perfil.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EmbyVerseApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                val config by container.config.config.collectAsStateWithLifecycle()
                val profile by container.prefs.activeProfileId.collectAsStateWithLifecycle(initialValue = 1L)
                val appearanceFlow = remember(profile) { container.prefs.appearance(profile) }
                val appearance by appearanceFlow.collectAsStateWithLifecycle(initialValue = UserAppearance.ORIGINAL)
                val theme = remember(config, appearance) { ConfigResolver.theme(config, appearance) }
                val hasSourcesFlow = remember { container.sources.sources.map { it.isNotEmpty() } }
                val hasSources by hasSourcesFlow.collectAsStateWithLifecycle(initialValue = null)
                var splashDone by rememberSaveable { mutableStateOf(false) }
                var crash by remember { mutableStateOf(CrashReport.take(this@MainActivity)) }

                EmbyTheme(theme) {
                    NebulaBackground(Modifier.fillMaxSize()) {
                        when {
                            !splashDone -> SplashScreen(config.brand) { splashDone = true }
                            hasSources == null -> LoadingBox()
                            else -> {
                                // La decisión de mostrar la configuración inicial se toma una sola vez por sesión.
                                val startOnboarding = rememberSaveable { hasSources == false }
                                AppShell(startOnboarding, config.brand.logoVariant, config.brand.logoUrl)
                            }
                        }
                    }
                    crash?.let { report -> CrashDialog(report) { crash = null } }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CrashDialog(report: String, onClose: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.crash_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.crash_message))
                androidx.compose.material3.Text(
                    report.take(1200), style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp).heightIn(max = 220.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(report)); copied = true }) {
                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(if (copied) R.string.crash_copied else R.string.crash_copy))
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_close)) } },
    )
}

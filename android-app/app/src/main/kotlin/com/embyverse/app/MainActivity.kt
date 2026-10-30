package com.embyverse.app

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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
import androidx.compose.ui.graphics.graphicsLayer
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
    private val repeatGate = com.embyverse.app.ui.KeyRepeatGate()

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        onUserInput()
        return if (repeatGate.shouldDrop(event)) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean { onUserInput(); return super.dispatchTouchEvent(ev) }

    // ---- Pantalla encendida en los menús ----
    // Android TV activa el protector de pantalla (o apaga la TV) tras unos minutos sin tocar el control.
    // En la app la pantalla se mantiene encendida hasta [awakeMs] después de la última pulsación
    // (Ajustes › Reproducción); al reproducir, el reproductor la mantiene encendida siempre.
    private var lastInput = android.os.SystemClock.uptimeMillis()
    private var awakeMs = 120 * 60_000L

    private fun onUserInput() {
        lastInput = android.os.SystemClock.uptimeMillis()
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun startAwakeWatcher(container: com.embyverse.app.di.AppContainer) {
        lifecycleScope.launch {
            container.prefs.playback.collect { awakeMs = if (it.menuAwakeMinutes < 0) Long.MAX_VALUE else it.menuAwakeMinutes * 60_000L }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                while (true) {
                    kotlinx.coroutines.delay(30_000)
                    if (android.os.SystemClock.uptimeMillis() - lastInput > awakeMs)
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as EmbyVerseApp).container
        startAwakeWatcher(container)
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
                val profiles by container.library.profiles.collectAsStateWithLifecycle(initialValue = null)
                var profileChosen by rememberSaveable { mutableStateOf(false) }
                var crash by remember { mutableStateOf(CrashReport.take(this@MainActivity)) }

                EmbyTheme(theme) { com.embyverse.app.ui.FastScrolling {
                    NebulaBackground(Modifier.fillMaxSize()) {
                        // Marca de agua: el logotipo apenas visible al fondo de toda la app.
                        if (splashDone) com.embyverse.app.ui.components.BrandLogo(config.brand.logoVariant, config.brand.logoUrl,
                            Modifier.align(androidx.compose.ui.Alignment.Center).fillMaxSize(0.5f)
                                .graphicsLayer { alpha = 0.045f })
                        when {
                            !splashDone -> SplashScreen(config.brand) { splashDone = true }
                            hasSources == null -> LoadingBox()
                            // Varios perfiles: se elige quién ve, así favoritos e historial no se mezclan.
                            !profileChosen && hasSources == true && (profiles?.size ?: 0) > 1 ->
                                com.embyverse.app.ui.screens.profiles.ProfilePicker(profiles!!, profile) { profileChosen = true }
                            else -> {
                                // La decisión de mostrar la configuración inicial se toma una sola vez por sesión.
                                val startOnboarding = rememberSaveable { hasSources == false }
                                AppShell(startOnboarding, config.brand.logoVariant, config.brand.logoUrl)
                            }
                        }
                    }
                    crash?.let { report -> CrashDialog(report) { crash = null } }
                    // Versión nueva de la app: se ofrece al abrir o al recuperar internet.
                    if (splashDone && crash == null) com.embyverse.app.ui.components.UpdateDialog(container.updater)
                    // Mensaje del administrador / vendedor (una vez por dispositivo, mientras esté vigente).
                    if (splashDone && crash == null) {
                        val seen by container.prefs.seenNotices.collectAsStateWithLifecycle(initialValue = null)
                        val now = System.currentTimeMillis() / 1000
                        val notice = seen?.let { s -> config.messages.firstOrNull { it.isActive(now) && it.id !in s } }
                        val scope = androidx.compose.runtime.rememberCoroutineScope()
                        notice?.let { n ->
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { scope.launch { container.prefs.markNoticeSeen(n.id) } },
                                title = { androidx.compose.material3.Text(n.title) },
                                text = { androidx.compose.material3.Text(n.body) },
                                confirmButton = { androidx.compose.material3.TextButton(onClick = { scope.launch { container.prefs.markNoticeSeen(n.id) } }) {
                                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_ok)) } },
                            )
                        }
                    }
                } }
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

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

    // ---- Protector de pantalla propio y sonidos de navegación ----
    private val idleTick = kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val screensaverOn = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var swallowUp = false
    private var navSounds = false
    private var sounds: android.media.SoundPool? = null
    private var sMove = 0
    private var sSelect = 0

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        onUserInput()
        // Cualquier tecla cierra el protector de pantalla (y no hace nada más).
        if (screensaverOn.value && event.action == android.view.KeyEvent.ACTION_DOWN) { screensaverOn.value = false; swallowUp = true; return true }
        if (swallowUp && event.action == android.view.KeyEvent.ACTION_UP) { swallowUp = false; return true }
        if (repeatGate.shouldDrop(event)) return true
        if (navSounds && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount % 3 == 0) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> sounds?.play(sMove, 0.35f, 0.35f, 0, 0, 1f)
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) sounds?.play(sSelect, 0.4f, 0.4f, 0, 0, 1f)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        onUserInput()
        if (screensaverOn.value) { if (ev.action == android.view.MotionEvent.ACTION_UP) screensaverOn.value = false; return true }
        return super.dispatchTouchEvent(ev)
    }

    private fun setupNavSounds(container: com.embyverse.app.di.AppContainer) {
        val tv = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        if (!tv) return
        lifecycleScope.launch {
            container.prefs.playback.collect { p ->
                navSounds = p.navSounds
                if (p.navSounds && sounds == null) runCatching {
                    sounds = android.media.SoundPool.Builder().setMaxStreams(2).setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
                        .also { sp -> sMove = sp.load(this@MainActivity, R.raw.nav_move, 1); sSelect = sp.load(this@MainActivity, R.raw.nav_select, 1) }
                }
            }
        }
    }

    override fun onDestroy() { sounds?.release(); sounds = null; super.onDestroy() }

    /** «Ver ahora» de un recordatorio: abre directo el canal. */
    private fun handlePlayIntent(intent: android.content.Intent?) {
        val sid = intent?.getStringExtra(com.embyverse.app.data.repo.Reminders.EXTRA_SOURCE) ?: return
        val cid = intent.getStringExtra(com.embyverse.app.data.repo.Reminders.EXTRA_CHANNEL) ?: return
        intent.removeExtra(com.embyverse.app.data.repo.Reminders.EXTRA_SOURCE)
        com.embyverse.app.data.repo.Reminders.due.value = null
        com.embyverse.app.player.PlayerActivity.start(this, com.embyverse.app.data.repo.ItemRef(com.embyverse.core.model.ContentKind.LIVE, sid, cid))
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handlePlayIntent(intent) }

    // ---- Pantalla encendida en los menús ----
    // Android TV activa el protector de pantalla (o apaga la TV) tras unos minutos sin tocar el control.
    // En la app la pantalla se mantiene encendida hasta [awakeMs] después de la última pulsación
    // (Ajustes › Reproducción); al reproducir, el reproductor la mantiene encendida siempre.
    private var lastInput = android.os.SystemClock.uptimeMillis()
    private var awakeMs = 120 * 60_000L

    private fun onUserInput() {
        lastInput = android.os.SystemClock.uptimeMillis()
        idleTick.value = lastInput
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
        setupNavSounds(container)
        if (savedInstanceState == null) handlePlayIntent(intent)
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                val config by container.config.config.collectAsStateWithLifecycle()
                val profile by container.prefs.activeProfileId.collectAsStateWithLifecycle(initialValue = 1L)
                val appearanceFlow = remember(profile) { container.prefs.appearance(profile) }
                val appearance by appearanceFlow.collectAsStateWithLifecycle(initialValue = UserAppearance.ORIGINAL)
                val kidsFlow = remember { container.parental.activeLock.map { it.second.kids } }
                val kids by kidsFlow.collectAsStateWithLifecycle(initialValue = false)
                val theme = remember(config, appearance, kids) {
                    ConfigResolver.theme(config, appearance).let { base ->
                        // Perfil infantil: estilo alegre (naranja, tarjetas grandes) sin importar el tema elegido.
                        if (kids) com.embyverse.core.config.STYLE_THEMES.firstOrNull { it.id == "style_naranja" }?.transform?.invoke(base) ?: base else base
                    }
                }
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
                    // Fondo de temporada activado desde el panel (Navidad, Halloween, Día de Muertos, Mundial).
                    if (splashDone) com.embyverse.app.ui.components.SeasonLayer(config.texts)
                    // Protector de pantalla propio tras 5 min sin usar el control (en los menús).
                    val pb by container.prefs.playback.collectAsStateWithLifecycle(initialValue = null)
                    val tick by idleTick.collectAsStateWithLifecycle()
                    val ssOn by screensaverOn.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(tick, pb?.screensaver, splashDone) {
                        if (splashDone && pb?.screensaver == true) { kotlinx.coroutines.delay(5 * 60_000L); screensaverOn.value = true }
                    }
                    if (ssOn) com.embyverse.app.ui.components.Screensaver()
                    // Recordatorio de partido: aviso dentro de la app con «Ver ahora».
                    val due by com.embyverse.app.data.repo.Reminders.due.collectAsStateWithLifecycle()
                    due?.let { d ->
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { com.embyverse.app.data.repo.Reminders.due.value = null },
                            title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.reminder_title, com.embyverse.app.data.repo.Reminders.BEFORE_MIN)) },
                            text = { androidx.compose.material3.Text(d.title) },
                            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                                com.embyverse.app.data.repo.Reminders.due.value = null
                                com.embyverse.app.player.PlayerActivity.start(this@MainActivity, com.embyverse.app.data.repo.ItemRef(com.embyverse.core.model.ContentKind.LIVE, d.sourceId, d.channelId))
                            }) { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_watch)) } },
                            dismissButton = { androidx.compose.material3.TextButton(onClick = { com.embyverse.app.data.repo.Reminders.due.value = null }) {
                                androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.action_close)) } },
                        )
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

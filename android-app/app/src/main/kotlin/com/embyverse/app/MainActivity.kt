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
                }
            }
        }
    }
}

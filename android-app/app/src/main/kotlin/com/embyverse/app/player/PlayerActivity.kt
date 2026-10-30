package com.embyverse.app.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.embyverse.app.EmbyVerseApp
import com.embyverse.app.data.repo.ItemRef
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.theme.EmbyTheme
import com.embyverse.core.config.ConfigResolver
import com.embyverse.core.config.UserAppearance
import com.embyverse.core.model.ContentKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Reproductor a pantalla completa (Media3/ExoPlayer). Sin anuncios ni banners: la publicidad
 * propia sólo aparece en la pantalla de inicio.
 *
 * El reproductor se crea en onStart y se libera en onStop para no retener decodificadores ni
 * memoria cuando la actividad no está visible.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_ID = "id"
        private const val EXTRA_CONTEXT = "context"

        fun start(context: Context, ref: ItemRef, ctx: String? = null) {
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_KIND, ref.kind.name).putExtra(EXTRA_SOURCE, ref.sourceId)
                .putExtra(EXTRA_ID, ref.id).putExtra(EXTRA_CONTEXT, ctx))
        }
    }

    private lateinit var vm: PlayerViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as EmbyVerseApp).container
        val ref = ItemRef(
            ContentKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: ContentKind.LIVE.name),
            intent.getStringExtra(EXTRA_SOURCE).orEmpty(), intent.getStringExtra(EXTRA_ID).orEmpty(),
        )
        val ctx = intent.getStringExtra(EXTRA_CONTEXT)
        vm = ViewModelProvider(this, viewModelFactory { initializer { PlayerViewModel(container, ref, ctx) } })[PlayerViewModel::class.java]

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        lifecycleScope.launch {
            if (container.prefs.playback.first().lockLandscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                val config by container.config.config.collectAsStateWithLifecycle()
                EmbyTheme(ConfigResolver.theme(config, UserAppearance.ORIGINAL)) {
                    PlayerScreen(vm, onClose = { finish() })
                }
            }
        }
    }

    override fun onStart() { super.onStart(); vm.attach(this) }
    override fun onStop() { vm.detach(); super.onStop() }

    /** Mando a distancia: CH+/CH- (y arriba/abajo con los controles ocultos) cambian de canal en vivo. */
    /** Teclas ya atendidas en ACTION_DOWN: su ACTION_UP también se consume para que no lleguen al PlayerView. */
    private val consumed = mutableSetOf<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        PlaybackProbe.lastKey = "${event.keyCode}/${event.action} live=${vm.isLive} list=${vm.channelListVisible.value} ctl=${vm.controlsVisible.value}"
        if (event.action == KeyEvent.ACTION_UP && consumed.remove(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_DOWN && vm.isLive && handleLiveKey(event.keyCode)) {
            consumed += event.keyCode
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleLiveKey(code: Int): Boolean {
        val list = vm.channelListVisible.value
        val ctl = vm.controlsVisible.value
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> vm.zap(+1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> vm.zap(-1)
            KeyEvent.KEYCODE_DPAD_UP -> if (!ctl && !list) vm.zap(-1) else return false
            KeyEvent.KEYCODE_DPAD_DOWN -> if (!ctl && !list) vm.zap(+1) else return false
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_MENU -> vm.toggleChannelList()
            // ◀, u OK con los controles ocultos: lista de canales para elegir otro sin salir.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!list) openList() else return false
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (!list && !ctl) openList() else return false
            else -> return false
        }
        return true
    }

    private fun openList() {
        vm.controlsVisible.value = false
        if (!vm.channelListVisible.value) vm.toggleChannelList()
    }
}

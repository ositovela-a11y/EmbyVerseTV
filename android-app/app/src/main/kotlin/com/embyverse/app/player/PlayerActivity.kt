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
                    com.embyverse.app.ui.FastScrolling { PlayerScreen(vm, onClose = { finish() }, onPip = if (pipSupported()) ({ enterPip() }) else null) }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); vm.attach(this) }

    /** Imagen en imagen (celular/tableta): al salir con INICIO el video sigue en una ventanita. */
    private fun pipSupported() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    fun enterPip(): Boolean {
        if (!pipSupported() || vm.player.value == null) return false
        return runCatching {
            val size = vm.player.value?.videoSize
            val ratio = if (size != null && size.width > 0 && size.height > 0)
                android.util.Rational(size.width, size.height).let { r -> if (r.toFloat() in 0.42f..2.38f) r else android.util.Rational(16, 9) }
            else android.util.Rational(16, 9)
            enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        }.getOrDefault(false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (vm.player.value?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.inPip.value = isInPictureInPictureMode
        if (isInPictureInPictureMode) { vm.showOptions(false); if (vm.channelListVisible.value) vm.toggleChannelList() }
    }
    override fun onStop() { vm.detach(); super.onStop() }

    private val repeatGate = com.embyverse.app.ui.KeyRepeatGate()
    /** Teclas ya atendidas en ACTION_DOWN: su ACTION_UP también se consume para que no lleguen al PlayerView. */
    private val consumed = mutableSetOf<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        PlaybackProbe.lastKey = "${event.keyCode}/${event.action} live=${vm.isLive} list=${vm.channelListVisible.value} ctl=${vm.controlsVisible.value}"
        if (repeatGate.shouldDrop(event)) return true
        if (event.action == KeyEvent.ACTION_UP && consumed.remove(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_DOWN && handleKey(event)) {
            consumed += event.keyCode
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Mando a distancia:
     *  - OK (sin nada en pantalla): opciones — audio, subtítulos y, en vivo, lista de canales.
     *  - ◀ en vivo: lista de canales; dentro de la lista ◀ ▶ cambian de carpeta.
     *  - ▲▼ / CH± en vivo: canal anterior / siguiente.  MENÚ: opciones.  GUÍA: lista de canales.
     */
    private fun handleKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val list = vm.channelListVisible.value
        val opts = vm.optionsVisible.value
        val ctl = vm.controlsVisible.value
        val idle = !list && !opts && !ctl
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_CAPTIONS -> { vm.showOptions(!opts); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (idle && vm.isLive) { vm.showOptions(true); return true }
        }
        if (!vm.isLive) return false
        // Teclear el número del canal (0-9 del control o teclado numérico).
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && !list && !opts) { vm.typeDigit(digit); return true }
        when (code) {
            KeyEvent.KEYCODE_CHANNEL_UP -> vm.zap(+1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> vm.zap(-1)
            KeyEvent.KEYCODE_GUIDE -> vm.toggleChannelList()
            KeyEvent.KEYCODE_DPAD_UP -> if (idle) vm.zap(-1) else return false
            KeyEvent.KEYCODE_DPAD_DOWN -> if (idle) vm.zap(+1) else return false
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                list -> vm.shiftListCategory(-1)
                idle -> vm.openChannelList()
                else -> return false
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (list) vm.shiftListCategory(+1) else return false
            else -> return false
        }
        return true
    }
}

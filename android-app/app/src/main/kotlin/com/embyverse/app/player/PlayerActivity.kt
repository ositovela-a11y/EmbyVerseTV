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
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
        // Equipo bloqueado desde el panel mientras ve algo: se cierra el video (la pantalla de bloqueo queda detrás).
        com.embyverse.app.data.repo.DeviceBlock.init(this)
        lifecycleScope.launch { com.embyverse.app.data.repo.DeviceBlock.blocked.collect { if (it == true) finish() } }
        lifecycleScope.launch {
            if (container.prefs.playback.first().lockLandscape) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                val config by container.config.config.collectAsStateWithLifecycle()
                EmbyTheme(ConfigResolver.theme(config, UserAppearance.ORIGINAL)) {
                    com.embyverse.app.ui.FastScrolling { PlayerScreen(vm, onClose = { finish() }, onPip = if (pipSupported()) ({ enterPip() }) else null, castEnabled = castOk) }
                }
            }
        }
    }

    // ---- Chromecast (solo celulares con Google Play Services) ----
    private val castListener by lazy {
        CastSupport.listener(onStart = { session ->
            val np = vm.nowPlaying.value ?: return@listener
            val pos = vm.player.value?.currentPosition ?: 0L
            CastSupport.load(session, np.url, np.title, np.subtitle, np.imageUrl, vm.isLive, pos)
            vm.player.value?.pause()
            vm.castingTo.value = session.castDevice?.friendlyName ?: "Chromecast"
        }, onEnd = { vm.castingTo.value = null; vm.player.value?.play() })
    }
    private val castOk by lazy { CastSupport.available(this) }

    override fun onResume() {
        super.onResume()
        if (castOk) runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(this).sessionManager
            .addSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java) }
    }

    override fun onPause() {
        if (castOk) runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(this).sessionManager
            .removeSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java) }
        super.onPause()
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
        PlaybackProbe.lastKey = "${event.keyCode}/${event.action} live=${vm.isLive} list=${vm.channelListVisible.value} ctl=${vm.controlsVisible.value} enPantalla=${vm.playerView?.get()?.isControllerFullyVisible} foco=${currentFocus?.javaClass?.simpleName}"
        if (repeatGate.shouldDrop(event)) return true
        // Nada tiene el foco (pasa en algunos Fire TV): se le da al video para que las siguientes teclas lleguen.
        if (event.action == KeyEvent.ACTION_DOWN && currentFocus == null) vm.playerView?.get()?.let { v -> v.post { if (currentFocus == null) v.requestFocus() } }
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
        // Estado REAL de los controles en pantalla (el aviso de «visibles/ocultos» llega tarde mientras se animan).
        val ctl = vm.playerView?.get()?.let { it.useController && it.isControllerFullyVisible } ?: vm.controlsVisible.value
        val idle = !list && !opts && !ctl
        when (code) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_CAPTIONS -> { vm.showOptions(!opts); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                val skip = vm.skipIntroAction
                if (!ctl && !opts && skip != null) { skip(); return true }
                if (code != KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD && idle && vm.isLive) { vm.showOptions(true); return true }
            }
        }
        if (handleMediaKey(code)) return true
        if (!vm.isLive && handleVodKey(code, list || opts)) return true
        if (ctl && !list && !opts && isDpad(code) && rescueControllerFocus()) return code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER
        if (!vm.isLive) return false
        // Teclear el número del canal (0-9 del control o teclado numérico).
        val digit = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0 && !list && !opts) { vm.typeDigit(digit); return true }
        when (code) {
            KeyEvent.KEYCODE_LAST_CHANNEL -> vm.previousChannel()
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

    private fun isDpad(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT ||
        code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER

    /**
     * En algunos Fire TV Stick (y otros Android TV) al abrir el video nada de la pantalla tiene el foco:
     * Android solo entrega VOLUMEN y ATRÁS, y las flechas, OK, ▶❚❚, ⏪ ⏩ «no hacen nada». Por eso las
     * teclas del reproductor se atienden aquí mismo, sin depender de quién tenga el foco.
     */
    private fun handleMediaKey(code: Int): Boolean {
        val p = vm.player.value ?: return false
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { if (p.isPlaying) p.pause() else p.play() }
            KeyEvent.KEYCODE_MEDIA_PLAY -> p.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.pause()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (vm.isLive) return false else seekBy(+SEEK_MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (vm.isLive) return false else seekBy(-SEEK_MS)
            else -> return false
        }
        if (!vm.isLive) showControls(focus = false)
        return true
    }

    /**
     * Película o serie cuando el foco NO está en los controles (estén o no en pantalla): ◀ ▶ retroceden /
     * adelantan 10 s y ▲ ▼ OK muestran los controles con el foco en ▶❚❚. Con el foco ya en los controles,
     * las flechas se mueven entre sus botones como siempre.
     */
    private fun handleVodKey(code: Int, panelOpen: Boolean): Boolean {
        if (panelOpen || vm.error.value != null || vm.upNextShowing || vm.nextEpisode.value != null || vm.player.value == null) return false
        if (focusInVideo() || (currentFocus != null && vm.screenHasFocus && !vm.videoHasFocus)) return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-SEEK_MS); showControls(focus = true) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(+SEEK_MS); showControls(focus = true) }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> showControls(focus = true)
            else -> return false
        }
        return true
    }

    private fun seekBy(ms: Long) {
        val p = vm.player.value ?: return
        val d = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + ms).coerceIn(0L, d))
    }

    private fun showControls(focus: Boolean) {
        val v = vm.playerView?.get() ?: return
        if (!v.useController) return
        v.showController()
        vm.controlsVisible.value = true
        if (focus) v.post { rescueControllerFocus() }
    }

    /** El foco está dentro de la vista del video o sus controles (no en «nada» ni en la vista misma sin botón). */
    private fun focusInVideo(): Boolean {
        val v = vm.playerView?.get() ?: return false
        val focused = currentFocus
        if (focused == null || focused === v) return false // la vista sola no es un botón de los controles
        var f: android.view.View? = focused
        while (f != null) { if (f === v) return true; f = f.parent as? android.view.View }
        return false
    }

    /** Si los controles están visibles pero el foco no está en ellos, se lleva a ▶❚❚. Devuelve true si tuvo que moverlo. */
    private fun rescueControllerFocus(): Boolean {
        val v = vm.playerView?.get() ?: return false
        val focused = currentFocus
        var f: android.view.View? = focused
        while (f != null) { if (f === v) return false; f = f.parent as? android.view.View }
        // Un botón de la pantalla (atrás, opciones, siguiente episodio…) tiene el foco: no se le quita.
        if (focused != null && vm.screenHasFocus && !vm.videoHasFocus) return false
        val target = v.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play_pause)?.takeIf { it.isShown }
            ?: v.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_play)?.takeIf { it.isShown }
            ?: v.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_pause)?.takeIf { it.isShown }
        val ok = target?.requestFocus() == true || v.requestFocus()
        if (ok) PlaybackProbe.focusRescues++
        return ok
    }
}

private const val SEEK_MS = 10_000L

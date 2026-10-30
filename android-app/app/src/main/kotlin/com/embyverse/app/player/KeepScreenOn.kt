package com.embyverse.app.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.Player

/**
 * Mientras haya video reproduciéndose (o cargando), la pantalla NO se apaga ni entra el protector de
 * pantalla del TV aunque nadie toque el control. Al pausar, terminar o salir, vuelve el comportamiento normal.
 */
@Composable
fun KeepScreenOnWhilePlaying(player: Player?) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(player, activity) {
        val window = activity?.window
        fun update() {
            val p = player ?: return
            val active = p.playWhenReady && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
            if (active) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) { update() }
        }
        player?.addListener(listener)
        update()
        onDispose {
            player?.removeListener(listener)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

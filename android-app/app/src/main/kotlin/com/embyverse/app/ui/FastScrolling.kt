package com.embyverse.app.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.abs
import kotlin.math.min

/**
 * Desplazamiento rápido al moverse con el control (TV) o el teclado.
 * El desplazamiento por defecto de Compose usa un "resorte" lento: con el control se veía lento y
 * borroso. Aquí cada paso dura ~110 ms y se deja ver un poco del siguiente elemento.
 */
@OptIn(ExperimentalFoundationApi::class)
private object FastBringIntoView : BringIntoViewSpec {
    private val spec: AnimationSpec<Float> = tween(110, easing = FastOutSlowInEasing)

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> get() = spec

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Margen para que se asome el elemento siguiente / anterior.
        val margin = min(size * 0.6f, containerSize * 0.15f)
        val leading = offset - margin
        val trailing = offset + size + margin
        return when {
            leading >= 0 && trailing <= containerSize -> 0f
            size + 2 * margin > containerSize -> offset // elemento muy grande: alinear al inicio
            abs(leading) < abs(trailing - containerSize) -> leading
            else -> trailing - containerSize
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FastScrolling(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides FastBringIntoView, content = content)
}

/**
 * Al mantener pulsada una flecha, el control repite la tecla muy rápido; si llega antes de que la
 * lista dibuje el siguiente elemento, el foco "brinca" a otra parte de la pantalla. Se deja pasar
 * una repetición cada [minGapMs] (≈ 20 por segundo): sigue siendo rápido y ya no se salta.
 */
class KeyRepeatGate(private val minGapMs: Long = 50) {
    private var last = 0L
    fun shouldDrop(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount == 0) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            else -> return false
        }
        val now = SystemClock.uptimeMillis()
        if (now - last < minGapMs) return true
        last = now
        return false
    }
}

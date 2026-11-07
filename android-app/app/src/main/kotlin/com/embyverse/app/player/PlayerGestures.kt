package com.embyverse.app.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.embyverse.app.R
import kotlinx.coroutines.delay

private tailrec fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

private data class Feedback(val icon: ImageVector, val text: String, val level: Float? = null, val id: Long = System.nanoTime())

/**
 * Gestos del celular sobre el video (cuando los controles están ocultos):
 *  · un toque muestra los controles · doble toque a la derecha/izquierda adelanta/regresa 10 s
 *  · deslizar en la mitad izquierda cambia el brillo y en la derecha el volumen.
 */
@Composable
fun PhoneGestureLayer(player: Player?, canSeek: Boolean, onShowControls: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    LaunchedEffect(feedback?.id) { if (feedback != null) { delay(900); feedback = null } }
    val fwd = stringResource(R.string.gesture_forward); val back = stringResource(R.string.gesture_back)
    Box(modifier.fillMaxSize().testTag("player_gestures")
        .pointerInput(player, canSeek) {
            detectTapGestures(
                onTap = { onShowControls() },
                onDoubleTap = { off ->
                    val p = player ?: return@detectTapGestures
                    if (!canSeek) { onShowControls(); return@detectTapGestures }
                    val ahead = off.x > size.width / 2
                    p.seekTo((p.currentPosition + if (ahead) 10_000 else -10_000).coerceAtLeast(0))
                    feedback = Feedback(if (ahead) Icons.Rounded.FastForward else Icons.Rounded.FastRewind, if (ahead) fwd else back)
                },
            )
        }
        .pointerInput(Unit) {
            var left = true
            detectVerticalDragGestures(onDragStart = { left = it.x < size.width / 2 }) { change, dy ->
                change.consume()
                val delta = -dy / (size.height * 0.8f)
                if (left) {
                    val w = context.activity()?.window ?: return@detectVerticalDragGestures
                    val cur = w.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                    val v = (cur + delta).coerceIn(0.02f, 1f)
                    w.attributes = w.attributes.apply { screenBrightness = v }
                    feedback = Feedback(Icons.Rounded.BrightnessMedium, "${(v * 100).toInt()} %", v)
                } else if (audio != null) {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val acc = (feedback?.takeIf { it.icon == Icons.Rounded.VolumeUp }?.level ?: (audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max)) + delta
                    val v = acc.coerceIn(0f, 1f)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).toInt(), 0)
                    feedback = Feedback(Icons.Rounded.VolumeUp, "${(v * 100).toInt()} %", v)
                }
            }
        }) {
        AnimatedVisibility(feedback != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            val f = feedback ?: return@AnimatedVisibility
            Column(Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(f.icon, null, tint = Color.White, modifier = Modifier.size(34.dp))
                Spacer(Modifier.height(6.dp))
                Text(f.text, color = Color.White, style = MaterialTheme.typography.titleSmall)
                f.level?.let { l ->
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { l }, modifier = Modifier.width(110.dp).height(4.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f),
                        drawStopIndicator = {}, gapSize = 0.dp)
                }
            }
        }
    }
}

/** Pantalla bloqueada (para que los niños no toquen nada): solo el candado la desbloquea. */
@Composable
fun LockedOverlay(onUnlock: () -> Unit) {
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(hint) { if (hint) { delay(2_500); hint = false } }
    androidx.activity.compose.BackHandler { hint = true }
    Box(Modifier.fillMaxSize().testTag("player_locked").pointerInput(Unit) { detectTapGestures { hint = true } }) {
        AnimatedVisibility(hint, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)) {
            Row(Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.player_locked_hint), color = Color.White, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onUnlock, modifier = Modifier.testTag("player_unlock")) { Icon(Icons.Rounded.LockOpen, stringResource(R.string.player_unlock), tint = Color.White) }
            }
        }
    }
}

/** Botón de candado que se muestra con los controles. */
@Composable
fun LockButton(onLock: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onLock, modifier = modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).testTag("player_lock")) {
        Icon(Icons.Rounded.Lock, stringResource(R.string.player_lock), tint = Color.White)
    }
}

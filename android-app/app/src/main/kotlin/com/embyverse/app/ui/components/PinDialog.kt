package com.embyverse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.embyverse.core.config.ConfigResolver
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.embyverse.app.R
import com.embyverse.app.ui.theme.LocalEmby
import kotlinx.coroutines.launch

/**
 * Pide un PIN de 4 dígitos. Con [confirm] = true lo pide dos veces (para crear uno nuevo).
 * [onSubmit] devuelve false si el PIN es incorrecto: se muestra el error y se puede reintentar.
 */
@Composable
fun PinDialog(title: String, message: String? = null, confirm: Boolean = false, onDismiss: () -> Unit, onSubmit: suspend (String) -> Boolean) {
    val t = LocalEmby.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit = {
        when {
            pin.length != 4 -> error = R.string.pin_length
            confirm && pin != pin2 -> error = R.string.pin_mismatch
            else -> scope.launch { if (!onSubmit(pin)) { error = R.string.pin_wrong; pin = "" } }
        }
        Unit
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = t.textSecondary) }
                OutlinedTextField(pin, { v -> pin = v.filter(Char::isDigit).take(4); error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.pin_label)) }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.focusRequester(focus).testTag("pin"))
                if (confirm) OutlinedTextField(pin2, { v -> pin2 = v.filter(Char::isDigit).take(4); error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.pin_repeat)) }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.testTag("pin2"))
                error?.let { Text(stringResource(it), color = t.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = submit, modifier = Modifier.testTag("pin_ok")) { Text(stringResource(R.string.action_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Avatares disponibles para los perfiles (se guardan por perfil en el dispositivo). */
val PROFILE_AVATARS = listOf("🦁", "🐼", "🦊", "🐯", "🐸", "🐵", "🐱", "🐶", "🦄", "🐧", "🚀", "⚽", "🎮", "🎬", "👑", "🌟", "🤖", "👾")

/** Círculo del perfil: avatar elegido o la inicial del nombre sobre su color. */
@Composable
fun ProfileAvatar(name: String, colorArgb: Int, avatar: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(Color(colorArgb), CircleShape), contentAlignment = Alignment.Center) {
        if (avatar != null) Text(avatar, fontSize = (size.value * 0.5f).sp)
        else Text(name.take(1).uppercase(), color = Color(ConfigResolver.readableOn(colorArgb)), fontSize = (size.value * 0.42f).sp)
    }
}

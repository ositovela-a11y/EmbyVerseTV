package com.embyverse.app.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.data.vpn.VpnManager
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.screens.sources.SubScreenTop
import com.embyverse.app.ui.theme.LocalEmby
import kotlinx.coroutines.launch

/** Ajustes › VPN: agregar la configuración WireGuard del proveedor de VPN, conectar y desconectar. */
@Composable
fun VpnScreen(nav: Navigator) {
    val c = LocalAppContainer.current
    val t = LocalEmby.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm = com.embyverse.app.ui.appViewModel { PlaybackViewModel(it) }
    val p by vm.prefs.collectAsStateWithLifecycle()
    val state by c.vpn.state.collectAsStateWithLifecycle()
    val message by c.vpn.message.collectAsStateWithLifecycle()
    var hasConfig by remember { mutableStateOf(c.vpn.hasConfig()) }
    var server by remember { mutableStateOf(c.vpn.summary()) }
    var editing by remember { mutableStateOf(!hasConfig) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun doConnect() = scope.launch { c.vpn.connect(p.vpnAppOnly) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) doConnect() else error = "Sin el permiso de VPN no se puede conectar."
    }
    fun connect() {
        error = null
        val i = c.vpn.permissionIntent()
        if (i == null) { doConnect(); return }
        // Algunos TV no traen la ventana de permiso de VPN de Android.
        runCatching { permission.launch(i) }.onFailure { error = "Este equipo no permite que las apps usen VPN. Usa la VPN en tu módem o router." }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) text = runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        SubScreenTop("VPN", nav::back)
        SettingsList {
            item {
                // Estado
                val (color, label) = when (state) {
                    VpnManager.State.ON -> Color(0xFF22C55E) to "Conectada"
                    VpnManager.State.CONNECTING -> Color(0xFFF59E0B) to "Conectando…"
                    VpnManager.State.ERROR -> Color(0xFFEF4444) to "No conectada"
                    VpnManager.State.OFF -> t.textSecondary to "Desconectada"
                }
                Row(Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(t.cardRadius)).padding(16.dp).testTag("vpn_status"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Shield, null, tint = color) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium, color = t.onBackground)
                        Text(if (hasConfig) "Servidor: ${server ?: "configurado"}" else "Sin configurar", style = MaterialTheme.typography.bodySmall, color = t.textSecondary)
                    }
                    if (hasConfig && !editing) {
                        if (state == VpnManager.State.ON || state == VpnManager.State.CONNECTING) EmbyButton("Desconectar", { scope.launch { c.vpn.disconnect() } }, secondary = true, modifier = Modifier.testTag("vpn_off"))
                        else EmbyButton("Conectar", { connect() }, modifier = Modifier.testTag("vpn_on"))
                    }
                }
            }
            (message ?: error)?.let { m -> item { Text(m, color = Color(0xFFEF4444), style = MaterialTheme.typography.bodyMedium) } }
            item { Text("Protege tu conexión y evita bloqueos de tu internet. Usa la configuración WireGuard que te da tu proveedor de VPN (Proton VPN, Mullvad, Surfshark, Windscribe o tu propio servidor).",
                style = MaterialTheme.typography.bodySmall, color = t.textSecondary) }
            item { SwitchRow("Solo para esta app", "Recomendado: la VPN se usa solo aquí; el resto del equipo sigue con tu internet normal.", p.vpnAppOnly) { v -> vm.update { it.copy(vpnAppOnly = v) } } }
            item { SwitchRow("Conectar al abrir la app", "La VPN se conecta sola cada vez que abres la app.", p.vpnAuto) { v -> vm.update { it.copy(vpnAuto = v) } } }
            if (editing) {
                item { Text("Configuración WireGuard", style = MaterialTheme.typography.titleMedium, color = t.onBackground) }
                item {
                    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag("vpn_text"),
                        placeholder = { Text("[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …", fontFamily = FontFamily.Monospace, fontSize = 12.sp) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = t.onBackground, unfocusedTextColor = t.onBackground))
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EmbyButton("Elegir archivo .conf", { pickFile.launch(arrayOf("*/*")) }, icon = Icons.Rounded.FileOpen, secondary = true)
                        EmbyButton("Guardar", {
                            error = c.vpn.save(text)
                            if (error == null) { hasConfig = true; server = c.vpn.summary(); editing = false; text = "" }
                        }, modifier = Modifier.testTag("vpn_save"))
                    }
                }
            } else item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmbyButton("Cambiar configuración", { editing = true }, secondary = true)
                    EmbyButton("Quitar VPN", { scope.launch { c.vpn.disconnect(); c.vpn.forget(); hasConfig = false; server = null; editing = true } }, secondary = true)
                }
            }
            item { Text("La configuración se guarda cifrada en este equipo. La app no incluye servidores de VPN propios.", style = MaterialTheme.typography.labelSmall, color = t.textSecondary) }
        }
    }
}

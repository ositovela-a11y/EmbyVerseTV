package com.embyverse.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.embyverse.app.data.tv.LgTvInstaller
import com.embyverse.app.ui.LocalAppContainer
import com.embyverse.app.ui.components.CategoryChip
import com.embyverse.app.ui.components.EmbyButton
import com.embyverse.app.ui.navigation.Navigator
import com.embyverse.app.ui.screens.sources.SubScreenTop
import com.embyverse.app.ui.theme.LocalEmby
import kotlinx.coroutines.launch

private const val SITE_TV = "https://ositovela-a11y.github.io/EmbyVerseTV/tv"
private const val RELEASES = "https://github.com/ositovela-a11y/EmbyVerseTV/releases/download"
private const val APPS2SAMSUNG = "https://github.com/Apps2Samsung/Apps2Samsung/releases/latest"

/**
 * Instalar EmbyVerse en TVs LG y Samsung desde el celular, sin PC. El celular y la TV deben estar en el mismo Wi-Fi.
 * LG: lo hace esta app (modo desarrollador). Samsung: con el instalador libre Apps2Samsung (firma con la cuenta Samsung).
 */
@Composable
fun TvInstallScreen(nav: Navigator) {
    val c = LocalAppContainer.current
    val t = LocalEmby.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by c.config.config.collectAsStateWithLifecycle()
    val installer = remember { LgTvInstaller(context.applicationContext, c.okHttp) }
    var brand by remember { mutableStateOf("lg") }
    var ip by remember { mutableStateOf(installer.savedIp) }
    var pass by remember { mutableStateOf(installer.savedPass) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf<Boolean?>(null) }
    var lastRenew by remember { mutableStateOf(installer.lastRenew) }
    val ipkUrls = listOfNotNull(cfg.texts["tv.lgUrl"]?.takeIf { it.startsWith("https://") },
        "$SITE_TV/EmbyVerse-LG.ipk", "$RELEASES/tv-latest/EmbyVerse-LG.ipk", "$RELEASES/tv-beta/EmbyVerse-LG.ipk")
    val wgtUrl = cfg.texts["tv.samsungUrl"]?.takeIf { it.startsWith("https://") } ?: "$SITE_TV/EmbyVerse-Samsung.wgt"
    val open = { u: String -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

    @Composable fun Step(n: Int, text: String) {
        Row(Modifier.fillMaxWidth().background(t.surface, RoundedCornerShape(t.cardRadius)).padding(12.dp)) {
            Text("$n.", color = t.accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
            Text(text, color = t.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Column(Modifier.fillMaxSize()) {
        SubScreenTop("Instalar en TV Samsung o LG", nav::back)
        SettingsList {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CategoryChip("TV LG", brand == "lg") { brand = "lg" }
                    CategoryChip("TV Samsung", brand == "samsung") { brand = "samsung" }
                }
            }
            item { Text("El celular y la TV deben estar conectados al mismo Wi-Fi. No necesitas computadora.", color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
            if (brand == "lg") {
                item { Step(1, "En la TV abre LG Content Store, busca e instala la app «Developer Mode».") }
                item { Step(2, "Abre Developer Mode e inicia sesión con una cuenta de LG Developer (gratis en webostv.developer.lge.com).") }
                item { Step(3, "Pon «Dev Mode Status» en ON. La TV se reinicia. Vuelve a abrir Developer Mode y pon «Key Server» en ON.") }
                item { Step(4, "Escribe aquí la IP y la Passphrase que muestra Developer Mode y pulsa Instalar.") }
                item {
                    OutlinedTextField(ip, { ip = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("IP de la TV (ej. 192.168.1.50)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("lg_ip"))
                }
                item {
                    OutlinedTextField(pass, { pass = it.trim().uppercase() }, label = { Text("Passphrase (6 letras)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters), modifier = Modifier.fillMaxWidth().testTag("lg_pass"))
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EmbyButton(if (busy) "Trabajando…" else "Instalar EmbyVerse", {
                            if (!busy) scope.launch {
                                busy = true; ok = null
                                val r = installer.install(ip, pass, ipkUrls) { status = it }
                                ok = r.ok; status = r.message; busy = false; lastRenew = installer.lastRenew
                            }
                        }, icon = Icons.Rounded.Tv, enabled = ip.count { it == '.' } == 3 && pass.length >= 4, modifier = Modifier.testTag("lg_install"))
                        EmbyButton("Renovar 50 h", {
                            if (!busy) scope.launch {
                                busy = true; ok = null; status = "Renovando…"
                                val r = installer.renew(ip, pass)
                                ok = r.ok; status = r.message; busy = false; lastRenew = installer.lastRenew
                            }
                        }, icon = Icons.Rounded.Autorenew, secondary = true, enabled = ip.isNotBlank())
                    }
                }
                if (status.isNotBlank()) item {
                    Text(status, color = when (ok) { true -> t.accent; false -> MaterialTheme.colorScheme.error; null -> t.onSurface },
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("lg_status"))
                }
                if (lastRenew > 0) item {
                    Text("Última renovación desde este celular: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(lastRenew))}", color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Text("Después de instalar, la app EmbyVerse de la TV renueva sola el modo desarrollador cada 20 horas al abrirla, y tu proveedor puede renovarla a distancia. " +
                        "Si la TV pasa más de 50 horas sin abrir EmbyVerse, LG la borra: repite estos pasos (la cuenta y la configuración quedan guardadas aquí).",
                        color = t.textSecondary, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                item { Step(1, "Instala en este celular el instalador gratuito «Apps2Samsung» (botón de abajo) y ábrelo.") }
                item { Step(2, "En la TV entra a Apps, con el control escribe 1 2 3 4 5, pon Developer mode en ON y escribe la IP de este celular (Ajustes del celular → Wi-Fi → tu red). Apaga y prende la TV.") }
                item { Step(3, "Descarga EmbyVerse para Samsung (botón de abajo).") }
                item { Step(4, "En Apps2Samsung elige tu TV, toca instalar un archivo .wgt propio y elige EmbyVerse-Samsung.wgt de Descargas. La primera vez te pide entrar con una cuenta Samsung (gratis) para firmar.") }
                item { Step(5, "Listo: EmbyVerse aparece en Apps de la TV. En Samsung no caduca y se actualiza sola.") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        EmbyButton("Descargar Apps2Samsung", { open(APPS2SAMSUNG) }, icon = Icons.Rounded.Download)
                        EmbyButton("Descargar EmbyVerse para Samsung", { open(wgtUrl) }, icon = Icons.Rounded.Download, secondary = true)
                        EmbyButton("Copiar enlace de EmbyVerse Samsung", {
                            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("EmbyVerse Samsung", wgtUrl))
                            android.widget.Toast.makeText(context, "Enlace copiado", android.widget.Toast.LENGTH_SHORT).show()
                        }, icon = Icons.Rounded.ContentCopy, secondary = true)
                    }
                }
                item { Text("Apps2Samsung es un instalador independiente de código abierto (licencia MIT); no es de Samsung ni de EmbyVerse.", color = t.textSecondary, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

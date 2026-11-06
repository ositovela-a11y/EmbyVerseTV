package com.embyverse.app.data.tv

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Instalador de EmbyVerse en TVs LG (webOS) desde el celular, sin PC.
 *
 * Hace lo mismo que las herramientas oficiales de LG (ares-setup-device / ares-novacom --getkey /
 * ares-install / ares-launch), con la TV en modo desarrollador y el celular en la misma red Wi-Fi:
 *  1. Con «Key Server» encendido en la app Developer Mode, la TV entrega su llave en http://IP:9991/webos_rsa.
 *     La llave viene cifrada con la «Passphrase» de 6 letras que muestra la TV.
 *  2. Con esa llave entra por SSH (usuario prisoner, puerto 9922), sube el .ipk y lo instala con el
 *     servicio de instalación de desarrollador de la TV.
 *  3. «Renovar» abre Developer Mode con {extend:true}: igual que pulsar «Extend» (vuelve a 50 h).
 *
 * La IP, la passphrase y la llave se guardan solo en este celular (almacenamiento privado de la app)
 * para poder renovar después con un toque. Nunca salen del equipo.
 */
class LgTvInstaller(context: Context, private val http: OkHttpClient) {
    data class Outcome(val ok: Boolean, val message: String)

    private val prefs = context.getSharedPreferences("tv_lg", Context.MODE_PRIVATE)
    private val shortHttp = http.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    val savedIp: String get() = prefs.getString("ip", "").orEmpty()
    val savedPass: String get() = prefs.getString("pass", "").orEmpty()
    val lastRenew: Long get() = prefs.getLong("renew", 0L)

    suspend fun install(ip: String, passphrase: String, ipkUrls: List<String>, progress: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        runCatching {
            progress("Conectando con la TV…")
            val s = connect(ip.trim(), passphrase.trim())
            try {
                progress("Descargando EmbyVerse para LG…")
                val ipk = download(ipkUrls)
                progress("Enviando a la TV (${ipk.size / 1024} KB)…")
                exec(s, "mkdir -p /media/developer/temp", 15_000)
                upload(s, "/media/developer/temp/embyverse.ipk", ipk)
                progress("Instalando en la TV…")
                val out = exec(s, "luna-send-pub -i 'luna://com.webos.appInstallService/dev/install' " +
                    "'{\"id\":\"com.ares.defaultName\",\"ipkUrl\":\"/media/developer/temp/embyverse.ipk\",\"subscribe\":true}'", 240_000) { o ->
                    o.contains("\"installed\"") || o.contains("fail", ignoreCase = true) || o.contains("\"returnValue\":false")
                }
                exec(s, "rm -f /media/developer/temp/embyverse.ipk", 15_000)
                if (!out.contains("\"installed\"")) return@runCatching Outcome(false, "La TV no aceptó la instalación: ${out.takeLast(240)}")
                runCatching { launch(s, "com.embyverse.tv", null) }
                runCatching { launch(s, DEVMODE, "{\"extend\":true}"); prefs.edit().putLong("renew", System.currentTimeMillis()).apply() }
                Outcome(true, "EmbyVerse quedó instalada en la TV y el modo desarrollador renovado por 50 horas.")
            } finally { s.disconnect() }
        }.getOrElse { Outcome(false, explain(it)) }
    }

    suspend fun renew(ip: String = savedIp, passphrase: String = savedPass): Outcome = withContext(Dispatchers.IO) {
        runCatching {
            val s = connect(ip.trim(), passphrase.trim())
            try {
                launch(s, DEVMODE, "{\"extend\":true}")
                prefs.edit().putLong("renew", System.currentTimeMillis()).apply()
                Outcome(true, "Modo desarrollador renovado por 50 horas.")
            } finally { s.disconnect() }
        }.getOrElse { Outcome(false, explain(it)) }
    }

    // ------------------------------------------------------------------ internos

    private fun connect(ip: String, passphrase: String): Session {
        require(ip.isNotBlank()) { "Escribe la IP de la TV" }
        // Primero la llave recién pedida a la TV (Key Server encendido); si no responde, la guardada.
        val fresh = runCatching { fetchKey(ip) }.getOrNull()
        val key = fresh ?: prefs.getString("key", null)?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
            ?: error("KEYSERVER")
        val jsch = JSch()
        try { jsch.addIdentity("webos", key, null, passphrase.toByteArray()) } catch (e: Exception) { error("PASSPHRASE") }
        val s = jsch.getSession("prisoner", ip, 9922)
        s.setConfig("StrictHostKeyChecking", "no")
        // Las TVs usan un servidor SSH antiguo: se permiten sus algoritmos (solo para esta conexión local).
        s.setConfig("server_host_key", s.getConfig("server_host_key") + ",ssh-rsa")
        s.setConfig("PubkeyAcceptedAlgorithms", s.getConfig("PubkeyAcceptedAlgorithms") + ",ssh-rsa")
        s.setConfig("kex", s.getConfig("kex") + ",diffie-hellman-group14-sha1,diffie-hellman-group1-sha1")
        s.timeout = 20_000
        s.connect(15_000)
        prefs.edit().putString("ip", ip).putString("pass", passphrase)
            .putString("key", android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)).apply()
        return s
    }

    private fun fetchKey(ip: String): ByteArray {
        shortHttp.newCall(Request.Builder().url("http://$ip:9991/webos_rsa").build()).execute().use { r ->
            check(r.isSuccessful) { "llave ${r.code}" }
            val b = r.body.bytes(); check(b.size > 100 && String(b).contains("PRIVATE KEY")) { "llave inválida" }
            return b
        }
    }

    private fun download(urls: List<String>): ByteArray {
        var last: Throwable? = null
        for (u in urls.distinct()) {
            runCatching {
                http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    val b = r.body.bytes(); check(b.size > 10_000 && String(b, 0, 8) == "!<arch>\n") { "no es un paquete .ipk" }
                    return b
                }
            }.onFailure { last = it }
        }
        throw IllegalStateException("DOWNLOAD", last)
    }

    private fun upload(s: Session, path: String, data: ByteArray) {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand("cat > '$path'")
        val err = ByteArrayOutputStream(); ch.setErrStream(err)
        val out = ch.outputStream
        ch.connect(15_000)
        out.write(data); out.flush(); out.close()
        val t0 = System.currentTimeMillis()
        while (!ch.isClosed && System.currentTimeMillis() - t0 < 120_000) Thread.sleep(100)
        ch.disconnect()
        check(ch.exitStatus == 0 || ch.exitStatus == -1) { "No se pudo copiar a la TV: $err" }
    }

    private fun exec(s: Session, cmd: String, timeoutMs: Long, until: ((String) -> Boolean)? = null): String {
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand(cmd)
        val buf = ByteArrayOutputStream(); ch.setOutputStream(buf); ch.setErrStream(buf)
        ch.connect(15_000)
        val t0 = System.currentTimeMillis()
        while (!ch.isClosed && System.currentTimeMillis() - t0 < timeoutMs) {
            if (until != null && until(buf.toString())) break
            Thread.sleep(150)
        }
        ch.disconnect()
        return buf.toString()
    }

    private fun launch(s: Session, id: String, paramsJson: String?) {
        val p = if (paramsJson == null) "{\"id\":\"$id\"}" else "{\"id\":\"$id\",\"params\":$paramsJson}"
        val out = exec(s, "luna-send-pub -n 1 'luna://com.webos.applicationManager/launch' '$p'", 20_000)
        check(!out.contains("\"returnValue\":false")) { "La TV no abrió $id: ${out.take(200)}" }
    }

    private fun explain(e: Throwable): String {
        val m = (e.message ?: "") + " " + (e.cause?.message ?: "")
        return when {
            m.contains("KEYSERVER") -> "No se pudo pedir la llave a la TV. En la TV abre «Developer Mode», revisa que «Key Server» esté en ON y que el celular esté en el mismo Wi-Fi que la TV. Revisa también la IP."
            m.contains("PASSPHRASE") -> "La Passphrase no es correcta. Escríbela igual que aparece en la app Developer Mode de la TV (mayúsculas)."
            m.contains("Auth fail", true) -> "La TV rechazó la llave. Apaga y enciende «Key Server» en Developer Mode y vuelve a intentar con la Passphrase nueva."
            m.contains("DOWNLOAD") -> "No se pudo descargar EmbyVerse para LG. Revisa el internet del celular."
            m.contains("timeout", true) || m.contains("connect", true) || m.contains("route", true) ->
                "No se pudo conectar con la TV. Revisa que esté encendida, en modo desarrollador, en el mismo Wi-Fi y que la IP sea correcta."
            else -> "Error: ${m.trim().take(200)}"
        }
    }

    companion object { const val DEVMODE = "com.palmdts.devmode" }
}

package com.embyverse.app.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.embyverse.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Actualización DENTRO de la app (sin Downloader): al abrir la app o recuperar internet se consulta
 * `update.json` del sitio. Si hay una versión nueva de ESTA app (principal o la del vendedor), se
 * ofrece; al aceptar se descarga y Android pide confirmar la instalación (encima, sin perder datos).
 */
class AppUpdater(private val context: Context, private val http: OkHttpClient) {
    sealed interface State {
        data object Idle : State
        data class Available(val versionName: String, val versionCode: Int, val url: String) : State
        data class Downloading(val versionName: String, val progress: Float) : State
        /** Ya descargada en segundo plano (Wi-Fi): solo falta tocar «Instalar». */
        data class Ready(val versionName: String, val versionCode: Int, val file: File) : State
        data class Failed(val message: String) : State
    }

    private var lastUrl: String? = null
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state
    /** «Después»: no se vuelve a preguntar hasta reiniciar la app. */
    var snoozed = false
        private set

    private val asset get() = if (BuildConfig.VENDOR.isBlank()) "EmbyVerse.apk" else "EmbyVerse-${BuildConfig.VENDOR}.apk"

    /** Raíz del sitio (donde está la carpeta config/): de ahí se leen update.json y las APK. */
    private val siteBase: String? get() {
        val cfg = BuildConfig.CONFIG_BASE_URL.trim()
        if (cfg.isBlank()) return null
        val i = cfg.indexOf("/config")
        return (if (i > 0) cfg.substring(0, i) else cfg.substringBeforeLast('/')).trimEnd('/')
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        if (_state.value is State.Downloading) return@withContext
        val base = siteBase ?: return@withContext
        runCatching {
            val body = http.newCall(Request.Builder().url("$base/update.json?t=${System.currentTimeMillis() / 60_000}").build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext
                r.body.string()
            }
            val app = Json.parseToJsonElement(body).jsonObject["apps"]?.jsonObject?.get(asset) as? JsonObject ?: return@withContext
            val code = app["versionCode"]?.jsonPrimitive?.int ?: return@withContext
            val name = app["versionName"]?.jsonPrimitive?.content ?: "$code"
            val path = app["path"]?.jsonPrimitive?.content ?: return@withContext
            val url = if (path.startsWith("http")) path else "$base/${path.trimStart('/')}"
            val cur = _state.value
            if (code <= BuildConfig.VERSION_CODE) { _state.value = State.Idle; return@withContext }
            if (cur is State.Ready && cur.versionCode == code && valid(cur.file, code)) return@withContext
            lastUrl = url
            _state.value = State.Available(name, code, url)
            // Con Wi-Fi (o red sin límite) y permiso de instalar: se descarga sola en segundo plano.
            if (canInstall() && unmetered()) fetch(State.Available(name, code, url), silent = true)?.let { _state.value = State.Ready(name, code, it) }
        }.onFailure { Log.i("AppUpdater", "no se pudo comprobar: ${it.message}") }
    }

    private fun unmetered(): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        !cm.isActiveNetworkMetered
    }.getOrDefault(false)

    /** Una sola descarga a la vez: la automática (Wi-Fi) y la del botón «Actualizar» no escriben el mismo archivo. */
    private val fetchLock = Any()

    /**
     * ¿La APK descargada está completa y es la versión esperada de ESTA app? Si no, Android mostraría
     * «el paquete no es válido» (descarga cortada, archivo a medias o reemplazado en el sitio mientras bajaba).
     */
    private fun valid(file: File, versionCode: Int): Boolean = runCatching {
        if (!file.exists() || file.length() < 100_000) return false
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        info.packageName == context.packageName && code == versionCode
    }.getOrDefault(false)

    private fun fetch(a: State.Available, silent: Boolean): File? = synchronized(fetchLock) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "app-${a.versionCode}.apk")
            if (valid(out, a.versionCode)) return@runCatching out
            dir.listFiles()?.forEach { it.delete() } // incluida una copia dañada de esta misma versión
            var lastError: Throwable? = null
            repeat(3) { attempt ->
                val tmp = File(dir, "app-${a.versionCode}.part"); tmp.delete()
                val ok = runCatching {
                    // Parámetro para no recibir una copia vieja o a medias de la caché del sitio.
                    val url = a.url + (if ('?' in a.url) "&" else "?") + "v=${a.versionCode}&t=${System.currentTimeMillis()}"
                    http.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        val total = r.body.contentLength().takeIf { it > 0 } ?: -1L
                        var read = 0L
                        r.body.byteStream().use { input -> tmp.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                o.write(buf, 0, n); read += n
                                if (!silent && total > 0) _state.value = State.Downloading(a.versionName, read.toFloat() / total)
                            }
                        } }
                        if (total > 0 && read != total) error("descarga incompleta ($read de $total bytes)")
                    }
                    out.delete(); check(tmp.renameTo(out)) { "no se pudo guardar" }
                    check(valid(out, a.versionCode)) { "la APK descargada está dañada" }
                }.onFailure { lastError = it; Log.i("AppUpdater", "intento ${attempt + 1}: ${it.message}"); out.delete(); tmp.delete() }.isSuccess
                if (ok) return@runCatching out
            }
            throw lastError ?: IllegalStateException("Error de descarga")
        }.onFailure { if (!silent) _state.value = State.Failed("No se pudo descargar completa la actualización. Revisa tu internet e intenta de nuevo.") }.getOrNull()
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun later() { snoozed = true }

    /** «Buscar actualización» (Acerca de): vuelve a preguntar aunque antes se haya elegido «Después». */
    val nudge = MutableStateFlow(0)
    suspend fun checkNow(): Boolean {
        snoozed = false; nudge.value++
        check()
        return _state.value !is State.Idle
    }

    /** Descarga la APK (si no está ya) y abre el instalador de Android. Devuelve false si falta el permiso de instalar. */
    suspend fun downloadAndInstall(): Boolean {
        val st = _state.value
        if (!canInstall()) return false
        when (st) {
            is State.Ready -> {
                if (withContext(Dispatchers.IO) { valid(st.file, st.versionCode) }) { install(st.file); return true }
                // La copia guardada se dañó: se borra y se vuelve a descargar.
                withContext(Dispatchers.IO) { st.file.delete() }
                val again = State.Available(st.versionName, st.versionCode, lastUrl ?: return true)
                val file = withContext(Dispatchers.IO) { fetch(again, silent = false) } ?: return true
                _state.value = State.Ready(st.versionName, st.versionCode, file)
                install(file)
            }
            is State.Available -> {
                val file = withContext(Dispatchers.IO) { fetch(st, silent = false) } ?: return true
                _state.value = State.Ready(st.versionName, st.versionCode, file)
                install(file)
            }
            else -> Unit
        }
        return true
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Pantalla de Android para permitir que esta app instale actualizaciones (una sola vez). */
    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

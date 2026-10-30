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
        data class Failed(val message: String) : State
    }

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
            _state.value = if (code > BuildConfig.VERSION_CODE) State.Available(name, code, url) else State.Idle
        }.onFailure { Log.i("AppUpdater", "no se pudo comprobar: ${it.message}") }
    }

    fun later() { snoozed = true }

    /** Descarga la APK y abre el instalador de Android. Devuelve false si falta el permiso de instalar. */
    suspend fun downloadAndInstall(): Boolean {
        val a = _state.value as? State.Available ?: return true
        if (!canInstall()) return false
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "app-${a.versionCode}.apk")
                http.newCall(Request.Builder().url(a.url).build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val total = r.body.contentLength().takeIf { it > 0 } ?: -1L
                    r.body.byteStream().use { input -> out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024); var read = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            o.write(buf, 0, n); read += n
                            if (total > 0) _state.value = State.Downloading(a.versionName, read.toFloat() / total)
                        }
                    } }
                }
                out
            }.onFailure { _state.value = State.Failed(it.message ?: "Error de descarga") }.getOrNull()
        } ?: return true
        _state.value = State.Downloading(a.versionName, 1f)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        _state.value = a // si se cancela la instalación, se puede volver a intentar
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

package com.embyverse.app.data.stats

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.embyverse.app.BuildConfig
import com.embyverse.app.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Avisa al servidor de estadísticas del dueño de la app que este equipo la tiene instalada y la usa:
 * código al azar de la instalación, app (principal o vendedor), tipo de equipo, modelo, versión y el
 * usuario Xtream con el que entra (nunca la contraseña). Como máximo un aviso cada 6 horas.
 * Si el servidor no está configurado (config/stats.json), no hace nada.
 */
class StatsReporter(private val context: Context, http: OkHttpClient, private val configBase: String = BuildConfig.CONFIG_BASE_URL) {
    private val client = http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val sp = context.getSharedPreferences("stats", Context.MODE_PRIVATE)

    val installId: String
        get() = sp.getString("id", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("id", it).apply() }

    private fun platform(): String =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) "androidtv" else "android"

    /** Dirección del servidor (se guarda un día). */
    private fun endpoint(): String? {
        val now = System.currentTimeMillis()
        val cached = sp.getString("url", null)
        if (cached != null && now - sp.getLong("urlAt", 0) < 86_400_000L && sp.getString("urlBase", null) == configBase) return cached.ifBlank { null }
        val url = runCatching {
            client.newCall(Request.Builder().url("${configBase.trimEnd('/')}/stats.json?t=${now / 3_600_000}").build()).execute().use { r ->
                if (!r.isSuccessful) "" else JSONObject(r.body.string()).optString("url").trim().trimEnd('/')
            }
        }.getOrNull() ?: return cached?.ifBlank { null }
        sp.edit().putString("url", url).putLong("urlAt", now).putString("urlBase", configBase).apply()
        return url.takeIf { it.startsWith("https://") || (BuildConfig.DEBUG && it.startsWith("http://")) }
    }

    /** Avisa al abrir y cada vez que cambia la cuenta del cliente. */
    suspend fun reportOn(flow: kotlinx.coroutines.flow.Flow<List<SourceEntity>>) =
        flow.collect { report(it) }

    suspend fun report(sources: List<SourceEntity>, force: Boolean = false) = withContext(Dispatchers.IO) {
        runCatching {
            val xtream = sources.firstOrNull { it.type == "XTREAM" } ?: sources.firstOrNull()
            val user = xtream?.username.orEmpty()
            val host = xtream?.location?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty()
            val sig = "${BuildConfig.VERSION_NAME}|$user|$host"
            val now = System.currentTimeMillis()
            if (!force && sig == sp.getString("sig", null) && now - sp.getLong("at", 0) < 6 * 3_600_000L) return@runCatching
            val base = endpoint() ?: return@runCatching
            val body = JSONObject()
                .put("id", installId).put("app", BuildConfig.VENDOR).put("platform", platform())
                .put("model", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)).put("version", BuildConfig.VERSION_NAME)
                .put("user", user).put("host", host)
            client.newCall(Request.Builder().url("$base/v1/ping").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                if (r.isSuccessful) sp.edit().putString("sig", sig).putLong("at", now).apply()
            }
        }.onFailure { Log.d("EmbyVerseStats", "sin aviso: ${it.message}") }
    }
}

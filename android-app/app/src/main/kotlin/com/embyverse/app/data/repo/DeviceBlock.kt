package com.embyverse.app.data.repo

import android.content.Context
import com.embyverse.app.BuildConfig
import com.embyverse.app.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * Bloqueo de equipos desde el panel (Estadísticas). config/blocked.json trae solo huellas (nadie puede ver qué
 * equipo o usuario es): del código de instalación y de app + servidor + usuario. Se revisa al abrir y cada 2 min.
 * No borra nada: si el dueño lo desbloquea, la app sigue como estaba. El último resultado se guarda para que un
 * equipo bloqueado siga bloqueado aunque abra la app sin internet.
 */
object DeviceBlock {
    val blocked = MutableStateFlow<Boolean?>(null)
    /** Solo pruebas: dirección de la lista. */
    @Volatile var testUrl: String? = null

    private fun h16(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    fun deviceKey(installId: String) = h16("blk-dev:" + installId.trim())
    fun userKey(app: String, host: String, user: String) = h16("blk-user:$app|${host.trim().lowercase()}|${user.trim().lowercase()}")
    /** Código corto del equipo: el mismo que ve el dueño en el panel. */
    fun shortCode(installId: String) = installId.filter { it.isLetterOrDigit() }.take(8).uppercase().let { if (it.length > 4) it.take(4) + "-" + it.drop(4) else it }

    fun stored(ctx: Context): Boolean = ctx.getSharedPreferences("devblock", Context.MODE_PRIVATE).getBoolean("b", false)
    fun init(ctx: Context) { if (blocked.value == null) blocked.value = stored(ctx) }

    /** true bloqueado · false no · null no se pudo revisar (se queda como estaba). */
    suspend fun check(ctx: Context, http: OkHttpClient, installId: String, sources: List<SourceEntity>): Boolean? = withContext(Dispatchers.IO) {
        val base = testUrl ?: "${BuildConfig.CONFIG_BASE_URL.trimEnd('/')}/blocked.json"
        val res = runCatching {
            http.newCall(Request.Builder().url("$base?t=${System.currentTimeMillis() / 30_000}").header("Cache-Control", "no-cache").build()).execute().use { r ->
                when {
                    r.code == 404 -> false
                    !r.isSuccessful -> null
                    else -> {
                        val j = JSONObject(r.body.string())
                        val devs = j.optJSONObject("devices"); val users = j.optJSONObject("users")
                        val src = sources.firstOrNull { it.type == "XTREAM" } ?: sources.firstOrNull()
                        val user = src?.username.orEmpty()
                        val host = src?.location?.let { runCatching { URI(it).host }.getOrNull() }.orEmpty()
                        (devs?.has(deviceKey(installId)) == true) || (user.isNotBlank() && users?.has(userKey(BuildConfig.VENDOR, host, user)) == true)
                    }
                }
            }
        }.getOrNull()
        if (res != null) {
            ctx.getSharedPreferences("devblock", Context.MODE_PRIVATE).edit().putBoolean("b", res).apply()
            blocked.value = res
        }
        res
    }
}

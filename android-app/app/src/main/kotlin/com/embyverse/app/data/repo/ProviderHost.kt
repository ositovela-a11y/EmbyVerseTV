package com.embyverse.app.data.repo

import android.content.Context
import com.embyverse.app.BuildConfig
import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.app.work.SyncWorker
import com.embyverse.core.config.RemoteConfig
import com.embyverse.core.model.SourceType
import com.embyverse.core.net.UrlSafety
import com.embyverse.core.xtream.XtreamClient

/**
 * Servidor del proveedor ya configurado: el cliente solo escribe usuario y contraseña.
 * Viene de la APK (BuildConfig.DEFAULT_HOST) y el panel puede cambiarlo sin APK nueva con el texto
 * «provider.host» del diseño publicado. Si cambia, las cuentas que usaban el servidor anterior pasan
 * solas al nuevo y se actualizan sus listas.
 */
object ProviderHost {
    const val TEXT_KEY = "provider.host"

    fun of(cfg: RemoteConfig): String? =
        (cfg.texts[TEXT_KEY]?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.DEFAULT_HOST.trim().takeIf { it.isNotEmpty() })
            ?.let { XtreamClient.normalizeBaseUrl(it) }?.takeIf { UrlSafety.isHttpUrl(it) }

    /** Mueve al servidor nuevo las fuentes Xtream del servidor anterior y las actualiza. */
    suspend fun follow(context: Context, db: EmbyDatabase, host: String) {
        val prefs = context.getSharedPreferences("ev_provider", Context.MODE_PRIVATE)
        val prev = prefs.getString("host", null)
        if (prev == host) return
        if (prev != null) {
            db.sources().all().filter { it.type == SourceType.XTREAM.name && XtreamClient.normalizeBaseUrl(it.location) == prev }.forEach { s ->
                db.sources().upsert(s.copy(location = host))
                SyncWorker.syncNow(context, s.id)
            }
        }
        prefs.edit().putString("host", host).apply()
    }
}

package com.embyverse.app.data.repo

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.core.config.RemoteConfig
import java.security.MessageDigest

/**
 * Suspensión remota de clientes que no pagan. En el panel se escribe el USUARIO de la cuenta; en el
 * diseño publicado solo viaja una huella (SHA-256 recortada), así los nombres de usuario no quedan
 * públicos. Si la cuenta de este equipo está en la lista, la app se bloquea, borra las cuentas y la
 * caché, y ofrece desinstalarse (Android siempre pide confirmar la desinstalación al usuario).
 */
object AccountBlock {
    const val TEXT_KEY = "blocked.users"

    fun hash(username: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(("ev:" + username.trim().lowercase()).toByteArray())
        return d.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun blockedHashes(cfg: RemoteConfig): Set<String> =
        cfg.texts.filterKeys { it == TEXT_KEY || it.startsWith("$TEXT_KEY.") }.values
            .flatMap { it.split(',', ' ', '\n') }.map { it.trim().lowercase() }.filter { it.length >= 12 }.toSet()

    fun isBlocked(cfg: RemoteConfig, usernames: List<String?>): Boolean {
        val set = blockedHashes(cfg)
        return set.isNotEmpty() && usernames.filterNotNull().any { hash(it) in set }
    }

    /** Cuenta bloqueada de la que hay que buscar una huella en la lista, o null. */
    fun blockedHashOf(cfg: RemoteConfig, usernames: List<String?>): String? {
        val set = blockedHashes(cfg)
        return usernames.filterNotNull().map(::hash).firstOrNull { it in set }
    }

    /** Borra cuentas, catálogo e historial del equipo (la app queda vacía) y recuerda la suspensión. */
    suspend fun wipe(context: Context, db: EmbyDatabase, hash: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        context.getSharedPreferences("blocked", Context.MODE_PRIVATE).edit().putString("hash", hash).commit()
        runCatching { db.clearAllTables() }
        runCatching { context.cacheDir.deleteRecursively() }
    }

    fun storedHash(context: Context): String? = context.getSharedPreferences("blocked", Context.MODE_PRIVATE).getString("hash", null)
    fun clear(context: Context) = context.getSharedPreferences("blocked", Context.MODE_PRIVATE).edit().clear().apply()

    fun uninstall(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

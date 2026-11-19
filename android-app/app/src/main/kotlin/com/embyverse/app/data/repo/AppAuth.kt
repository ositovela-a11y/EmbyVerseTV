package com.embyverse.app.data.repo

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.embyverse.app.BuildConfig
import com.embyverse.core.config.RemoteConfig
import java.net.URI
import java.security.MessageDigest

/**
 * Que nadie use la app sin autorización del dueño (se controla desde el panel, por app):
 *  - «auth.only» = "1" (lo enciende el dueño en el panel; apagado si no está): la app solo funciona con los servidores autorizados: el del proveedor
 *    («provider.host» o el de la APK) y los de «auth.hosts» (uno por renglón o separados por coma). Cuentas
 *    Xtream y listas M3U de otros servidores, o archivos M3U sueltos, no se aceptan.
 *  - «app.disabled» = "1": apagado a distancia de toda la app (la de un vendedor o la tuya).
 *  - Copia alterada: si la APK no está firmada con la firma del proyecto, no abre.
 */
object AppAuth {
    fun hostOf(u: String?): String? {
        val s = u?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if ("://" in s) s else "http://$s"
        return runCatching { URI(withScheme.substringBefore('|')).host }.getOrNull()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
    }

    fun hosts(cfg: RemoteConfig): Set<String> =
        (listOfNotNull(ProviderHost.of(cfg)) + (cfg.texts["auth.hosts"] ?: "").split(',', '\n', ';', ' '))
            .mapNotNull { hostOf(it) }.toSet()

    fun enforced(cfg: RemoteConfig): Boolean = cfg.texts["auth.only"]?.trim() == "1" && hosts(cfg).isNotEmpty()

    /** ¿Se permite una cuenta o lista con esta dirección? (sin dirección = archivo suelto: solo si no se exige). */
    fun allowed(cfg: RemoteConfig, location: String?): Boolean {
        if (!enforced(cfg)) return true
        val h = hostOf(location) ?: return false
        return hosts(cfg).any { a -> h == a || h.endsWith(".$a") }
    }

    fun disabled(cfg: RemoteConfig): Boolean = cfg.texts["app.disabled"]?.trim() == "1"

    /** La APK está firmada con la firma del proyecto (BuildConfig.SIGNING_SHA256). Sin dato esperado, se da por buena. */
    @Volatile private var integrity: Boolean? = null
    fun genuine(ctx: Context): Boolean = integrity ?: runCatching {
        val expected = BuildConfig.SIGNING_SHA256.lowercase().replace(":", "")
        if (expected.isBlank()) return@runCatching true
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (info == null) emptyArray() else if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        sigs.orEmpty().any { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } == expected }
    }.getOrDefault(true).also { integrity = it }
}

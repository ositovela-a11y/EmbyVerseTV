package com.embyverse.core.config

/** Almacenamiento clave-valor persistente (en Android: archivo en filesDir con escritura atómica). */
interface ConfigStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

sealed interface ApplyResult {
    data class Applied(val version: Int) : ApplyResult
    data object NotNewer : ApplyResult
    data class Rejected(val reasons: List<String>) : ApplyResult
    data class RequiresAppUpdate(val minAppVersionCode: Int) : ApplyResult
}

/**
 * Ciclo de vida de la configuración remota en el dispositivo:
 *
 *  1. [onFetched] valida la descarga. Si es válida y más nueva, pasa a ser la activa y la
 *     anterior se conserva como respaldo. Queda "sin verificar".
 *  2. Cuando la pantalla de inicio se ha renderizado bien con ella, la app llama a [markHealthy].
 *  3. Si la app arranca 2 veces seguidas sin haber llegado a [markHealthy] (p. ej. se cierra
 *     inesperadamente), [onStartup] la marca como defectuosa, restaura la anterior y nunca la
 *     vuelve a aplicar.
 *  4. Sin conexión, [current] devuelve la última configuración válida guardada, o los valores
 *     predeterminados empaquetados en la app.
 */
class ConfigManager(
    private val storage: ConfigStorage,
    private val appVersionCode: Int,
    private val bundledDefault: RemoteConfig = RemoteConfig.DEFAULT,
) {
    companion object {
        const val K_ACTIVE = "config.active"
        const val K_PREVIOUS = "config.previous"
        const val K_UNVERIFIED = "config.unverified"
        const val K_UNVERIFIED_STARTS = "config.unverified_starts"
        const val K_BAD = "config.bad_versions"
        const val K_ETAG = "config.etag"
        const val MAX_UNVERIFIED_STARTS = 2
    }

    @Volatile private var cached: RemoteConfig? = null

    fun current(): RemoteConfig {
        cached?.let { return it }
        val parsed = storage.read(K_ACTIVE)?.let { RemoteConfigParser.parse(it, bundledDefault) }
        val cfg = (parsed as? ConfigParseResult.Valid)?.config?.takeIf { it.minAppVersionCode <= appVersionCode } ?: bundledDefault
        cached = cfg
        return cfg
    }

    val etag: String? get() = storage.read(K_ETAG)

    private fun badVersions(): Set<Int> = storage.read(K_BAD)?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty()

    fun onStartup(): Boolean {
        val unverified = storage.read(K_UNVERIFIED)?.toIntOrNull() ?: return false
        val starts = (storage.read(K_UNVERIFIED_STARTS)?.toIntOrNull() ?: 0) + 1
        if (starts < MAX_UNVERIFIED_STARTS) { storage.write(K_UNVERIFIED_STARTS, starts.toString()); return false }
        rollback(unverified)
        return true
    }

    fun markHealthy() {
        storage.write(K_UNVERIFIED, null)
        storage.write(K_UNVERIFIED_STARTS, null)
    }

    /** Reversión manual o automática: marca la versión como defectuosa y vuelve a la anterior. */
    fun rollback(badVersion: Int) {
        storage.write(K_BAD, (badVersions() + badVersion).sorted().takeLast(50).joinToString(","))
        storage.write(K_ACTIVE, storage.read(K_PREVIOUS))
        storage.write(K_PREVIOUS, null)
        storage.write(K_ETAG, null)
        markHealthy()
        cached = null
    }

    fun onFetched(text: String, etag: String? = null): ApplyResult {
        val result = RemoteConfigParser.parse(text, bundledDefault)
        if (result is ConfigParseResult.Invalid) return ApplyResult.Rejected(result.errors)
        val cfg = (result as ConfigParseResult.Valid).config
        if (cfg.minAppVersionCode > appVersionCode) return ApplyResult.RequiresAppUpdate(cfg.minAppVersionCode)
        if (cfg.version in badVersions()) return ApplyResult.Rejected(listOf("La versión ${cfg.version} se marcó como defectuosa en este dispositivo"))
        val activeVersion = storage.read(K_ACTIVE)?.let { (RemoteConfigParser.parse(it, bundledDefault) as? ConfigParseResult.Valid)?.config?.version }
        if (activeVersion != null && cfg.version <= activeVersion) { if (etag != null) storage.write(K_ETAG, etag); return ApplyResult.NotNewer }

        storage.write(K_PREVIOUS, storage.read(K_ACTIVE))
        storage.write(K_ACTIVE, text)
        storage.write(K_UNVERIFIED, cfg.version.toString())
        storage.write(K_UNVERIFIED_STARTS, "0")
        if (etag != null) storage.write(K_ETAG, etag)
        cached = cfg
        return ApplyResult.Applied(cfg.version)
    }
}

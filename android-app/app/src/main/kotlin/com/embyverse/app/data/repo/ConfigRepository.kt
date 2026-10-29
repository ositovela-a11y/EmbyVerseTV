package com.embyverse.app.data.repo

import android.content.Context
import android.util.AtomicFile
import com.embyverse.app.BuildConfig
import com.embyverse.app.data.net.await
import com.embyverse.core.config.ApplyResult
import com.embyverse.core.config.ConfigManager
import com.embyverse.core.config.ConfigParseResult
import com.embyverse.core.config.ConfigStorage
import com.embyverse.core.config.RemoteConfig
import com.embyverse.core.config.RemoteConfigParser
import com.embyverse.core.net.readLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/** Almacenamiento con escritura atómica: un corte de energía nunca deja una configuración a medias. */
private class FileConfigStorage(dir: File) : ConfigStorage {
    private val root = dir.apply { mkdirs() }
    private fun f(key: String) = AtomicFile(File(root, key.replace(Regex("[^a-zA-Z0-9_.]"), "_")))
    override fun read(key: String): String? = runCatching { String(f(key).readFully(), Charsets.UTF_8) }.getOrNull()
    override fun write(key: String, value: String?) {
        val file = f(key)
        if (value == null) { file.delete(); return }
        val out = file.startWrite()
        try { out.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
}

sealed interface ConfigStatus {
    data object NotConfigured : ConfigStatus
    data object Idle : ConfigStatus
    data object Checking : ConfigStatus
    data class UpToDate(val version: Int) : ConfigStatus
    data class Updated(val version: Int) : ConfigStatus
    data class Rejected(val reasons: List<String>) : ConfigStatus
    data class NeedsAppUpdate(val minVersionCode: Int) : ConfigStatus
    data class Offline(val message: String) : ConfigStatus
    data class RolledBack(val version: Int?) : ConfigStatus
}

/**
 * Descarga la configuración visual publicada desde el panel y la aplica con validación,
 * caché, versión, valores predeterminados y reversión (toda la lógica está en :core ConfigManager).
 */
class ConfigRepository(context: Context, private val client: OkHttpClient) {
    private val bundled: RemoteConfig = runCatching {
        val text = context.assets.open("default_config.json").use { String(it.readBytes(), Charsets.UTF_8) }
        (RemoteConfigParser.parse(text) as? ConfigParseResult.Valid)?.config
    }.getOrNull() ?: RemoteConfig.DEFAULT

    private val manager = ConfigManager(FileConfigStorage(File(context.filesDir, "remote_config")), BuildConfig.VERSION_CODE, bundled)

    private val _config = MutableStateFlow(manager.current())
    val config: StateFlow<RemoteConfig> = _config

    private val _status = MutableStateFlow<ConfigStatus>(if (BuildConfig.CONFIG_BASE_URL.isBlank()) ConfigStatus.NotConfigured else ConfigStatus.Idle)
    val status: StateFlow<ConfigStatus> = _status

    /** Se llama al arrancar: revierte automáticamente una configuración que impidió abrir la app. */
    fun onAppStart() {
        val unverified = _config.value.version
        if (manager.onStartup()) {
            _config.value = manager.current()
            _status.value = ConfigStatus.RolledBack(unverified)
        }
    }

    /** La pantalla de inicio se dibujó correctamente con la configuración actual. */
    fun markHealthy() = manager.markHealthy()

    fun rollbackCurrent() {
        manager.rollback(_config.value.version)
        _config.value = manager.current()
        _status.value = ConfigStatus.RolledBack(null)
    }

    private fun endpoint(): String? {
        val base = BuildConfig.CONFIG_BASE_URL.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val isLocalDebug = BuildConfig.DEBUG && Regex("^http://(10\\.0\\.2\\.2|localhost|127\\.0\\.0\\.1)(:\\d+)?$").matches(base)
        if (!base.startsWith("https://") && !isLocalDebug) return null // sólo HTTPS en producción
        return "$base/api/v1/public/config?platform=android&appVersion=${BuildConfig.VERSION_CODE}"
    }

    suspend fun refresh(): ConfigStatus = withContext(Dispatchers.IO) {
        val url = endpoint() ?: return@withContext ConfigStatus.NotConfigured.also { _status.value = it }
        _status.value = ConfigStatus.Checking
        val status = try {
            val req = Request.Builder().url(url).header("Accept", "application/json")
                .apply { manager.etag?.let { header("If-None-Match", it) } }.build()
            client.newCall(req).await().use { resp ->
                when {
                    resp.code == 304 -> ConfigStatus.UpToDate(_config.value.version)
                    !resp.isSuccessful -> ConfigStatus.Offline("HTTP ${resp.code}")
                    else -> {
                        val text = String(readLimited(resp.body.byteStream(), RemoteConfigParser.MAX_BYTES + 1), Charsets.UTF_8)
                        when (val r = manager.onFetched(text, resp.header("ETag"))) {
                            is ApplyResult.Applied -> { _config.value = manager.current(); ConfigStatus.Updated(r.version) }
                            ApplyResult.NotNewer -> ConfigStatus.UpToDate(_config.value.version)
                            is ApplyResult.Rejected -> ConfigStatus.Rejected(r.reasons)
                            is ApplyResult.RequiresAppUpdate -> ConfigStatus.NeedsAppUpdate(r.minAppVersionCode)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ConfigStatus.Offline(e.javaClass.simpleName)
        }
        _status.value = status
        status
    }
}

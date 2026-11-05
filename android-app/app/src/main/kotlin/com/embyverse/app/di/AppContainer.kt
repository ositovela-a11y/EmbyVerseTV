package com.embyverse.app.di

import android.app.Application
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import com.embyverse.app.data.db.EmbyDatabase
import com.embyverse.app.data.net.Network
import com.embyverse.app.data.net.OkHttpFetcher
import com.embyverse.app.data.prefs.PreferencesRepository
import com.embyverse.app.data.repo.CatalogRepository
import com.embyverse.app.data.repo.ConfigRepository
import com.embyverse.app.data.repo.EpgRepository
import com.embyverse.app.data.repo.LibraryRepository
import com.embyverse.app.data.repo.SourceRepository
import com.embyverse.app.data.secure.CredentialStore

/** Inyección de dependencias manual: un único grafo por proceso, sin reflexión. */
class AppContainer(val app: Application) {
    val okHttp by lazy { Network.client() }
    val database by lazy { EmbyDatabase.build(app) }
    val prefs by lazy { PreferencesRepository(app) }
    val credentials by lazy { CredentialStore(app) }
    val sources by lazy { SourceRepository(app, database, credentials, OkHttpFetcher(okHttp), prefs) }
    val parental by lazy { com.embyverse.app.data.repo.ParentalControl(prefs, database) }
    val catalog by lazy { CatalogRepository(database, parental.hidden) }
    val library by lazy { LibraryRepository(database, prefs) }
    val epg by lazy { EpgRepository(database, okHttp, sources) { config.config.value.texts["provider.epgUrl"] } }
    val config by lazy { ConfigRepository(app, okHttp) }
    val updater by lazy { com.embyverse.app.data.repo.AppUpdater(app, okHttp) }

    /**
     * Imágenes: cliente propio (no compite con la descarga de listas y guías), muchas descargas en paralelo
     * por servidor (antes 5), tiempos cortos, caché en disco grande y pósters al tamaño de la tarjeta.
     */
    private val imageHttp by lazy {
        okHttp.newBuilder()
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 40; maxRequestsPerHost = 10 })
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { ch -> ch.proceed(ch.request().newBuilder().header("User-Agent", com.embyverse.app.data.net.IMAGE_USER_AGENT).build()) }
            .build()
    }

    val imageLoader by lazy {
        ImageLoader.Builder(app)
            .components {
                add(com.embyverse.app.data.net.TmdbSizeInterceptor())
                add(OkHttpNetworkFetcherFactory(callFactory = { imageHttp }))
            }
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(app, 0.25).build() }
            .diskCache { coil3.disk.DiskCache.Builder().directory(java.io.File(app.cacheDir, "images").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .crossfade(120)
            .eventListener(com.embyverse.app.data.net.ImageStats)
            .build()
    }
}

package com.embyverse.app.di

import android.app.Application
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
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

    val imageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttp })) }
            .crossfade(true)
            .build()
    }
}

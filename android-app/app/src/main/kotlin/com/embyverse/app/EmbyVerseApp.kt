package com.embyverse.app

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.embyverse.app.data.repo.ProviderHost
import com.embyverse.app.di.AppContainer
import com.embyverse.app.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class EmbyVerseApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        container = AppContainer(this)
        container.config.onAppStart()
        SyncWorker.schedule(this)
        // Si el panel cambia el servidor del proveedor, las cuentas se pasan solas al nuevo.
        appScope.launch {
            container.config.config.collect { cfg ->
                runCatching { ProviderHost.of(cfg)?.let { ProviderHost.follow(this@EmbyVerseApp, container.database, it) } }
            }
        }
        // Cada vez que se abre la app: si una lista tiene más de 4 h, se actualiza en segundo plano
        // (lo nuevo del proveedor aparece solo, sin pulsar «Actualizar»). Lo ya cargado sigue visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) appScope.launch {
                runCatching {
                    val limit = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(STALE_HOURS)
                    container.database.sources().all().filter { (it.lastSyncAt ?: 0) in 1 until limit }
                        .forEach { SyncWorker.syncNow(this@EmbyVerseApp, it.id) }
                }
            }
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader

    companion object { const val STALE_HOURS = 4L }
}

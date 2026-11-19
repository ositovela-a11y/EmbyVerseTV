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
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        com.embyverse.app.data.repo.ChannelHealth.init(this)
        // Equipos lentos (Fire Stick / TV Box con poca memoria): modo ligero activado de inicio.
        runCatching {
            val am = getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            // En celular viene activado siempre (más fluido y menos batería); en TV, según la memoria.
            val phone = !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            com.embyverse.app.data.prefs.LiteDefault.value = phone || am.isLowRamDevice || am.memoryClass <= 128 || mi.totalMem < 1_700L * 1024 * 1024
        }
        container = AppContainer(this)
        com.embyverse.app.data.prefs.UiPrefs.init(this)
        com.embyverse.app.ui.components.Connectivity.start(this)
        appScope.launch { com.embyverse.app.data.repo.NewBadge.refresh(container.database) }
        container.config.onAppStart()
        SyncWorker.schedule(this)
        // Si el panel cambia el servidor del proveedor, las cuentas se pasan solas al nuevo.
        appScope.launch {
            container.config.config.collect { cfg ->
                runCatching { ProviderHost.of(cfg)?.let { ProviderHost.follow(this@EmbyVerseApp, container.database, it) } }
            }
        }
        // Al conectarse a internet (Wi-Fi o datos): se revisan el diseño publicado y las actualizaciones.
        runCatching {
            getSystemService(android.net.ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    appScope.launch { runCatching { container.config.refresh() }; container.updater.check() }
                }
            })
        }
        // Cada vez que se abre la app o se vuelve a ella (estuvo fuera 1 min o más), las listas se actualizan en
        // segundo plano: lo nuevo del proveedor aparece solo, sin pulsar «Actualizar». Lo ya cargado sigue visible.
        var leftAt = 0L
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) leftAt = System.currentTimeMillis()
            // Al volver a la app: diseño del panel y versión nueva de la app (sin reinstalar ni Downloader).
            // Al salir de la app: «Continuar viendo» y favoritos en la pantalla de inicio de la TV.
            if (e == Lifecycle.Event.ON_STOP) appScope.launch {
                com.embyverse.app.data.repo.TvHome.sync(this@EmbyVerseApp, container)
                ContinueWidget.update(this@EmbyVerseApp)
            }
            if (e == Lifecycle.Event.ON_START) appScope.launch {
                runCatching { container.config.refresh() }
                container.updater.check()
            }
            if (e == Lifecycle.Event.ON_START) appScope.launch {
                runCatching {
                    val now = System.currentTimeMillis()
                    val returning = leftAt == 0L || now - leftAt >= awayMs
                    // Al abrir o volver: lo que tenga más de 2 min. (Volver al instante, p. ej. de una notificación, no recarga.)
                    val limit = now - if (returning) RESUME_MIN_AGE_MS else TimeUnit.HOURS.toMillis(STALE_HOURS)
                    container.database.sources().all().filter { (it.lastSyncAt ?: 0) in 1 until limit }
                        .forEach { SyncWorker.syncNow(this@EmbyVerseApp, it.id) }
                }
            }
        })
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader

    companion object {
        const val STALE_HOURS = 4L
        /** Volver a la app actualiza lo que tenga más de 2 min (las pruebas lo bajan para no esperar). */
        @Volatile var RESUME_MIN_AGE_MS = 2 * 60_000L
        /** Tiempo fuera de la app que cuenta como «volver» (las pruebas lo bajan para no esperar). */
        @Volatile var awayMs = 60_000L
    }
}

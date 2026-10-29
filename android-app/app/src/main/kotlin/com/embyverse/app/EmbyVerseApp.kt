package com.embyverse.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.embyverse.app.di.AppContainer
import com.embyverse.app.work.SyncWorker

class EmbyVerseApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.config.onAppStart()
        SyncWorker.schedule(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}

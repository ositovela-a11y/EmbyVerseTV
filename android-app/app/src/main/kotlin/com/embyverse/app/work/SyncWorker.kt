package com.embyverse.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.embyverse.app.EmbyVerseApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Actualización en segundo plano: configuración visual y catálogos de más de 12 h.
 * También se usa para sincronizar una fuente concreta justo después de guardarla.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as EmbyVerseApp).container
        val only = inputData.getString(KEY_SOURCE)
        if (only != null) {
            c.sources.sync(only)
            c.epg.refresh(only)
            return Result.success()
        }
        c.config.refresh()
        c.sources.syncAll(olderThanMs = TimeUnit.HOURS.toMillis(12))
        c.sources.sources.first().forEach { c.epg.refresh(it.id) }
        return Result.success()
    }

    companion object {
        private const val KEY_SOURCE = "source"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(12, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("embyverse-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun syncNow(context: Context, sourceId: String) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
                .setInputData(workDataOf(KEY_SOURCE to sourceId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-$sourceId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

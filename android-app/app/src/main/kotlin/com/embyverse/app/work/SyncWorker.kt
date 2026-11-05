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
            notifyNew(c)
            return Result.success()
        }
        c.config.refresh()
        c.sources.syncAll(olderThanMs = TimeUnit.HOURS.toMillis(4))
        c.sources.sources.first().forEach { c.epg.refresh(it.id) }
        notifyNew(c)
        return Result.success()
    }

    /** «Se agregaron N películas y M series»: aviso después de actualizar las listas (solo si hay nuevas). */
    private suspend fun notifyNew(c: com.embyverse.app.di.AppContainer) = runCatching {
        val sp = applicationContext.getSharedPreferences("new_content", Context.MODE_PRIVATE)
        val nowSec = System.currentTimeMillis() / 1000
        val since = sp.getLong("since", 0L)
        sp.edit().putLong("since", nowSec).apply()
        if (since == 0L) return@runCatching // primera vez: solo se guarda la fecha
        val m = c.database.movies().countAddedSince(since); val s = c.database.series().countAddedSince(since)
        if (m + s <= 0) return@runCatching
        val ctx = applicationContext
        if (android.os.Build.VERSION.SDK_INT >= 26) ctx.getSystemService(android.app.NotificationManager::class.java)?.createNotificationChannel(
            android.app.NotificationChannel("new_content", ctx.getString(com.embyverse.app.R.string.new_added_title), android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val pi = android.app.PendingIntent.getActivity(ctx, 77, android.content.Intent(ctx, com.embyverse.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, "new_content").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(ctx.getString(com.embyverse.app.R.string.new_added_title))
            .setContentText(ctx.getString(com.embyverse.app.R.string.new_added_body, m, s)).setAutoCancel(true).setContentIntent(pi).build()
        androidx.core.app.NotificationManagerCompat.from(ctx).notify(7701, n)
    }

    companion object {
        private const val KEY_SOURCE = "source"
        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("embyverse-sync", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun syncNow(context: Context, sourceId: String) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
                .setInputData(workDataOf(KEY_SOURCE to sourceId)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-$sourceId", ExistingWorkPolicy.REPLACE, req)
        }
    }
}

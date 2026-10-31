package com.embyverse.app.data.repo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.embyverse.app.MainActivity
import com.embyverse.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Recordatorios de partidos/eventos: 10 minutos antes avisa (notificación y, si la app está abierta,
 * un aviso en pantalla) con «Ver ahora». Se guardan solo en el dispositivo.
 */
object Reminders {
    const val BEFORE_MIN = 10
    const val EXTRA_SOURCE = "play_source"
    const val EXTRA_CHANNEL = "play_channel"
    private const val PREFS = "reminders"
    private const val CHANNEL_ID = "events"

    data class Due(val title: String, val sourceId: String, val channelId: String)

    /** Aviso pendiente de mostrar dentro de la app. */
    val due = MutableStateFlow<Due?>(null)
    private val _keys = MutableStateFlow<Set<String>>(emptySet())
    val keys: StateFlow<Set<String>> = _keys

    fun key(e: LiveEvent) = "${e.channel.sourceId}|${e.channel.providerId}|${e.minuteOfDay}"

    fun load(context: Context) {
        val now = System.currentTimeMillis()
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val valid = sp.all.filter { (_, v) -> (v as? Long ?: 0L) > now - 3600_000L }
        _keys.value = valid.keys
        sp.edit().apply { sp.all.keys.filter { it !in valid.keys }.forEach { remove(it) } }.apply()
    }

    /** Hora de inicio de hoy (o null si el evento no trae hora). */
    fun startMs(e: LiveEvent, now: Calendar = Calendar.getInstance()): Long? {
        val m = e.minuteOfDay ?: return null
        return (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, m / 60); set(Calendar.MINUTE, m % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /** Programa (o quita, si ya estaba) el recordatorio. Devuelve true si quedó programado. */
    fun toggle(context: Context, e: LiveEvent): Boolean? {
        val k = key(e)
        val wm = WorkManager.getInstance(context)
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (k in _keys.value) {
            wm.cancelUniqueWork("rem|$k")
            sp.edit().remove(k).apply(); _keys.value = _keys.value - k
            return false
        }
        val start = startMs(e) ?: return null
        val now = System.currentTimeMillis()
        if (start <= now) return null
        val delay = (start - BEFORE_MIN * 60_000L - now).coerceAtLeast(3_000L)
        wm.enqueueUniqueWork("rem|$k", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("title" to e.title, "sid" to e.channel.sourceId, "cid" to e.channel.providerId, "key" to k)).build())
        sp.edit().putLong(k, start).apply(); _keys.value = _keys.value + k
        return true
    }

    internal fun notify(context: Context, d: Due) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(context, MainActivity::class.java).putExtra(EXTRA_SOURCE, d.sourceId).putExtra(EXTRA_CHANNEL, d.channelId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, (d.sourceId + d.channelId).hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.reminder_title, BEFORE_MIN))
            .setContentText(d.title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_play, context.getString(R.string.action_watch), pi)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify((d.sourceId + d.channelId).hashCode(), n) }
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val d = Reminders.Due(inputData.getString("title").orEmpty(), inputData.getString("sid") ?: return Result.success(),
            inputData.getString("cid") ?: return Result.success())
        Reminders.due.value = d
        Reminders.notify(applicationContext, d)
        return Result.success()
    }
}

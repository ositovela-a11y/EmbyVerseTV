package com.embyverse.app

import android.content.Context
import android.os.Build
import com.embyverse.core.net.UrlSafety
import java.io.File

/**
 * Si la app se cierra por un error inesperado, guarda un informe (sin credenciales) y lo muestra
 * al abrirla de nuevo para que el usuario pueda copiarlo y enviarlo al soporte.
 */
object CrashReport {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { File(appCtx.filesDir, FILE).writeText(format(thread, e)) }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun format(thread: Thread, e: Throwable): String {
        val trace = e.stackTraceToString().lineSequence().take(80).joinToString("\n")
        return buildString {
            append("EmbyVerse ").append(BuildConfig.VERSION_NAME).append(" · Android ").append(Build.VERSION.SDK_INT)
            append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Hilo: ").append(thread.name).append('\n')
            append("Memoria máx.: ").append(Runtime.getRuntime().maxMemory() / 1_048_576).append(" MB\n\n")
            append(UrlSafety.redact(trace))
        }.take(8000)
    }

    /** Devuelve el informe pendiente (y lo borra) o null. */
    fun take(context: Context): String? = runCatching {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) null else f.readText().also { f.delete() }
    }.getOrNull()
}

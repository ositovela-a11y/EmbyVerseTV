package com.embyverse.app.data.repo

import android.content.Context

/**
 * Canales que fallan seguido: se recuerda (en este equipo) cuántas veces falló cada canal en los últimos
 * 7 días. Con 3 fallas o más se manda al final de la lista; cuando vuelve a cargar bien, se olvida.
 */
object ChannelHealth {
    private const val PREFS = "channel_health"
    private const val WEEK = 7 * 24 * 3600_000L
    @Volatile private var bad: Map<String, Int> = emptyMap()
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val m = HashMap<String, Int>()
        sp.all.forEach { (k, v) ->
            val parts = (v as? String)?.split('|') ?: return@forEach
            val count = parts.getOrNull(0)?.toIntOrNull() ?: 0; val last = parts.getOrNull(1)?.toLongOrNull() ?: 0
            if (now - last < WEEK) m[k] = count
        }
        bad = m
    }

    private fun key(sourceId: String, id: String) = "$sourceId|$id"

    fun failed(sourceId: String, id: String) {
        val k = key(sourceId, id); val n = (bad[k] ?: 0) + 1
        bad = bad + (k to n)
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(k, "$n|${System.currentTimeMillis()}")?.apply()
    }

    fun worked(sourceId: String, id: String) {
        val k = key(sourceId, id)
        if (k !in bad) return
        bad = bad - k
        ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(k)?.apply()
    }

    fun isBad(sourceId: String, id: String) = (bad[key(sourceId, id)] ?: 0) >= 3
}

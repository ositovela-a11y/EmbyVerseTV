package com.embyverse.app.data.repo

import com.embyverse.app.data.db.ChannelEntity
import java.util.Calendar

/** Evento deportivo/especial detectado en el nombre de un canal ("10/30 LIGUE 1 12:00pm", "América vs Chivas 21:00"). */
data class LiveEvent(val channel: ChannelEntity, val title: String, val timeLabel: String?, val minuteOfDay: Int?)

object Events {
    /** Un evento deja de mostrarse 2 h después de su hora de inicio (lo que dura un partido). */
    const val FINISHED_AFTER_MIN = 120
    private val TIME = Regex("\\b(\\d{1,2})[:.](\\d{2})\\s*(am|pm|a\\.m\\.|p\\.m\\.)?", RegexOption.IGNORE_CASE)
    private val DATE = Regex("\\b(\\d{1,2})/(\\d{1,2})\\b")
    private val VS = Regex("\\b(vs\\.?|v\\.?|contra)\\b", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("^[A-Z]{2,3}\\s*[|:-]\\s*")

    /** Eventos de HOY (o sin fecha) ordenados por hora. */
    fun today(channels: List<ChannelEntity>, now: Calendar = Calendar.getInstance(), limit: Int = 30): List<LiveEvent> {
        val day = now.get(Calendar.DAY_OF_MONTH); val month = now.get(Calendar.MONTH) + 1
        val nowMinute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return channels.mapNotNull { ch ->
            val name = ch.name.replace(PREFIX, "").trim()
            val time = TIME.find(name)
            val hasVs = VS.containsMatchIn(name)
            if (time == null && !hasVs) return@mapNotNull null
            // Si trae fecha, debe ser hoy (se aceptan MM/DD y DD/MM).
            DATE.find(name)?.let { d ->
                val a = d.groupValues[1].toInt(); val b = d.groupValues[2].toInt()
                if (!((a == month && b == day) || (a == day && b == month))) return@mapNotNull null
            }
            val minute = time?.let {
                var h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
                val ap = it.groupValues[3].lowercase()
                if (ap.startsWith("p") && h < 12) h += 12
                if (ap.startsWith("a") && h == 12) h = 0
                if (h in 0..23 && m in 0..59) h * 60 + m else null
            }
            val title = name.replace(DATE, "").replace(TIME, "").replace(Regex("\\s{2,}"), " ").trim(' ', '-', '|', '·')
            LiveEvent(ch, title.ifBlank { name }, time?.value?.trim(), minute)
        }.filter { e -> e.minuteOfDay == null || e.minuteOfDay >= nowMinute - FINISHED_AFTER_MIN } // ya terminados: fuera
            .distinctBy { it.title.lowercase() + it.minuteOfDay }
            .sortedWith(compareBy({ it.minuteOfDay == null }, { it.minuteOfDay }))
            .take(limit)
    }
}

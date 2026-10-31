package com.embyverse.core.epg

import com.embyverse.core.model.EpgProgram
import java.io.Reader

data class XmltvChannel(val id: String, val displayName: String?, val iconUrl: String?)

data class XmltvResult(val channels: List<XmltvChannel>, val programs: List<EpgProgram>)

/**
 * Lector XMLTV en streaming, sin dependencias (funciona igual en JVM y Android).
 * Sólo conserva los programas dentro de la ventana [fromEpoch, toEpoch] y de los canales
 * solicitados, para no cargar guías de cientos de MB en memoria.
 */
class XmltvParser(private val maxPrograms: Int = 200_000) {

    fun parse(
        reader: Reader,
        fromEpoch: Long,
        toEpoch: Long,
        wantedChannels: Set<String>? = null,
        /**
         * Opcional: para guías externas cuyos ids no coinciden con los de la lista. Recibe cada canal de
         * la guía y devuelve el id con el que se guardará su programación (o null para ignorarlo).
         */
        mapChannel: ((XmltvChannel) -> String?)? = null,
    ): XmltvResult {
        val alias = HashMap<String, String>()
        val r = XmlTokenizer(reader)
        val channels = ArrayList<XmltvChannel>()
        val programs = ArrayList<EpgProgram>()
        var chId: String? = null; var chName: String? = null; var chIcon: String? = null
        var pCh: String? = null; var pStart = 0L; var pStop = 0L; var pTitle: String? = null; var pDesc: String? = null
        var capture: String? = null
        val text = StringBuilder()

        while (true) {
            val ev = r.next() ?: break
            when (ev) {
                is XmlTokenizer.Event.Start -> {
                    when (ev.name) {
                        "channel" -> { chId = ev.attrs["id"]; chName = null; chIcon = null }
                        "display-name" -> if (chId != null && chName == null) { capture = "dn"; text.setLength(0) }
                        "icon" -> if (chId != null) chIcon = ev.attrs["src"]
                        "programme" -> {
                            pCh = ev.attrs["channel"]
                            pStart = parseXmltvTime(ev.attrs["start"]) ?: -1
                            pStop = parseXmltvTime(ev.attrs["stop"]) ?: -1
                            pTitle = null; pDesc = null
                        }
                        "title" -> if (pCh != null && pTitle == null) { capture = "t"; text.setLength(0) }
                        "desc" -> if (pCh != null && pDesc == null) { capture = "d"; text.setLength(0) }
                    }
                    if (ev.selfClosing) r.pushEnd(ev.name)
                }
                is XmlTokenizer.Event.Text -> if (capture != null && text.length < 4000) text.append(ev.text)
                is XmlTokenizer.Event.End -> when (ev.name) {
                    "display-name" -> if (capture == "dn") { chName = text.toString().trim(); capture = null }
                    "title" -> if (capture == "t") { pTitle = text.toString().trim(); capture = null }
                    "desc" -> if (capture == "d") { pDesc = text.toString().trim(); capture = null }
                    "channel" -> {
                        chId?.let { id ->
                            val xc = XmltvChannel(id, chName, chIcon?.takeIf { s -> s.startsWith("http") })
                            channels += xc
                            if (mapChannel != null && (wantedChannels == null || id !in wantedChannels)) mapChannel(xc)?.let { alias[id] = it }
                        }
                        chId = null
                    }
                    "programme" -> {
                        val raw = pCh
                        val ch = when {
                            raw == null -> null
                            wantedChannels == null || raw in wantedChannels -> raw
                            else -> alias[raw]
                        }
                        if (ch != null && pStart > 0 && pStop > pStart && pStop >= fromEpoch && pStart <= toEpoch && programs.size < maxPrograms) {
                            programs += EpgProgram(ch, pStart, pStop, pTitle?.ifBlank { null } ?: "Sin título", pDesc?.ifBlank { null })
                        }
                        pCh = null
                    }
                }
            }
        }
        return XmltvResult(channels, programs.sortedWith(compareBy({ it.channelId }, { it.startEpochSeconds })))
    }

    companion object {
        /** "20261029083000 +0100" → segundos epoch UTC. Sin java.time para mantener minSdk 24. */
        fun parseXmltvTime(s: String?): Long? {
            if (s == null) return null
            val t = s.trim()
            if (t.length < 12 || !t.substring(0, 12).all { it.isDigit() }) return null
            val y = t.substring(0, 4).toInt(); val mo = t.substring(4, 6).toInt(); val d = t.substring(6, 8).toInt()
            val h = t.substring(8, 10).toInt(); val mi = t.substring(10, 12).toInt()
            val sec = if (t.length >= 14 && t[12].isDigit() && t[13].isDigit()) t.substring(12, 14).toInt() else 0
            if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || sec > 60) return null
            var epoch = daysFromCivil(y, mo, d) * 86400L + h * 3600 + mi * 60 + sec
            val tz = Regex("""([+-])(\d{2}):?(\d{2})\s*$""").find(t)
            if (tz != null) {
                val off = tz.groupValues[2].toInt() * 3600 + tz.groupValues[3].toInt() * 60
                epoch -= if (tz.groupValues[1] == "+") off else -off
            }
            return epoch
        }

        // Algoritmo de Howard Hinnant: días desde 1970-01-01 en el calendario gregoriano proléptico.
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468
        }
    }
}

/** Tokenizador XML mínimo y tolerante (no valida; ignora DOCTYPE, comentarios e instrucciones). */
internal class XmlTokenizer(private val reader: Reader) {
    sealed interface Event {
        data class Start(val name: String, val attrs: Map<String, String>, val selfClosing: Boolean) : Event
        data class End(val name: String) : Event
        data class Text(val text: String) : Event
    }

    private val buf = CharArray(64 * 1024)
    private var len = 0
    private var pos = 0
    private var pendingEnd: String? = null

    fun pushEnd(name: String) { pendingEnd = name }

    private fun peek(): Int {
        if (pos >= len) { len = reader.read(buf); pos = 0; if (len <= 0) { len = 0; return -1 } }
        return buf[pos].code
    }
    private fun read(): Int = peek().also { if (it >= 0) pos++ }

    private fun readUntil(terminator: String, max: Int = 1 shl 20): String {
        val sb = StringBuilder()
        while (true) {
            val c = read(); if (c < 0) return sb.toString()
            sb.append(c.toChar())
            if (sb.length >= terminator.length && sb.endsWith(terminator)) return sb.substring(0, sb.length - terminator.length)
            if (sb.length > max) return sb.toString()
        }
    }

    fun next(): Event? {
        pendingEnd?.let { pendingEnd = null; return Event.End(it) }
        while (true) {
            val c = peek()
            if (c < 0) return null
            if (c != '<'.code) {
                val sb = StringBuilder()
                while (peek().let { it >= 0 && it != '<'.code }) sb.append(read().toChar())
                if (sb.isNotBlank()) return Event.Text(decodeEntities(sb.toString()))
                continue
            }
            read() // '<'
            when (peek()) {
                '?'.code -> { readUntil("?>"); continue }
                '!'.code -> {
                    read()
                    if (peek() == '-'.code) { readUntil("-->"); continue }
                    if (peek() == '['.code) { readUntil("CDATA["); return Event.Text(readUntil("]]>")) }
                    readUntil(">"); continue
                }
                '/'.code -> { read(); return Event.End(readUntil(">").trim().substringAfter(':')) }
                else -> {
                    val raw = readTagBody()
                    val selfClosing = raw.endsWith("/")
                    val body = raw.removeSuffix("/").trim()
                    val nameEnd = body.indexOfFirst { it.isWhitespace() }.let { if (it < 0) body.length else it }
                    return Event.Start(body.substring(0, nameEnd), parseAttrs(body.substring(nameEnd)), selfClosing)
                }
            }
        }
    }

    /** Lee hasta '>' respetando comillas en los atributos. */
    private fun readTagBody(): String {
        val sb = StringBuilder()
        var q = 0
        while (true) {
            val c = read(); if (c < 0) break
            if (q != 0) { if (c == q) q = 0 } else if (c == '"'.code || c == '\''.code) q = c else if (c == '>'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun parseAttrs(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        Regex("""([\w:.-]+)\s*=\s*("([^"]*)"|'([^']*)')""").findAll(s).forEach {
            m[it.groupValues[1]] = decodeEntities(it.groupValues[3].ifEmpty { it.groupValues[4] })
        }
        return m
    }

    companion object {
        fun decodeEntities(s: String): String {
            if ('&' !in s) return s
            return Regex("""&(#x[0-9a-fA-F]+|#\d+|amp|lt|gt|quot|apos);""").replace(s) { m ->
                when (val e = m.groupValues[1]) {
                    "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                    else -> runCatching {
                        val cp = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                        String(Character.toChars(cp))
                    }.getOrDefault(m.value)
                }
            }
        }
    }
}

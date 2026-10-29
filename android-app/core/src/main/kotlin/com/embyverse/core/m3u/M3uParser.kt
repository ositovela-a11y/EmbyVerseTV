package com.embyverse.core.m3u

import com.embyverse.core.model.StreamHeaders
import com.embyverse.core.net.SourceError
import com.embyverse.core.net.SourceException
import java.io.BufferedReader
import java.io.Reader
import java.net.URI

/** Entrada cruda de una lista M3U/M3U8 extendida. */
data class M3uEntry(
    val title: String,
    val url: String,
    val durationSeconds: Double,
    val attributes: Map<String, String>,
    val group: String?,
    val headers: StreamHeaders,
) {
    fun attr(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> attributes[k]?.takeIf { it.isNotBlank() } }
}

data class M3uPlaylist(
    val headerAttributes: Map<String, String>,
    val entries: List<M3uEntry>,
    val skippedLines: Int,
) {
    /** Primera URL de guía declarada en la cabecera (url-tvg / x-tvg-url), si existe. */
    val epgUrl: String?
        get() = (headerAttributes["x-tvg-url"] ?: headerAttributes["url-tvg"] ?: headerAttributes["tvg-url"])
            ?.split(',')?.map { it.trim() }?.firstOrNull { it.startsWith("http", ignoreCase = true) }
}

/**
 * Analizador en streaming de listas M3U / M3U8 "extendidas" (las que usan los servicios IPTV).
 *
 * - Tolera BOM, CRLF, líneas vacías, atributos con comillas dobles, simples o sin comillas,
 *   comas dentro de valores entrecomillados y títulos con comas.
 * - Soporta #EXTGRP y #EXTVLCOPT (http-user-agent / http-referrer).
 * - Resuelve URLs relativas contra la URL de la lista.
 * - Ignora entradas con esquemas no http(s)/rtmp/rtsp/udp.
 *
 * Una lista M3U8 de medios HLS (con #EXT-X-TARGETDURATION, etc.) NO es una lista de canales:
 * se detecta y se informa como [SourceError.NotM3u] para que el usuario la añada como stream.
 */
class M3uParser(
    private val maxEntries: Int = 300_000,
    private val maxLineLength: Int = 64 * 1024,
) {
    fun parse(reader: Reader, baseUrl: String? = null): M3uPlaylist {
        val br = if (reader is BufferedReader) reader else BufferedReader(reader, 64 * 1024)
        val entries = ArrayList<M3uEntry>()
        var header: Map<String, String> = emptyMap()
        var pendingInfo: Pair<Double, String>? = null // (duración, resto de la línea EXTINF)
        var pendingGroup: String? = null
        var ua: String? = null
        var ref: String? = null
        var sawHeader = false
        var sawExtinf = false
        var hlsMediaTags = 0
        var lineNo = 0
        var skipped = 0
        val seenUrls = HashSet<String>()

        while (true) {
            var line = br.readLine() ?: break
            lineNo++
            if (line.length > maxLineLength) { skipped++; continue }
            if (lineNo == 1) line = line.removePrefix("﻿")
            line = line.trim()
            if (line.isEmpty()) continue

            if (lineNo <= 3 && line.startsWith("<", ignoreCase = true) &&
                (line.contains("html", true) || line.contains("<?xml", true))) {
                throw SourceException(SourceError.NotM3u)
            }

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    sawHeader = true
                    header = parseAttributes(line.substring(7))
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    sawExtinf = true
                    val rest = line.substring(8)
                    val durEnd = rest.indexOfFirst { it == ' ' || it == ',' || it == '\t' }.let { if (it < 0) rest.length else it }
                    val duration = rest.substring(0, durEnd).toDoubleOrNull() ?: -1.0
                    pendingInfo = duration to rest.substring(durEnd)
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substring(8).trim().ifEmpty { null }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11)
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val k = opt.substring(0, eq).trim().lowercase()
                        val v = opt.substring(eq + 1).trim()
                        when (k) {
                            "http-user-agent" -> ua = v
                            "http-referrer", "http-referer" -> ref = v
                        }
                    }
                }
                line.startsWith("#EXT-X-TARGETDURATION", true) || line.startsWith("#EXT-X-MEDIA-SEQUENCE", true) ||
                    line.startsWith("#EXT-X-STREAM-INF", true) -> hlsMediaTags++
                line.startsWith("#") -> Unit // otras directivas (#KODIPROP, #EXT-X-*, comentarios)
                else -> {
                    val info = pendingInfo
                    if (info == null) { skipped++; continue }
                    val url = resolveUrl(line, baseUrl)
                    if (url == null) { skipped++; pendingInfo = null; continue }
                    if (seenUrls.add(url)) {
                        val (attrs, title) = splitAttributesAndTitle(info.second)
                        val lowerAttrs = attrs.mapKeys { it.key.lowercase() }
                        val group = lowerAttrs["group-title"]?.takeIf { it.isNotBlank() } ?: pendingGroup
                        val headers = StreamHeaders(
                            userAgent = ua ?: lowerAttrs["user-agent"] ?: lowerAttrs["http-user-agent"],
                            referrer = ref ?: lowerAttrs["http-referrer"] ?: lowerAttrs["referer"],
                        )
                        val name = title.ifBlank { lowerAttrs["tvg-name"].orEmpty() }.ifBlank { url.substringAfterLast('/') }
                        entries += M3uEntry(name, url, info.first, lowerAttrs, group?.trim(), headers)
                        if (entries.size >= maxEntries) break
                    }
                    pendingInfo = null; pendingGroup = null; ua = null; ref = null
                }
            }
        }

        // Una playlist HLS (segmentos o variantes) es un único stream, no una lista de canales.
        if (hlsMediaTags > 0) throw SourceException(SourceError.NotM3u)
        if (!sawExtinf && !sawHeader) throw SourceException(SourceError.NotM3u)
        return M3uPlaylist(header.mapKeys { it.key.lowercase() }, entries, skipped)
    }

    private fun resolveUrl(raw: String, base: String?): String? {
        val lower = raw.lowercase()
        val allowed = listOf("http://", "https://", "rtmp://", "rtsp://", "udp://", "rtp://")
        if (allowed.any { lower.startsWith(it) }) return raw
        if (lower.contains("://") || lower.startsWith("file:") || lower.startsWith("content:") ||
            lower.startsWith("javascript:") || lower.startsWith("data:")) return null
        if (base == null || !base.startsWith("http", true)) return null
        return try { URI(base).resolve(raw.replace(" ", "%20")).toString() } catch (_: Exception) { null }
    }

    companion object {
        /** Separa `-1 tvg-id="x" group-title="A, B",Título, con coma` en atributos y título. */
        fun splitAttributesAndTitle(s: String): Pair<Map<String, String>, String> {
            var quote: Char? = null
            for (i in s.indices) {
                val c = s[i]
                if (quote != null) { if (c == quote) quote = null; continue }
                if (c == '"' ) { quote = c; continue }
                if (c == '\'' && i > 0 && s[i - 1] == '=') { quote = c; continue }
                if (c == ',') return parseAttributes(s.substring(0, i)) to s.substring(i + 1).trim()
            }
            return parseAttributes(s) to ""
        }

        fun parseAttributes(s: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var i = 0
            val n = s.length
            while (i < n) {
                while (i < n && (s[i].isWhitespace())) i++
                val keyStart = i
                while (i < n && s[i] != '=' && !s[i].isWhitespace()) i++
                if (i >= n || s[i] != '=') { while (i < n && !s[i].isWhitespace()) i++; continue }
                val key = s.substring(keyStart, i)
                i++ // '='
                if (i < n && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]; i++
                    val vs = i
                    while (i < n && s[i] != q) i++
                    out[key] = s.substring(vs, minOf(i, n)).trim()
                    i++
                } else {
                    val vs = i
                    while (i < n && !s[i].isWhitespace()) i++
                    out[key] = s.substring(vs, i)
                }
            }
            return out
        }
    }
}

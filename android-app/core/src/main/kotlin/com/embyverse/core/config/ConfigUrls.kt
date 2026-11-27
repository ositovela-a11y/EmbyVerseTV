package com.embyverse.core.config

/**
 * Carpeta config/ del sitio a partir de la dirección del diseño que trae la APK. Esa dirección es un archivo
 * (…/config/app.json o …/config/vendors/<vendedor>.json): las listas compartidas (stats.json, blocked.json)
 * están en …/config/, también para las apps de vendedor.
 */
object ConfigUrls {
    fun dir(configUrl: String): String {
        val u = configUrl.trim().substringBefore('?')
        if (u.isEmpty()) return ""
        val i = u.indexOf("/config/")
        return when {
            i > 0 -> u.substring(0, i + "/config/".length)
            u.endsWith(".json") -> u.substringBeforeLast('/') + "/"
            else -> u.trimEnd('/') + "/"
        }
    }
    fun file(configUrl: String, name: String): String = dir(configUrl).let { if (it.isEmpty()) "" else it + name }
}

/**
 * Dirección de respaldo del sitio: si GitHub (ositovela-a11y.github.io/EmbyVerseTV) no responde o ya no tiene el
 * archivo, se pide lo mismo a eversetv.com, que guarda su propia copia del diseño, las listas y update.json.
 */
object SiteMirror {
    const val PRIMARY_HOST = "ositovela-a11y.github.io"
    const val PRIMARY_PREFIX = "/EmbyVerseTV/"
    const val MIRROR = "https://eversetv.com/"

    /** Misma dirección en eversetv.com, o null si no es del sitio. */
    fun alternate(url: String): String? {
        val m = Regex("^https?://([^/]+)(/[^?#]*)(.*)$").find(url) ?: return null
        val (host, path, rest) = m.destructured
        if (!host.equals(PRIMARY_HOST, ignoreCase = true) || !path.startsWith(PRIMARY_PREFIX)) return null
        return MIRROR + path.removePrefix(PRIMARY_PREFIX) + rest
    }

    /** ¿Conviene probar la copia? (sin red hacia GitHub, error del servidor o archivo que ya no está). */
    fun shouldRetry(code: Int?): Boolean = code == null || code >= 500 || code == 404
}

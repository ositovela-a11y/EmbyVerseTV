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

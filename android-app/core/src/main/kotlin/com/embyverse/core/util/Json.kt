package com.embyverse.core.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lectura tolerante de JSON. Los paneles IPTV devuelven el mismo campo como número, cadena,
 * cadena vacía o null según la versión, por eso no se usa deserialización estricta.
 */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)
    ?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

fun JsonObject.int(key: String): Int? = str(key)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
fun JsonObject.long(key: String): Long? = str(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
fun JsonObject.double(key: String): Double? = str(key)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { !it.isNaN() }
fun JsonObject.bool(key: String): Boolean? = when (str(key)?.lowercase()) {
    "1", "true", "yes" -> true
    "0", "false", "no" -> false
    else -> null
}
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Primer elemento no vacío si el campo es un array de cadenas, o el valor si es una cadena. */
fun JsonObject.firstString(key: String): String? = when (val v = this[key]) {
    is JsonArray -> v.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
    is JsonPrimitive -> str(key)
    else -> null
}

fun JsonElement.objects(): List<JsonObject> = when (this) {
    is JsonArray -> mapNotNull { it as? JsonObject }
    is JsonObject -> values.mapNotNull { it as? JsonObject }
    else -> emptyList()
}

/** Duración "01:32:10" o "5530" en segundos. */
fun parseDurationSeconds(s: String?): Long? {
    if (s.isNullOrBlank()) return null
    s.toLongOrNull()?.let { return it.takeIf { v -> v > 0 } }
    val parts = s.split(':').map { it.trim().toLongOrNull() ?: return null }
    return when (parts.size) {
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        2 -> parts[0] * 60 + parts[1]
        else -> null
    }?.takeIf { it > 0 }
}

fun splitGenres(s: String?): List<String> =
    s?.split(',', '/', '|', ';')?.map { it.trim() }?.filter { it.isNotEmpty() && it.length <= 40 }?.distinct().orEmpty()

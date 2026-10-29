package com.embyverse.core.util

import java.security.MessageDigest

/** Identificadores estables y deterministas: la misma URL siempre produce el mismo id, lo que evita duplicados al sincronizar. */
object StableId {
    fun of(value: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(value.trim().toByteArray(Charsets.UTF_8))
        return d.take(10).joinToString("") { "%02x".format(it) }
    }
}

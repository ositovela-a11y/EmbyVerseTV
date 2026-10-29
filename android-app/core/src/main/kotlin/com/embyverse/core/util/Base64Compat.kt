package com.embyverse.core.util

/** Decodificador Base64 estándar (RFC 4648) sin depender de java.util.Base64, que exige API 26. */
object Base64Compat {
    private val table = IntArray(128) { -1 }.also { t ->
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c -> t[c.code] = i }
        t['-'.code] = 62; t['_'.code] = 63 // variante URL-safe
    }

    fun decode(input: String): ByteArray {
        val clean = input.filterNot { it == '\r' || it == '\n' || it == ' ' }.trimEnd('=')
        val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val v = if (c.code < 128) table[c.code] else -1
            require(v >= 0) { "Carácter Base64 no válido" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) { bits -= 8; out.write((buffer shr bits) and 0xFF) }
        }
        return out.toByteArray()
    }
}

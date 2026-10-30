package com.embyverse.core.net

import java.io.InputStream

/** Respuesta HTTP mínima. `body` se lee una sola vez y debe cerrarse. */
class HttpResponse(
    val code: Int,
    val contentType: String?,
    val body: InputStream,
    val finalUrl: String,
) : AutoCloseable {
    fun readText(maxBytes: Int = 32 * 1024 * 1024): String {
        val bytes = body.use { readLimited(it, maxBytes) }
        return String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }

    override fun close() = body.close()
}

/** Lanza [ResponseTooLargeException] si el cuerpo excede el límite, para evitar agotar memoria. */
fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) throw ResponseTooLargeException(maxBytes)
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

class ResponseTooLargeException(val limit: Int) : java.io.IOException("Respuesta mayor de $limit bytes")

/**
 * Abstracción de red. En Android la implementa OkHttp (app/), en pruebas JVM la implementa
 * [JdkHttpFetcher]. Ambas deben lanzar [java.net.SocketTimeoutException] ante timeouts.
 */
interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse
}

/** Errores de conexión clasificados para mostrar mensajes comprensibles. */
sealed class SourceError(val userMessage: String) {
    data object InvalidUrl : SourceError("La dirección no es válida. Usa una URL que empiece por http:// o https://.")
    data object AuthFailed : SourceError("Usuario o contraseña incorrectos.")
    data class AccountInactive(val status: String) : SourceError("La cuenta del proveedor no está activa (estado: $status).")
    data class Http(val code: Int) : SourceError(
        when (code) {
            401, 403 -> "El servidor rechazó el acceso (HTTP $code). Revisa las credenciales."
            404 -> "No se encontró la lista o el servicio en esa dirección (HTTP 404)."
            in 500..599 -> "El servidor del proveedor tiene un problema (HTTP $code). Inténtalo más tarde."
            else -> "El servidor respondió con un error (HTTP $code)."
        }
    )
    data object Timeout : SourceError("El servidor tardó demasiado en responder. Revisa tu conexión o inténtalo más tarde.")
    data class Network(val detail: String?) : SourceError("No se pudo conectar con el servidor. Comprueba la dirección y tu conexión a Internet.")
    data object NotM3u : SourceError("El contenido descargado no es una lista M3U/M3U8 válida.")
    data object NotXtream : SourceError("El servidor no respondió como una API compatible con Xtream Codes. Prueba a añadirlo como lista M3U.")
    data class InvalidResponse(val detail: String) : SourceError("El proveedor devolvió datos con un formato no reconocido.")
    data object Empty : SourceError("La fuente es válida pero no contiene canales ni contenido.")
    data class TooLarge(val limitBytes: Int) : SourceError("La lista es demasiado grande para procesarla en este dispositivo.")
}

class SourceException(val error: SourceError, cause: Throwable? = null) : Exception(error.userMessage, cause)

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Fail(val error: SourceError) : Outcome<Nothing>
}

/** Ejecuta [block] traduciendo excepciones de red a [SourceError]. */
suspend fun <T> classify(block: suspend () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e // la cancelación (salir de la pantalla) no es un error
} catch (e: SourceException) {
    Outcome.Fail(e.error)
} catch (e: java.net.SocketTimeoutException) {
    Outcome.Fail(SourceError.Timeout)
} catch (e: ResponseTooLargeException) {
    Outcome.Fail(SourceError.TooLarge(e.limit))
} catch (e: java.net.MalformedURLException) {
    Outcome.Fail(SourceError.InvalidUrl)
} catch (e: kotlinx.serialization.SerializationException) {
    Outcome.Fail(SourceError.InvalidResponse("JSON no válido"))
} catch (e: IllegalArgumentException) {
    Outcome.Fail(SourceError.InvalidUrl)
} catch (e: java.io.IOException) {
    // Algunos clientes envuelven el timeout en otra IOException
    if (e.cause is java.net.SocketTimeoutException || e.message?.contains("timeout", true) == true) Outcome.Fail(SourceError.Timeout)
    else Outcome.Fail(SourceError.Network(e.javaClass.simpleName))
} catch (e: OutOfMemoryError) {
    // Lista enorme para la memoria del dispositivo: se informa en vez de cerrar la app.
    Outcome.Fail(SourceError.TooLarge(0))
} catch (e: Exception) {
    // Cualquier dato inesperado del proveedor se muestra como error, nunca cierra la app.
    Outcome.Fail(SourceError.InvalidResponse(e.javaClass.simpleName))
}

object UrlSafety {
    /** Sólo se admiten http/https. Se rechazan esquemas como file:, content:, javascript:. */
    fun isHttpUrl(url: String): Boolean {
        val u = url.trim()
        if (u.length > 2048 || u.any { it.isWhitespace() }) return false
        return try {
            val uri = java.net.URI(u)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
        } catch (_: Exception) { false }
    }

    fun isHttps(url: String) = url.trim().startsWith("https://", ignoreCase = true)

    /**
     * Elimina credenciales de una URL para que nunca lleguen a logs ni a reportes de errores:
     * usuario/contraseña en query (Xtream), en la ruta (/live/user/pass/...) y en userinfo.
     */
    fun redact(url: String, secrets: Collection<String> = emptyList()): String {
        var r = url.replace(Regex("(?i)([?&](?:password|pass|pwd|username|user|token)=)[^&#]*"), "$1***")
        r = r.replace(Regex("(?i)(/(?:live|movie|series|timeshift)/)[^/]+/[^/]+/"), "$1***/***/")
        r = r.replace(Regex("^(https?://)[^/@]+@", RegexOption.IGNORE_CASE), "$1***@")
        for (s in secrets) if (s.length >= 3) r = r.replace(s, "***")
        return r
    }
}

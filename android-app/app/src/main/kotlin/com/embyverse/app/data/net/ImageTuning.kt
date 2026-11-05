package com.embyverse.app.data.net

import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.size.Dimension

/**
 * Pósters más rápidos: muchas listas traen la imagen de TMDB en tamaño ORIGINAL (cientos de KB a varios
 * MB). Se pide el tamaño justo para la tarjeta (w185/w342/w500/w780), 5–20 veces más ligero.
 */
class TmdbSizeInterceptor : Interceptor {
    private val seg = Regex("(image\\.tmdb\\.org/t/p/)([^/]+)(/)")

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val url = chain.request.data as? String ?: return chain.proceed()
        // Segundo intento sin cambiar el tamaño (marca #noresize).
        if (url.endsWith("#noresize")) return chain.withRequest(chain.request.newBuilder().data(url.removeSuffix("#noresize")).build()).proceed()
        if (!url.contains("image.tmdb.org/t/p/")) return chain.proceed()
        val px = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
        val target = when {
            px <= 200 -> "w185"; px <= 360 -> "w342"; px <= 520 -> "w500"; px <= 800 -> "w780"; else -> "w1280"
        }
        val sized = seg.replace(url) { m -> m.groupValues[1] + target + m.groupValues[3] }
        return if (sized == url) chain.proceed() else chain.withRequest(chain.request.newBuilder().data(sized).build()).proceed()
    }
}

/** Agente de navegador para las imágenes: algunos CDN de carátulas rechazan agentes desconocidos (403). */
const val IMAGE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/**
 * Diagnóstico de imágenes (sin datos personales): cuántas cargan, cuántas fallan y por qué, por servidor.
 * Se ve en Ajustes › Acerca de y va en el reporte de «Ayuda por WhatsApp».
 */
object ImageStats : coil3.EventListener() {
    @Volatile var ok = 0; private set
    @Volatile var failed = 0; private set
    private val errors = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun onSuccess(request: coil3.request.ImageRequest, result: coil3.request.SuccessResult) { ok++ }
    override fun onError(request: coil3.request.ImageRequest, result: coil3.request.ErrorResult) {
        failed++
        val host = (request.data as? String)?.let { runCatching { java.net.URI(it.substringBefore('#')).host }.getOrNull() } ?: "?"
        val t = result.throwable
        val why = (t.message ?: t.javaClass.simpleName).take(80)
        if (errors.size < 12 || host in errors) errors[host] = "${t.javaClass.simpleName}: $why"
    }

    fun reset() { ok = 0; failed = 0; errors.clear() }

    fun summary(): String = buildString {
        append("Imágenes: $ok cargadas, $failed fallidas")
        errors.entries.take(6).forEach { (h, e) -> append("\n  · $h → $e") }
    }
}

/** Direcciones a probar en orden si una imagen falla (otro tamaño, http/https). */
fun imageCandidates(url: String?): List<String> {
    if (url.isNullOrBlank()) return emptyList()
    val u = url.trim().replace(" ", "%20")
    val out = mutableListOf(u)
    if (u.contains("image.tmdb.org/t/p/")) out += "$u#noresize"
    when {
        u.startsWith("https://") -> out += "http://" + u.removePrefix("https://")
        u.startsWith("http://") && !u.contains("image.tmdb.org") -> out += "https://" + u.removePrefix("http://")
    }
    return out.distinct()
}

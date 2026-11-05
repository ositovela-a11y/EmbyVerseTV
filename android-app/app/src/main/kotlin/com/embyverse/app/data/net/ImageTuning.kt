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
        if (!url.contains("image.tmdb.org/t/p/")) return chain.proceed()
        val px = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed()
        val target = when {
            px <= 200 -> "w185"; px <= 360 -> "w342"; px <= 520 -> "w500"; px <= 800 -> "w780"; else -> "w1280"
        }
        val sized = seg.replace(url) { m -> m.groupValues[1] + target + m.groupValues[3] }
        return if (sized == url) chain.proceed() else chain.withRequest(chain.request.newBuilder().data(sized).build()).proceed()
    }
}

package com.embyverse.app

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.embyverse.app.player.StreamSource
import com.embyverse.core.m3u.M3uParser
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Laboratorio M3U (no forma parte de la prueba normal): descarga listas públicas reales de canales
 * gratuitos (iptv-org), las lee con el lector de la app y trata de reproducir una muestra de canales
 * con el reproductor de la app. Mide qué funciona y por qué falla lo que no, y si cambiar el
 * «User-Agent» (como hacen otros reproductores) recupera canales. Solo registra resultados.
 */
@RunWith(AndroidJUnit4::class)
class M3uLabTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmbyVerseApp
    private fun log(msg: String) { Log.i("EVLAB", msg) }

    private val lists = listOf(
        "https://iptv-org.github.io/iptv/countries/mx.m3u",
        "https://iptv-org.github.io/iptv/countries/es.m3u",
        "https://iptv-org.github.io/iptv/countries/ar.m3u",
    )
    private val uaVlc = "VLC/3.0.20 LibVLC/3.0.20"
    private val uaChrome = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    data class Result(val ok: Boolean, val ms: Long, val error: String)

    /** Intenta reproducir hasta que hay imagen lista (STATE_READY) o falla/agota el tiempo. */
    private fun tryPlay(client: OkHttpClient, url: String, ua: String?, ref: String?, mime: String? = null, timeoutMs: Long = 9_000): Result {
        val inst = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        var ok = false; var err = "tiempo agotado"
        var player: ExoPlayer? = null
        val t0 = System.currentTimeMillis()
        inst.runOnMainSync {
            val p = StreamSource.player(app)
            player = p
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) { ok = true; done.countDown() } }
                override fun onPlayerError(e: PlaybackException) {
                    val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                    err = e.errorCodeName + (http?.let { " HTTP ${it.responseCode}" } ?: "") ; done.countDown()
                }
            })
            p.setMediaSource(StreamSource.mediaSource(client, url, ua, ref, forcedMime = mime))
            p.volume = 0f
            p.prepare(); p.playWhenReady = true
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        val ms = System.currentTimeMillis() - t0
        inst.runOnMainSync { player?.release() }
        return Result(ok, ms, if (ok) "" else err)
    }

    /** Lo mismo que hace la app: la dirección tal cual y, si el formato no era el anunciado, las alternativas. */
    private fun playLikeApp(client: OkHttpClient, url: String, ua: String?, ref: String?): Result {
        val first = tryPlay(client, url, ua, ref)
        if (first.ok || first.error.contains("HTTP 4") || first.error.contains("tiempo")) return first
        for ((alt, mime) in StreamSource.alternatives(url)) { val r = tryPlay(client, alt, ua, ref, mime); if (r.ok) return r }
        return first
    }

    @Test fun laboratorioM3u() {
        val client = app.container.okHttp
        val dl = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
        var totalTried = 0; var okApp = 0; var okWithUa = 0
        val errors = HashMap<String, Int>()
        for (listUrl in lists) {
            val body = runCatching {
                dl.newCall(Request.Builder().url(listUrl).build()).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body.string() }
            }.getOrElse { log("LISTA $listUrl: no se pudo descargar (${it.message})"); continue }
            val pl = runCatching { M3uParser().parse(body.reader(), listUrl) }.getOrElse { log("LISTA $listUrl: no se pudo leer (${it.message})"); continue }
            val e = pl.entries
            val ext = e.groupingBy { it.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "sin").lowercase().take(5) }.eachCount()
            log("LISTA ${listUrl.substringAfterLast('/')}: ${e.size} canales · con User-Agent propio ${e.count { it.headers.userAgent != null }} · con Referer ${e.count { it.headers.referrer != null }} · " +
                "con «|» en la dirección ${e.count { '|' in it.url }} · http ${e.count { it.url.startsWith("http:") }} / https ${e.count { it.url.startsWith("https:") }} · formatos $ext")
            // Muestra repartida a lo largo de la lista.
            val sample = if (e.size <= 14) e else (0 until 14).map { e[it * e.size / 14] }
            for (ch in sample) {
                totalTried++
                val r = playLikeApp(client, ch.url, ch.headers.userAgent, ch.headers.referrer)
                var line = "  ${if (r.ok) "✓" else "✗"} ${ch.title.take(34)} · ${r.ms} ms${if (r.ok) "" else " · ${r.error}"}"
                if (r.ok) okApp++ else {
                    errors[r.error] = (errors[r.error] ?: 0) + 1
                    // ¿Se recupera con otro User-Agent (VLC o navegador), como hacen otros reproductores?
                    for ((label, ua) in listOf("VLC" to uaVlc, "Chrome" to uaChrome)) {
                        val r2 = playLikeApp(client, ch.url, ua, ch.headers.referrer)
                        if (r2.ok) { okWithUa++; line += " → ✓ con User-Agent $label"; break }
                    }
                }
                log(line)
            }
        }
        log("RESUMEN: $totalTried canales probados · reproducen con la app $okApp · se recuperan cambiando User-Agent $okWithUa · fallas: $errors")
    }
}

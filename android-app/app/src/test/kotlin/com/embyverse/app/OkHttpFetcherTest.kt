package com.embyverse.app

import com.embyverse.app.data.net.OkHttpFetcher
import com.embyverse.core.model.SourceSpec
import com.embyverse.core.model.SourceType
import com.embyverse.core.net.Outcome
import com.embyverse.core.net.SourceError
import com.embyverse.core.source.M3uSourceAdapter
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** El cliente OkHttp real de la app con el adaptador M3U del núcleo (JVM, sin dispositivo). */
class OkHttpFetcherTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
    private val adapter = M3uSourceAdapter(OkHttpFetcher(client), openLocal = { error("no usado") })
    @After fun stop() = server.shutdown()

    private fun spec(path: String) = SourceSpec("s", "t", SourceType.M3U_URL, server.url(path).toString())

    @Test fun importaListaYEnviaUserAgent() = runTest {
        server.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:-1 group-title=\"A\",Uno\nhttp://h.example.com/1.ts\n"))
        val r = adapter.sync(spec("/l.m3u").copy(userAgent = "Prueba/1.0"), null)
        assertEquals(1, (r as Outcome.Ok).value.live.size)
        assertEquals("Prueba/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun clasificaErroresHttpYTimeout() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(SourceError.Http(404), (adapter.sync(spec("/a"), null) as Outcome.Fail).error)
        server.enqueue(MockResponse().setBody("#EXTM3U\n").setBodyDelay(2, TimeUnit.SECONDS))
        val e = (adapter.sync(spec("/b"), null) as Outcome.Fail).error
        assertTrue("fue $e", e == SourceError.Timeout)
    }
}

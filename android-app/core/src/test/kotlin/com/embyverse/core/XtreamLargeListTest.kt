package com.embyverse.core

import com.embyverse.core.model.SourceSpec
import com.embyverse.core.model.SourceType
import com.embyverse.core.net.HttpFetcher
import com.embyverse.core.net.HttpResponse
import com.embyverse.core.net.Outcome
import com.embyverse.core.source.XtreamSourceAdapter
import com.embyverse.core.xtream.XtreamClient
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Listas Xtream grandes y con datos "sucios": se leen en streaming y nunca rompen la app. */
class XtreamLargeListTest {
    private val server = FakeProvider()
    @After fun tearDown() = server.close()

    private fun auth() = server.ok("/player_api.php", """{"user_info":{"auth":1,"status":"Active"},"server_info":{}}""")

    private fun movies(n: Int) = buildString {
        append('﻿').append("  \n[")
        for (i in 1..n) {
            if (i > 1) append(',')
            append("""{"stream_id":$i,"name":"Película $i","stream_icon":"","rating":${if (i % 3 == 0) "\"8,5\"" else "null"},"category_id":"${i % 7}","container_extension":"mkv","added":"1700000000","plot":"${"x".repeat(200)}"}""")
        }
        append(']')
    }

    @Test fun `80 000 peliculas en streaming con BOM y categorias`() = runBlocking {
        auth()
        server.ok("/player_api.php#get_vod_categories", """[{"category_id":"1","category_name":"Acción"}]""")
        server.ok("/player_api.php#get_vod_streams", movies(80_000))
        server.ok("/player_api.php#get_live_categories", "[]"); server.ok("/player_api.php#get_live_streams", "[]")
        server.ok("/player_api.php#get_series_categories", "[]"); server.ok("/player_api.php#get_series", "[]")
        val a = XtreamSourceAdapter(XtreamClient(JdkHttpFetcher(timeoutMs = 10_000)))
        val r = a.sync(SourceSpec("s", "x", SourceType.XTREAM, server.base, "u"), "p")
        val cat = (r as Outcome.Ok).value
        assertEquals(80_000, cat.movies.size)
        assertEquals("Acción", cat.movies.first { it.providerId == "8" }.categoryName)
    }

    @Test fun `lista como objeto, JSON roto y secciones vacias no cierran nada`() = runBlocking {
        auth()
        server.ok("/player_api.php#get_live_categories", """[{"category_id":"1","category_name":"MX"}]""")
        server.ok("/player_api.php#get_live_streams", """{"a":{"stream_id":1,"name":"Uno","category_id":"1"},"b":{"stream_id":2,"name":"Dos"}}""")
        server.ok("/player_api.php#get_vod_categories", "[]")
        server.ok("/player_api.php#get_vod_streams", """[{"stream_id":1,"name":"Bien"},{"stream_id":2,"name":"Ro""")
        server.ok("/player_api.php#get_series_categories", "[]"); server.ok("/player_api.php#get_series", "")
        val a = XtreamSourceAdapter(XtreamClient(JdkHttpFetcher(timeoutMs = 5_000)))
        val cat = (a.sync(SourceSpec("s", "x", SourceType.XTREAM, server.base, "u"), "p") as Outcome.Ok).value
        assertEquals(2, cat.live.size)
        assertEquals("MX", cat.live.first { it.providerId == "1" }.categoryName)
        assertEquals("se conserva lo leído antes del corte", 1, cat.movies.size)
    }

    @Test fun `cualquier excepcion inesperada se convierte en error, no en cierre`() = runBlocking {
        val broken = object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = throw IllegalStateException("fallo raro")
        }
        val r = XtreamSourceAdapter(XtreamClient(broken)).test(SourceSpec("s", "x", SourceType.XTREAM, "http://h.example.com", "u"), "p")
        assertTrue("debe ser un error controlado: $r", r is Outcome.Fail)
    }
}

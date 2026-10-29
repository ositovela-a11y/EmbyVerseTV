package com.embyverse.core

import com.embyverse.core.model.SourceSpec
import com.embyverse.core.model.SourceType
import com.embyverse.core.net.Outcome
import com.embyverse.core.net.SourceError
import com.embyverse.core.source.M3uSourceAdapter
import com.embyverse.core.source.XtreamSourceAdapter
import com.embyverse.core.xtream.LiveFormat
import com.embyverse.core.xtream.XtreamClient
import com.embyverse.core.xtream.XtreamCredentials
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceAdaptersTest {
    private val server = FakeProvider()
    private val http = JdkHttpFetcher(timeoutMs = 800)
    private val m3u = M3uSourceAdapter(http, openLocal = { Fixtures.file(it).inputStream() })
    @After fun tearDown() = server.close()

    private fun spec(type: SourceType, loc: String, user: String? = null) = SourceSpec("s1", "Prueba", type, loc, user)
    private fun <T> fail(o: Outcome<T>): SourceError = (o as? Outcome.Fail)?.error ?: error("se esperaba error y fue $o")
    private fun <T> ok(o: Outcome<T>): T = (o as? Outcome.Ok)?.value ?: error("se esperaba éxito y fue $o")

    // ---------- M3U ----------
    @Test fun `importa M3U por URL y resuelve rutas relativas contra la URL final`() = runBlocking {
        server.ok("/lista.m3u", Fixtures.text("tests/fixtures/m3u/sample.m3u"))
        val cat = ok(m3u.sync(spec(SourceType.M3U_URL, "${server.base}/lista.m3u"), null))
        assertEquals(5, cat.live.size); assertEquals(3, cat.movies.size); assertEquals(2, cat.series.size)
        assertTrue(cat.live.any { it.streamUrl == "${server.base}/relativo/canal.ts" })
        val report = ok(m3u.test(spec(SourceType.M3U_URL, "${server.base}/lista.m3u"), null))
        assertEquals(5, report.liveCount)
    }

    @Test fun `importa M3U desde archivo local`() = runBlocking {
        val cat = ok(m3u.sync(spec(SourceType.M3U_FILE, "tests/fixtures/m3u/crlf-no-header.m3u"), null))
        assertEquals(2, cat.live.size)
    }

    @Test fun `errores M3U comprensibles`() = runBlocking {
        server.route("/403.m3u") { Triple(403, "denied", 0L) }
        server.route("/500.m3u") { Triple(503, "x", 0L) }
        server.ok("/html.m3u", "<!DOCTYPE html><html>login</html>")
        server.ok("/vacia.m3u", "#EXTM3U\n")
        server.route("/lenta.m3u") { Triple(200, "#EXTM3U\n", 2_000L) }
        assertEquals(SourceError.AuthFailed, fail(m3u.sync(spec(SourceType.M3U_URL, "${server.base}/403.m3u"), null)))
        assertEquals(SourceError.Http(503), fail(m3u.sync(spec(SourceType.M3U_URL, "${server.base}/500.m3u"), null)))
        assertEquals(SourceError.Http(404), fail(m3u.sync(spec(SourceType.M3U_URL, "${server.base}/nada.m3u"), null)))
        assertEquals(SourceError.NotM3u, fail(m3u.sync(spec(SourceType.M3U_URL, "${server.base}/html.m3u"), null)))
        assertEquals(SourceError.Empty, fail(m3u.sync(spec(SourceType.M3U_URL, "${server.base}/vacia.m3u"), null)))
        assertEquals(SourceError.Timeout, fail(m3u.sync(spec(SourceType.M3U_URL, "${server.base}/lenta.m3u"), null)))
        assertEquals(SourceError.InvalidUrl, fail(m3u.sync(spec(SourceType.M3U_URL, "ftp://x.example.com/a.m3u"), null)))
        assertEquals(SourceError.InvalidUrl, fail(m3u.sync(spec(SourceType.M3U_URL, "no es una url"), null)))
        val net = fail(m3u.sync(spec(SourceType.M3U_URL, "http://127.0.0.1:1/x.m3u"), null))
        assertTrue("conexión rechazada → error de red, fue $net", net is SourceError.Network)
    }

    @Test fun `rechaza listas demasiado grandes`() = runBlocking {
        server.ok("/grande.m3u", "#EXTM3U\n" + "#EXTINF:-1,C\nhttp://h.example.com/c.ts\n".repeat(2000))
        val small = M3uSourceAdapter(http, { error("no") }, maxBytes = 10_000)
        assertTrue(fail(small.sync(spec(SourceType.M3U_URL, "${server.base}/grande.m3u"), null)) is SourceError.TooLarge)
    }

    // ---------- Xtream ----------
    private val user = "demo user"; private val pass = "p@ss/word"
    private fun xtream(status: String = "Active", auth: Int = 1) {
        server.route("/player_api.php") { q ->
            if (q["username"] != user || q["password"] != pass) Triple(200, """{"user_info":{"auth":0}}""", 0L)
            else Triple(200, """{"user_info":{"username":"$user","auth":$auth,"status":"$status","exp_date":"1924992000","max_connections":"2","active_cons":0,"is_trial":"0","allowed_output_formats":["m3u8","ts"]},"server_info":{"url":"internal.example","port":"80","timezone":"UTC"}}""", 0L)
        }
        server.ok("/player_api.php#get_live_categories", """[{"category_id":"1","category_name":"Noticias"},{"category_id":2,"category_name":"Deportes"}]""")
        server.ok("/player_api.php#get_live_streams", """[
            {"num":1,"name":"Demo News","stream_id":101,"stream_icon":"https://img.example.com/n.png","epg_channel_id":"demo.news","category_id":"1","tv_archive":1,"tv_archive_duration":"3"},
            {"num":2,"name":"Demo Sports","stream_id":"102","stream_icon":"","epg_channel_id":null,"category_id":2},
            {"num":2,"name":"Demo Sports (dup)","stream_id":"102","category_id":2},
            {"name":"Sin id"}]""")
        server.ok("/player_api.php#get_vod_categories", """[{"category_id":"10","category_name":"Animación"}]""")
        server.ok("/player_api.php#get_vod_streams", """[{"name":"Big Buck Bunny","stream_id":"501","stream_icon":"https://img.example.com/bbb.jpg","rating":"7.5","added":"1700000000","category_id":"10","container_extension":"mkv","genre":"Animación, Comedia"}]""")
        server.ok("/player_api.php#get_vod_info", """{"info":{"plot":"Un conejo enorme.","genre":"Animación","duration_secs":596,"backdrop_path":["https://img.example.com/bbb-bg.jpg"],"releasedate":"2008-05-20"},"movie_data":{}}""")
        server.ok("/player_api.php#get_series_categories", """[{"category_id":"20","category_name":"Cortos"}]""")
        server.ok("/player_api.php#get_series", """[{"series_id":"900","name":"Open Movies","cover":"https://img.example.com/om.jpg","plot":"Cortos libres","genre":"Animación","releaseDate":"2010-01-01","rating":"8","category_id":"20","backdrop_path":["https://img.example.com/om-bg.jpg"]}]""")
        server.ok("/player_api.php#get_series_info", """{"seasons":[],"episodes":{"1":[{"id":"9001","episode_num":"2","title":"Sintel","container_extension":"mp4","info":{"duration_secs":"888","plot":"Dragón"},"season":1},{"id":"9000","episode_num":1,"title":"Elephants Dream","container_extension":"mkv","info":{"duration":"00:10:54"}}],"2":[{"id":"9002","episode_num":1,"title":"Tears of Steel","container_extension":"mp4"}]}}""")
        server.ok("/player_api.php#get_short_epg", """{"epg_listings":[{"title":"Tm90aWNpYXMgZGUgbGEgbWHDsWFuYQ==","description":"UmVzdW1lbg==","start_timestamp":"1761724800","stop_timestamp":"1761728400"},{"title":"News","start_timestamp":"1761728400","stop_timestamp":"1761732000"}]}""")
    }
    private val client = XtreamClient(http)
    private fun creds(p: String = pass) = XtreamCredentials("${server.base}/", user, p)
    private val xadapter = XtreamSourceAdapter(client, { LiveFormat.TS })

    @Test fun `autenticacion correcta, capacidades y catalogo sin duplicados`() = runBlocking {
        xtream()
        val report = ok(xadapter.test(spec(SourceType.XTREAM, server.base, user), pass))
        assertEquals("Active", report.account!!.status); assertEquals(2, report.account!!.maxConnections)
        assertTrue(report.capabilities!!.live && report.capabilities!!.vod && report.capabilities!!.series)

        val cat = ok(xadapter.sync(spec(SourceType.XTREAM, server.base, user), pass))
        assertEquals(listOf("101", "102"), cat.live.map { it.providerId })
        val news = cat.live[0]
        assertEquals("Noticias", news.categoryName); assertEquals(3, news.catchupDays)
        // usuario y contraseña codificados en la ruta del stream
        assertEquals("${server.base}/live/demo%20user/p%40ss%2Fword/101.ts", news.streamUrl)
        assertEquals(null, cat.live[1].logoUrl)
        val m = cat.movies.single()
        assertEquals("${server.base}/movie/demo%20user/p%40ss%2Fword/501.mkv", m.streamUrl)
        assertEquals(listOf("Animación", "Comedia"), m.genres); assertEquals(7.5, m.rating!!, 0.001)
        assertEquals(2010, cat.series.single().year)
        assertTrue(cat.epgUrl!!.contains("/xmltv.php?username=demo+user&password=p%40ss%2Fword"))
    }

    @Test fun `credenciales incorrectas, cuenta caducada y servidores no compatibles`() = runBlocking {
        xtream()
        assertEquals(SourceError.AuthFailed, fail(xadapter.test(spec(SourceType.XTREAM, server.base, user), "mala")))
        assertEquals(SourceError.AuthFailed, fail(xadapter.test(spec(SourceType.XTREAM, server.base, user), "")))
        val s2 = FakeProvider()
        try {
            val a2 = XtreamSourceAdapter(XtreamClient(http))
            s2.route("/player_api.php") { Triple(200, """{"user_info":{"auth":1,"status":"Expired"}}""", 0L) }
            assertEquals(SourceError.AccountInactive("Expired"), fail(a2.test(spec(SourceType.XTREAM, s2.base, user), pass)))
            s2.route("/player_api.php") { Triple(200, "<html>panel</html>", 0L) }
            assertEquals(SourceError.NotXtream, fail(a2.test(spec(SourceType.XTREAM, s2.base, user), pass)))
            s2.route("/player_api.php") { Triple(200, "{\"user_info\": {", 0L) }
            assertTrue(fail(a2.test(spec(SourceType.XTREAM, s2.base, user), pass)) is SourceError.InvalidResponse)
            s2.route("/player_api.php") { Triple(401, "", 0L) }
            assertEquals(SourceError.AuthFailed, fail(a2.test(spec(SourceType.XTREAM, s2.base, user), pass)))
            s2.route("/player_api.php") { Triple(200, "{}", 3_000L) }
            assertEquals(SourceError.Timeout, fail(a2.test(spec(SourceType.XTREAM, s2.base, user), pass)))
            // Servidor sin player_api.php (404): no es Xtream
            assertEquals(SourceError.NotXtream, fail(a2.test(spec(SourceType.XTREAM, s2.base + "/otro", user), pass)))
        } finally { s2.close() }
    }

    @Test fun `seccion que falla se omite sin romper el resto`() = runBlocking {
        xtream()
        server.route("/player_api.php#get_series_categories") { Triple(500, "error", 0L) }
        val cat = ok(xadapter.sync(spec(SourceType.XTREAM, server.base, user), pass))
        assertTrue(cat.series.isEmpty()); assertEquals(2, cat.live.size)
        server.route("/player_api.php#get_vod_streams") { Triple(200, "no json", 0L) }
        val cat2 = ok(xadapter.sync(spec(SourceType.XTREAM, server.base, user), pass))
        assertTrue(cat2.movies.isEmpty()); assertTrue(cat2.warnings.any { it.contains("películas") })
    }

    @Test fun `episodios, ficha de pelicula y EPG corta`() = runBlocking {
        xtream()
        val eps = client.seriesEpisodes("s1", creds(), "900")
        assertEquals(listOf("9000", "9001", "9002"), eps.map { it.providerId })
        assertEquals(654L, eps[0].durationSeconds); assertEquals(888L, eps[1].durationSeconds)
        assertEquals(2, eps[2].season)
        val movie = client.movieDetails(com.embyverse.core.model.Movie("s1", "501", "BBB", "x"), creds())
        assertEquals("Un conejo enorme.", movie.plot); assertEquals(596L, movie.durationSeconds); assertEquals(2008, movie.year)
        val epg = client.shortEpg(creds(), "101", "demo.news")
        assertEquals("Noticias de la mañana", epg[0].title); assertEquals("Resumen", epg[0].description)
        assertEquals("News", epg[1].title)
    }

    @Test fun `normaliza URLs pegadas por el usuario`() {
        assertEquals("http://h.example.com:8080", XtreamClient.normalizeBaseUrl("h.example.com:8080/"))
        assertEquals("https://h.example.com", XtreamClient.normalizeBaseUrl("https://h.example.com/player_api.php?username=a"))
        val c = XtreamClient.parseCredentialUrl("http://h.example.com:8000/get.php?username=ana&password=s%26cret&type=m3u_plus&output=ts")!!
        assertEquals("http://h.example.com:8000", c.baseUrl); assertEquals("ana", c.username); assertEquals("s&cret", c.password)
        assertEquals(null, XtreamClient.parseCredentialUrl("http://h.example.com/lista.m3u"))
        assertTrue("la contraseña no aparece en toString", !c.toString().contains("s&cret"))
    }
}

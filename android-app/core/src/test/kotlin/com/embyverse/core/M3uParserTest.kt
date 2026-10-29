package com.embyverse.core

import com.embyverse.core.m3u.M3uCatalogBuilder
import com.embyverse.core.m3u.M3uEntry
import com.embyverse.core.m3u.M3uParser
import com.embyverse.core.model.ContentKind
import com.embyverse.core.model.StreamHeaders
import com.embyverse.core.net.SourceError
import com.embyverse.core.net.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

class M3uParserTest {
    private val parser = M3uParser()
    private fun sample() = parser.parse(Fixtures.file("tests/fixtures/m3u/sample.m3u").reader(), "https://lists.example.com/user/list.m3u")

    @Test fun `importa la lista de ejemplo y omite entradas no validas`() {
        val p = sample()
        val urls = p.entries.map { it.url }
        // 12 entradas útiles: se descartan la URL duplicada, el esquema file:// y la URL huérfana sin #EXTINF
        assertEquals(12, p.entries.size)
        assertEquals(1, urls.count { it == "https://streams.example.com/live/news/index.m3u8" })
        assertFalse(urls.any { it.startsWith("file:") })
        assertTrue("https://lists.example.com/user/relativo/canal.ts" in urls)
        assertEquals("https://epg.example.com/guide.xml.gz", p.epgUrl)
        assertTrue(p.skippedLines >= 2)
    }

    @Test fun `analiza atributos, comas y cabeceras por stream`() {
        val e = sample().entries
        val news = e.first { it.attr("tvg-id") == "demo.news" }
        assertEquals("Demo News HD", news.title)
        assertEquals("Noticias, Internacional", news.group)
        assertEquals("5", news.attr("tvg-chno"))
        val sports = e.first { it.attr("tvg-id") == "demo.sports" }
        assertEquals("Demo Sports, Canal 2", sports.title)
        assertEquals("Deportes", sports.group)
        assertEquals(StreamHeaders("EmbyVerseTest/1.0", "https://ref.example.com/"), sports.headers)
        val plain = e.first { it.title == "Canal sin atributos" }
        assertEquals("Variedades", plain.group)
        assertTrue("las cabeceras no se arrastran a la siguiente entrada", plain.headers.isEmpty)
    }

    @Test fun `clasifica en vivo, peliculas y series`() {
        val cat = M3uCatalogBuilder().build("src1", sample())
        assertEquals(setOf("Demo News HD", "Demo Sports, Canal 2", "Canal sin atributos", "Ruta relativa", "Partido S01E01 en directo"),
            cat.live.map { it.name }.toSet())
        assertEquals(setOf("Big Buck Bunny", "Sintel", "Película por atributo"), cat.movies.map { it.title }.toSet())
        val bbb = cat.movies.first { it.title == "Big Buck Bunny" }
        assertEquals(2008, bbb.year); assertEquals(7200L, bbb.durationSeconds); assertEquals("mp4", bbb.containerExtension)

        val ed = cat.series.first { it.title == "Elephants Dream" }
        assertEquals(listOf(1, 2), ed.seasons.keys.toList())
        assertEquals(listOf(1, 2), ed.seasons[1]!!.map { it.episode })
        assertEquals("El comienzo", ed.seasons[1]!![0].title)
        assertEquals("Regreso", ed.seasons[2]!![0].title)
        val tos = cat.series.first { it.title == "Tears of Steel" }
        assertEquals(1, tos.episodes.single().season); assertEquals(3, tos.episodes.single().episode)

        val news = cat.live.first { it.name == "Demo News HD" }
        assertEquals("demo.news", news.epgChannelId); assertEquals(5, news.number)
        assertEquals("https://img.example.com/news.png", news.logoUrl)
        assertTrue(cat.categories.any { it.kind == ContentKind.LIVE && it.name == "Noticias, Internacional" })
    }

    @Test fun `ids estables evitan duplicados entre sincronizaciones`() {
        val a = M3uCatalogBuilder().build("s", sample())
        val b = M3uCatalogBuilder().build("s", sample())
        assertEquals(a.live.map { it.providerId }, b.live.map { it.providerId })
        assertEquals(a.live.size, a.live.map { it.providerId }.toSet().size)
    }

    @Test fun `acepta CRLF y listas sin cabecera EXTM3U`() {
        val p = parser.parse(Fixtures.file("tests/fixtures/m3u/crlf-no-header.m3u").reader())
        assertEquals(listOf("Uno", "Dos"), p.entries.map { it.title })
        assertEquals("A", p.entries[0].group)
    }

    @Test fun `rechaza playlists HLS de un solo stream y paginas HTML`() {
        for (f in listOf("hls-media-playlist.m3u8", "not-m3u.html")) {
            try { parser.parse(Fixtures.file("tests/fixtures/m3u/$f").reader()); fail("$f debería rechazarse") }
            catch (e: SourceException) { assertEquals(SourceError.NotM3u, e.error) }
        }
        try { parser.parse(StringReader("hola\nmundo")); fail() } catch (e: SourceException) { assertEquals(SourceError.NotM3u, e.error) }
    }

    @Test fun `limita el numero de entradas`() {
        val big = buildString { append("#EXTM3U\n"); repeat(50) { append("#EXTINF:-1,C$it\nhttp://h.example.com/$it.ts\n") } }
        assertEquals(10, M3uParser(maxEntries = 10).parse(StringReader(big)).entries.size)
    }

    @Test fun `parser de atributos tolera formatos irregulares`() {
        val (attrs, title) = M3uParser.splitAttributesAndTitle("""-1 tvg-id=abc tvg-name="A, B" tvg-logo='x.png' broken group-title="",Título, con coma""")
        assertEquals("abc", attrs["tvg-id"]); assertEquals("A, B", attrs["tvg-name"]); assertEquals("x.png", attrs["tvg-logo"])
        assertEquals("", attrs["group-title"]); assertEquals("Título, con coma", title)
    }

    @Test fun `patrones de episodio`() {
        val p = M3uCatalogBuilder.parseEpisodeTitle("La Casa - Temporada 2 Episodio 10")!!
        assertEquals("La Casa", p.seriesTitle); assertEquals(2, p.season); assertEquals(10, p.episode)
        assertNull(M3uCatalogBuilder.parseEpisodeTitle("Canal 24 HD"))
        val e = M3uEntry("Show S03E04", "http://h.example.com/a.ts", -1.0, emptyMap(), "Noticias", StreamHeaders())
        assertEquals("un .ts en grupo en vivo sigue siendo canal", ContentKind.LIVE, M3uCatalogBuilder.classify(e))
    }

    @Test fun `lista de demostracion autorizada se clasifica como indica TESTING md`() {
        val cat = M3uCatalogBuilder().build("demo", parser.parse(Fixtures.file("tests/fixtures/m3u/demo-authorized.m3u").reader()))
        assertEquals(2, cat.live.size); assertEquals(2, cat.movies.size); assertEquals(1, cat.series.size)
        assertEquals(2, cat.series.single().episodes.size)
    }
}

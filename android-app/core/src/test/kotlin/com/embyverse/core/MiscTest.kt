package com.embyverse.core

import com.embyverse.core.catalog.CatalogDiff
import com.embyverse.core.catalog.RecItem
import com.embyverse.core.catalog.Recommender
import com.embyverse.core.config.ConfigResolver
import com.embyverse.core.epg.XmltvParser
import com.embyverse.core.model.ContentKind
import com.embyverse.core.net.UrlSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XmltvParserTest {
    @Test fun `lee canales y programas dentro de la ventana`() {
        val from = 1793232000L // 2026-10-29T00:00Z
        val r = XmltvParser().parse(Fixtures.file("tests/fixtures/epg-sample.xml").reader(), from, from + 86400)
        assertEquals(listOf("demo.news", "demo.sports"), r.channels.map { it.id })
        assertEquals("Demo & Sports", r.channels[1].displayName)
        assertEquals("https://img.example.com/news.png", r.channels[0].iconUrl)
        val news = r.programs.filter { it.channelId == "demo.news" }
        assertEquals(listOf("Noticias de la mañana", "Entrevista & análisis"), news.map { it.title })
        assertEquals("Resumen <diario> éxito", news[0].description)
        assertEquals(from + 8 * 3600, news[0].startEpochSeconds)
        assertEquals(from + 8 * 3600, news[1].startEpochSeconds) // 09:00 +0100 = 08:00Z
        val sports = r.programs.single { it.channelId == "demo.sports" }
        assertEquals(from + 14 * 3600, sports.startEpochSeconds) // 08:00 -0600 = 14:00Z
        val filtered = XmltvParser().parse(Fixtures.file("tests/fixtures/epg-sample.xml").reader(), from, from + 86400, setOf("demo.sports"))
        assertEquals(1, filtered.programs.size)
    }

    @Test fun `guia externa se empareja por nombre de canal`() {
        val from = 1793232000L
        // La lista solo conoce "mi.canal"; la guía externa trae "demo.sports" con nombre "Demo & Sports".
        val r = XmltvParser().parse(Fixtures.file("tests/fixtures/epg-sample.xml").reader(), from, from + 86400, setOf("mi.canal")) { xc ->
            if (xc.displayName == "Demo & Sports") "mi.canal" else null
        }
        assertEquals(listOf("mi.canal"), r.programs.map { it.channelId }.distinct())
    }
}

class CatalogLogicTest {
    data class Item(val id: String, val name: String)

    @Test fun `la sincronizacion no duplica y detecta altas, cambios y bajas`() {
        val existing = mapOf("a" to Item("a", "A").hashCode(), "b" to Item("b", "B").hashCode(), "c" to Item("c", "C").hashCode())
        val incoming = listOf(Item("a", "A"), Item("b", "B2"), Item("d", "D"), Item("d", "D duplicado"))
        val plan = CatalogDiff.plan(existing, incoming, { it.id }, { it.hashCode() })
        assertEquals(listOf(Item("b", "B2"), Item("d", "D")), plan.upsert)
        assertEquals(setOf("c"), plan.deleteIds)
        assertEquals(1, plan.unchanged)
    }

    private fun rec(k: String, g: List<String>, cat: String? = null, rating: Double? = null, kind: ContentKind = ContentKind.MOVIE) =
        RecItem(k, kind, k, g, cat, rating, null)

    @Test fun `recomienda por generos y categorias vistas, excluyendo lo ya visto`() {
        val history = listOf(rec("h1", listOf("Animación")))
        val favs = listOf(rec("f1", listOf("Ciencia ficción"), "Cortos"))
        val cands = listOf(rec("h1", listOf("Animación")), rec("x1", listOf("Animación", "Comedia"), rating = 8.0),
            rec("x2", listOf("Terror"), rating = 9.0), rec("x3", listOf("Ciencia ficción"), "Cortos"), rec("l1", listOf("Animación"), kind = ContentKind.LIVE))
        val out = Recommender.recommend(history, favs, cands, 0L).map { it.key }
        assertEquals(listOf("x3", "x1"), out)
        // sin historial: ordena por valoración
        assertEquals("x2", Recommender.recommend(emptyList(), emptyList(), cands, 0L).first().key)
    }
}

class SecurityUtilTest {
    @Test fun `nunca deja credenciales en textos de log`() {
        val u = "http://h.example.com:8080/player_api.php?username=ana&password=S3cr3t&action=get_live_streams"
        val r = UrlSafety.redact(u)
        assertFalse(r.contains("S3cr3t")); assertFalse(r.contains("ana"))
        assertEquals("http://h.example.com/live/***/***/101.ts", UrlSafety.redact("http://h.example.com/live/ana/S3cr3t/101.ts"))
        assertEquals("https://***@h.example.com/a.m3u", UrlSafety.redact("https://ana:S3cr3t@h.example.com/a.m3u"))
        assertFalse(UrlSafety.redact("token S3cr3t usado", listOf("S3cr3t")).contains("S3cr3t"))
    }

    @Test fun `solo se aceptan URLs http y https`() {
        assertTrue(UrlSafety.isHttpUrl("https://a.example.com/x"))
        for (bad in listOf("file:///etc/hosts", "javascript:alert(1)", "content://x/y", "http://", "http://a b.com", "")) assertFalse(bad, UrlSafety.isHttpUrl(bad))
    }

    @Test fun `contraste de texto legible`() {
        assertEquals(0xFF04110A.toInt(), ConfigResolver.readableOn(0xFF7BE52F.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ConfigResolver.readableOn(0xFF1E3A8A.toInt()))
    }
}

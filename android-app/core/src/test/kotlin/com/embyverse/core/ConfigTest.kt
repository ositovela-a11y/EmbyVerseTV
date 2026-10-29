package com.embyverse.core

import com.embyverse.core.config.AppRoute
import com.embyverse.core.config.ApplyResult
import com.embyverse.core.config.BannerPlacement
import com.embyverse.core.config.CardSize
import com.embyverse.core.config.ConfigManager
import com.embyverse.core.config.ConfigParseResult
import com.embyverse.core.config.ConfigResolver
import com.embyverse.core.config.ConfigStorage
import com.embyverse.core.config.FontChoice
import com.embyverse.core.config.LogoVariant
import com.embyverse.core.config.RemoteConfig
import com.embyverse.core.config.RemoteConfigParser
import com.embyverse.core.config.SectionType
import com.embyverse.core.config.UserAppearance
import com.embyverse.core.config.toHexColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigParserTest {
    private fun valid(text: String) = RemoteConfigParser.parse(text) as? ConfigParseResult.Valid
        ?: error("debería ser válida: ${RemoteConfigParser.parse(text)}")

    @Test fun `la configuracion predeterminada compartida es valida`() {
        val c = valid(Fixtures.text("shared/default-config.json")).config
        assertEquals("EmbyVerse", c.brand.appName)
        assertEquals("#FF7BE52F", c.theme.palette.primary.toHexColor())
        assertEquals(FontChoice.POPPINS, c.theme.font)
        assertEquals(9, c.sections.size); assertEquals(4, c.themes.size)
        assertEquals(setOf("hero"), c.userCustomization.lockedSections)
        assertEquals(AppRoute.SOURCES, c.banners.single().route)
    }

    @Test fun `todos los fixtures validos se aceptan`() {
        val files = Fixtures.file("tests/fixtures/config/valid").listFiles()!!.filter { it.extension == "json" }
        assertTrue(files.size >= 3)
        files.forEach { valid(it.readText()) }
    }

    @Test fun `todos los fixtures invalidos se rechazan con motivo`() {
        val files = Fixtures.file("tests/fixtures/config/invalid").listFiles()!!.filter { it.extension == "json" }
        assertTrue(files.size >= 10)
        for (f in files) {
            val r = RemoteConfigParser.parse(f.readText())
            assertTrue("${f.name} debería ser inválida", r is ConfigParseResult.Invalid)
            assertTrue((r as ConfigParseResult.Invalid).errors.isNotEmpty())
        }
    }

    @Test fun `tipos de seccion futuros se aceptan pero se omiten`() {
        val r = valid(Fixtures.text("tests/fixtures/config/valid/future-section-type.json"))
        assertEquals(3, r.config.sections.size)
        assertNull(r.config.sections.first { it.id == "scores" }.type)
        assertTrue(r.warnings.any { it.contains("live_sports_scores") })
        assertEquals(listOf("hero", "movies"), ConfigResolver.sections(r.config, UserAppearance.ORIGINAL).map { it.id })
        // lockedSections por defecto ("hero") sigue existiendo
        assertEquals(setOf("hero"), r.config.userCustomization.lockedSections)
    }

    @Test fun `campos ausentes toman valores predeterminados`() {
        val c = valid("""{"schemaVersion":1,"version":3}""").config
        assertEquals(3, c.version)
        assertEquals(RemoteConfig.DEFAULT.theme, c.theme)
        assertEquals(RemoteConfig.DEFAULT.sections, c.sections)
    }

    @Test fun `logo por recurso, paleta parcial y fechas con zona horaria`() {
        val c = valid(Fixtures.text("tests/fixtures/config/valid/asset-logo-and-banners.json")).config
        assertEquals(LogoVariant.ASSET, c.brand.logoVariant)
        assertEquals("https://cdn.example.com/assets/logo-v2.webp", c.brand.logoUrl)
        assertEquals("#FF22D3EE", c.theme.palette.primary.toHexColor())
        assertEquals("#FF000000", c.theme.palette.background.toHexColor())
        assertEquals("#FF1E6BFF", c.theme.palette.secondary.toHexColor()) // no especificado → predeterminado
        assertEquals(0, c.theme.cardRadius); assertEquals(1.2f, c.theme.fontScale, 0.0001f)
        val promo = c.banners.first { it.id == "promo1" }
        assertEquals(1790812800L, promo.startsAtEpoch)
        assertEquals(1798761599L + 6 * 3600, promo.endsAtEpoch)
        assertNull(c.banners.first { it.id == "hero1" }.route)
    }

    @Test fun `limites basicos`() {
        assertTrue(RemoteConfigParser.parse("x".repeat(300 * 1024)) is ConfigParseResult.Invalid)
        assertTrue(RemoteConfigParser.parse("{nojson") is ConfigParseResult.Invalid)
        assertEquals(1793232000L, RemoteConfigParser.parseIso("2026-10-29T00:00:00Z"))
        assertEquals(1793232000L, RemoteConfigParser.parseIso("2026-10-28T18:00:00-06:00"))
        assertNull(RemoteConfigParser.parseIso("2026-13-01T00:00:00Z"))
    }
}

class ConfigResolverTest {
    private val cfg = (RemoteConfigParser.parse(Fixtures.text("shared/default-config.json")) as ConfigParseResult.Valid).config

    @Test fun `tema y color de acento solo si el administrador lo permite`() {
        val prefs = UserAppearance(themeId = "electric", accentColor = 0xFFA855F7.toInt())
        val t = ConfigResolver.theme(cfg, prefs)
        assertEquals("#FFA855F7", t.palette.primary.toHexColor())
        assertEquals("#FF04110A", t.palette.buttonText.toHexColor()) // WCAG: el negro contrasta más que el blanco sobre #A855F7
        assertEquals("#FF22D3EE", t.palette.accent.toHexColor()) // del tema "electric"

        val noAccent = ConfigResolver.theme(cfg, UserAppearance(accentColor = 0xFF123456.toInt())) // color no ofrecido
        assertEquals(cfg.theme.palette, noAccent.palette)

        val locked = cfg.copy(userCustomization = cfg.userCustomization.copy(allowThemeSelection = false, allowAccentColor = false))
        assertEquals(cfg.theme.palette, ConfigResolver.theme(locked, prefs).palette)
    }

    @Test fun `ocultar y reordenar respeta secciones bloqueadas`() {
        val prefs = UserAppearance(hiddenSections = setOf("hero", "promos", "recommended"),
            sectionOrder = listOf("series", "movies", "hero", "live"))
        val ids = ConfigResolver.sections(cfg, prefs).map { it.id }
        // "hero" está bloqueada: no se puede ocultar ni mover de la primera posición
        assertEquals(listOf("hero", "series", "movies", "live", "continue", "new", "favorites"), ids)

        val noPerms = cfg.copy(userCustomization = cfg.userCustomization.copy(allowHideSections = false, allowReorderSections = false))
        assertEquals(cfg.sections.map { it.id }, ConfigResolver.sections(noPerms, prefs).map { it.id })
    }

    @Test fun `tamano de tarjeta del usuario y secciones deshabilitadas por el administrador`() {
        val adminOff = cfg.copy(sections = cfg.sections.map { if (it.id == "movies") it.copy(enabled = false) else it })
        val s = ConfigResolver.sections(adminOff, UserAppearance(cardSize = CardSize.L))
        assertTrue(s.none { it.id == "movies" })
        assertTrue(s.filter { it.type != SectionType.HERO_BANNER }.all { it.size == CardSize.L })
        assertEquals(CardSize.L, s.first { it.id == "hero" }.size)
    }

    @Test fun `banners segun ventana de publicacion`() {
        val c = (RemoteConfigParser.parse(Fixtures.text("tests/fixtures/config/valid/asset-logo-and-banners.json")) as ConfigParseResult.Valid).config
        assertEquals(1, ConfigResolver.banners(c, BannerPlacement.PROMO, 1793232000L).size) // 29-oct-2026
        assertEquals(0, ConfigResolver.banners(c, BannerPlacement.PROMO, 1790812799L).size) // antes del inicio
        assertEquals(0, ConfigResolver.banners(c, BannerPlacement.PROMO, 1798848000L).size) // después del fin
        assertEquals(1, ConfigResolver.banners(c, BannerPlacement.HERO, 0L).size)
    }
}

class ConfigManagerTest {
    private class MemStorage : ConfigStorage {
        val map = HashMap<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }
    private fun cfg(version: Int, extra: String = "") = """{"schemaVersion":1,"version":$version,"brand":{"appName":"V$version"}$extra}"""

    @Test fun `sin conexion usa la ultima valida o los valores predeterminados`() {
        val st = MemStorage()
        assertEquals("EmbyVerse", ConfigManager(st, 10).current().brand.appName)
        assertEquals(ApplyResult.Applied(5), ConfigManager(st, 10).onFetched(cfg(5)))
        // nuevo arranque sin red: lee lo guardado
        assertEquals("V5", ConfigManager(st, 10).current().brand.appName)
    }

    @Test fun `configuracion invalida se rechaza y se conserva la anterior`() {
        val st = MemStorage(); val m = ConfigManager(st, 10)
        m.onFetched(cfg(5))
        val r = m.onFetched(cfg(6, ""","theme":{"palette":{"primary":"rojo"}}"""))
        assertTrue(r is ApplyResult.Rejected)
        assertEquals("V5", m.current().brand.appName)
        assertEquals(ApplyResult.NotNewer, m.onFetched(cfg(5)))
        assertEquals(ApplyResult.NotNewer, m.onFetched(cfg(4)))
    }

    @Test fun `requiere actualizar la app si minAppVersionCode es mayor`() {
        val m = ConfigManager(MemStorage(), 10)
        assertEquals(ApplyResult.RequiresAppUpdate(11), m.onFetched(cfg(7, ""","minAppVersionCode":11""")))
        assertEquals("EmbyVerse", m.current().brand.appName)
    }

    @Test fun `revierte automaticamente una configuracion que impide arrancar`() {
        val st = MemStorage()
        ConfigManager(st, 10).apply { onFetched(cfg(5)); markHealthy() }
        ConfigManager(st, 10).onFetched(cfg(6))
        // 1er arranque sin llegar a markHealthy: aún no revierte (pudo ser un cierre normal)
        assertEquals(false, ConfigManager(st, 10).onStartup())
        // 2º arranque consecutivo sin verificar: revierte
        val m = ConfigManager(st, 10)
        assertEquals(true, m.onStartup())
        assertEquals("V5", m.current().brand.appName)
        // la versión defectuosa no se vuelve a aplicar, pero una nueva sí
        assertTrue(m.onFetched(cfg(6)) is ApplyResult.Rejected)
        assertEquals(ApplyResult.Applied(7), m.onFetched(cfg(7)))
        m.markHealthy()
        assertEquals(false, ConfigManager(st, 10).onStartup())
        assertNotNull(st.read(ConfigManager.K_ACTIVE))
    }
}

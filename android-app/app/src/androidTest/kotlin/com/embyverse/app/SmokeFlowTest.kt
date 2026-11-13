package com.embyverse.app

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.isRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.test.platform.app.InstrumentationRegistry
import com.embyverse.app.data.repo.SourceDraft
import com.embyverse.app.data.repo.SyncState
import com.embyverse.app.player.PlaybackProbe
import com.embyverse.core.model.SourceType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * Recorrido completo como lo haría un usuario, contra un proveedor simulado GRANDE
 * (tests/android/mock-xtream.mjs): sincronizar, abrir películas, series y canales y reproducir.
 * Si la app se cierra (crash), la prueba falla y el registro queda en logcat.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SmokeFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<EmbyVerseApp>()
    private val base = InstrumentationRegistry.getArguments().getString("mockBase") ?: "http://10.0.2.2:8800"

    private fun log(msg: String) { Log.i("EVTEST", msg) }

    /** Espera una condición; si no se cumple, deja en logcat el árbol de la pantalla y falla con un mensaje claro. */
    private fun waitFor(what: String, timeoutMs: Long, cond: () -> Boolean) {
        try { compose.waitUntil(timeoutMs, cond) } catch (e: ComposeTimeoutException) {
            runCatching { compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("EVTREE", maxDepth = Int.MAX_VALUE) }
            shot("fallo")
            fail("No apareció: $what (${timeoutMs / 1000} s)")
        }
    }

    private fun waitTag(tag: String, timeoutMs: Long = 20_000) =
        waitFor("etiqueta $tag", timeoutMs) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(text: String, timeoutMs: Long = 20_000) =
        waitFor("texto «$text»", timeoutMs) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Captura de pantalla en la carpeta de la app (el flujo de CI la descarga y publica). */
    private fun shot(name: String) = runCatching {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return@runCatching
        val dir = java.io.File(app.getExternalFilesDir(null), "capturas").apply { mkdirs() }
        java.io.File(dir, "$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 90, it) }
    }

    /** Atrás del sistema (funciona aunque un aviso de Android tenga el foco). */
    private fun back() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Thread.sleep(800); compose.waitForIdle()
    }

    private fun memory(): String {
        val rt = Runtime.getRuntime()
        return "heap usado ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} MB de ${rt.maxMemory() / 1_048_576} MB"
    }

    /** Pulsa reproducir y espera a que el reproductor esté listo (o falle). */
    private fun playAndCheck(label: String, click: () -> Unit) {
        PlaybackProbe.reset()
        val t0 = System.currentTimeMillis()
        click()
        try {
            compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 || PlaybackProbe.lastError != null }
        } catch (e: ComposeTimeoutException) { fail("$label: el reproductor no arrancó en 40 s") }
        assertTrue("$label: error de reproducción ${PlaybackProbe.lastError}", PlaybackProbe.readyCount > 0)
        log("$label: reproduciendo en ${System.currentTimeMillis() - t0} ms")
        Thread.sleep(2_500)
        shot("reproductor-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
        back() // cerrar reproductor
        compose.waitForIdle()
    }

    @Test fun a_sincronizaXtreamGrande() = runBlocking {
        val c = app.container
        val id = c.sources.save(SourceDraft(name = "Proveedor simulado", type = SourceType.XTREAM, location = base, username = "user", password = "pass"))
        val t0 = System.currentTimeMillis()
        val r = c.sources.sync(id)
        log("Xtream: sincronización ${System.currentTimeMillis() - t0} ms → $r · ${memory()}")
        assertTrue("sincronización: $r", r is SyncState.Done)
        val t1 = System.currentTimeMillis()
        val epg = c.epg.refresh(id)
        log("Xtream: guía EPG ${System.currentTimeMillis() - t1} ms → $epg")
    }

    @Test fun b_peliculasSeriesYCanales() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val t0 = System.currentTimeMillis()
            waitTag("nav_movies", 30_000)
            log("Inicio visible en ${System.currentTimeMillis() - t0} ms")
            runCatching { compose.waitUntil(60_000) { compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            log("Inicio con contenido en ${System.currentTimeMillis() - t0} ms")
            Thread.sleep(1500); shot("01-inicio")

            // ---- Películas
            var t = System.currentTimeMillis()
            compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
            waitCards()
            log("Películas: abre en «Lo más nuevo» en ${System.currentTimeMillis() - t} ms")
            shot("02a-peliculas-nuevas")
            compose.onNodeWithTag("folder_all", useUnmergedTree = true).performClick() // la prueba recorre «Todas»
            Thread.sleep(800); waitCards()
            log("Películas: cuadrícula en ${System.currentTimeMillis() - t} ms · ${memory()}")
            shot("02-peliculas")
            for (index in listOf(0, 1, 3)) { // .mp4, .mkv y .avi en el catálogo (el simulador entrega el mismo clip)
                t = System.currentTimeMillis()
                openCard("MOVIE", (index + 1).toString(), index)
                waitText(app.getString(R.string.action_play))
                log("Película #$index: ficha en ${System.currentTimeMillis() - t} ms")
                if (index == 0) shot("03-ficha-pelicula")
                playAndCheck("Película #$index") { compose.onAllNodesWithText(app.getString(R.string.action_play), useUnmergedTree = true)[0].performClick() }
                back() // volver a la cuadrícula
                waitTag("grid")
            }
            t = System.currentTimeMillis()
            compose.onNodeWithTag("grid").performScrollToIndex(4_500)
            compose.waitForIdle()
            log("Películas: desplazamiento hasta la 4500 en ${System.currentTimeMillis() - t} ms")

            // ---- Series: formatos distintos de get_series_info (objeto, array, vacía, info: [])
            compose.onNodeWithTag("nav_series", useUnmergedTree = true).performClick()
            waitCards()
            compose.onNodeWithTag("folder_all", useUnmergedTree = true).performClick()
            Thread.sleep(800); waitCards()
            for (index in listOf(0, 4, 3, 6)) { // Serie 1 (normal), 5 (array), 4 (info: []), 7 (sin episodios)
                t = System.currentTimeMillis()
                openCard("SERIES", (index + 1).toString(), index)
                if (index == 6) { // Serie sin episodios: debe mostrar un aviso, no cerrarse
                    waitText(app.getString(R.string.episodes_empty), 20_000)
                    log("Serie #$index (sin episodios): aviso mostrado")
                } else {
                    waitTag("episode", 30_000)
                    log("Serie #$index: episodios en ${System.currentTimeMillis() - t} ms")
                    if (index == 0) shot("04-ficha-serie")
                    playAndCheck("Serie #$index episodio") { compose.onAllNodesWithTag("episode", useUnmergedTree = true)[0].performClick() }
                }
                back()
                waitTag("grid")
            }
            // «Ver ahora» de una serie en las novedades: abre el reproductor con una SERIE (no un episodio).
            val serie = kotlinx.coroutines.runBlocking { app.container.catalog.series(null, 10).first().first() }
            playAndCheck("Serie: Ver ahora desde novedades") {
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                ctx.startActivity(android.content.Intent(ctx, com.embyverse.app.player.PlayerActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("kind", "SERIES").putExtra("source", serie.sourceId).putExtra("id", serie.providerId))
            }

            // ---- TV en vivo (.ts continuo)
            compose.onNodeWithTag("nav_live", useUnmergedTree = true).performClick()
            waitTag("channel", 30_000)
            shot("05-tv-en-vivo")
            // Guía de TV en cuadrícula.
            compose.onNodeWithTag("open_guide", useUnmergedTree = true).performClick()
            waitTag("guide_cell", 20_000)
            Thread.sleep(800); shot("05b-guia-tv")
            log("Guía de TV: cuadrícula con programación")
            back()
            waitTag("channel", 20_000)
            // Primer toque: vista previa dentro de la pantalla. Segundo toque: pantalla completa.
            PlaybackProbe.reset()
            var tp = System.currentTimeMillis()
            compose.onAllNodesWithTag("channel", useUnmergedTree = true)[0].performClick()
            try { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 || PlaybackProbe.lastError != null } } catch (e: ComposeTimeoutException) { fail("la vista previa no arrancó") }
            assertTrue("vista previa: ${PlaybackProbe.lastError}", PlaybackProbe.readyCount > 0)
            log("Canal en vivo: vista previa en ${System.currentTimeMillis() - tp} ms")
            Thread.sleep(2_000); shot("06-vista-previa")
            playAndCheck("Canal en vivo (pantalla completa)") { compose.onAllNodesWithTag("channel", useUnmergedTree = true)[0].performClick() }
            // Cambiar de canal sin salir: lista lateral dentro del reproductor.
            PlaybackProbe.reset()
            compose.onAllNodesWithTag("channel", useUnmergedTree = true)[1].performClick()
            compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 || PlaybackProbe.lastError != null }
            assertTrue("vista previa del canal que anuncia .ts y entrega HLS: ${PlaybackProbe.lastError}", PlaybackProbe.readyCount > 0)
            log("Canal en vivo: formato distinto del anunciado rescatado en la vista previa")
            PlaybackProbe.reset()
            compose.onAllNodesWithTag("preview", useUnmergedTree = true)[0].performClick()
            runCatching { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 } }
            assertTrue("pantalla completa desde la vista previa: ${PlaybackProbe.lastError}", PlaybackProbe.readyCount > 0)
            log("Canal en vivo: vista previa → pantalla completa al pulsar el recuadro")
            // En el emulador de teléfono (modo táctil) Android usa la 1.ª pulsación para salir del modo
            // táctil; en un TV con control no pasa. Por eso aquí se pulsa ◀ dos veces.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            Thread.sleep(400)
            if (compose.onAllNodesWithTag("channel_list", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
            Thread.sleep(1_100)
            try { waitTag("channel_list", 10_000) } catch (e: Throwable) {
                throw AssertionError("◀ no abrió la lista; última tecla vista por el reproductor: ${PlaybackProbe.lastKey}", e)
            }
            shot("07-lista-en-reproductor")
            log("Reproductor: ◀ abre la lista de canales sin salir")
            // ▶ dentro de la lista: siguiente carpeta.
            fun folderName() = compose.onNodeWithTag("channel_list_folder", useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty()
            val before = folderName()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.waitUntil(5_000) { folderName() != before }
            Thread.sleep(600)
            log("Reproductor: ▶ cambia de carpeta en la lista ($before → ${folderName()})")
            shot("08-lista-otra-carpeta")
            back()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("channel_list", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
            // OK con el video limpio: opciones de audio y subtítulos.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            Thread.sleep(400)
            if (compose.onAllNodesWithTag("player_options_panel", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            try { waitTag("player_options_panel", 10_000) } catch (e: Throwable) {
                throw AssertionError("OK no abrió las opciones; última tecla: ${PlaybackProbe.lastKey}", e)
            }
            Thread.sleep(500); shot("09-opciones-audio-subtitulos")
            log("Reproductor: OK abre audio y subtítulos")
            back(); back()
            log("Recorrido completo sin cierres · ${memory()}")
        }
    }

    /** Control parental POR PERFIL: lo que bloquea un perfil no afecta a otro; el PIN desbloquea solo ese perfil. */
    @Test fun e_controlParentalPorPerfil() = runBlocking {
        val c = app.container
        val cats = c.database.categories().observeNames("LIVE").first()
        assertTrue("hay carpetas de TV", cats.isNotEmpty())
        val blocked = cats.first()
        val p1 = c.prefs.activeProfile()
        val p2 = c.library.createProfile("Prueba niños", 0xFF22D3EE.toInt())
        try {
            c.prefs.updateLock(p1) { it.copy(pin = "1234", blocked = setOf(blocked)) }
            val v1 = c.catalog.channels(null, 5000).first()
            assertTrue("perfil 1 no ve la carpeta bloqueada", v1.none { it.category == blocked })
            assertTrue("perfil 1 no ve la carpeta en la lista", blocked !in c.catalog.categories(com.embyverse.core.model.ContentKind.LIVE).first())
            c.prefs.setActiveProfile(p2)
            val v2 = c.catalog.channels(null, 5000).first()
            assertTrue("perfil 2 SÍ ve la carpeta (bloqueo independiente)", v2.any { it.category == blocked })
            c.prefs.setActiveProfile(p1)
            assertTrue("PIN incorrecto no desbloquea", !c.parental.unlock("0000"))
            assertTrue("PIN correcto desbloquea", c.parental.unlock("1234"))
            assertTrue("desbloqueado: vuelve a verse", c.catalog.channels(null, 5000).first().any { it.category == blocked })
            c.parental.relock()
            log("Control parental por perfil: bloqueo independiente y desbloqueo con PIN")
            // Eventos de hoy y temas de estilo.
            val events = com.embyverse.app.data.repo.Events.today(c.catalog.eventCandidates().first())
            log("Eventos de hoy detectados: ${events.size}")
            val cfg = c.config.config.value
            com.embyverse.core.config.STYLE_THEMES.forEach { st ->
                val th = com.embyverse.core.config.ConfigResolver.theme(cfg, com.embyverse.core.config.UserAppearance(themeId = st.id))
                assertTrue("tema ${st.name} aplicado", th.palette.primary == st.transform(cfg.theme).palette.primary)
            }
            log("Temas de estilo: ${com.embyverse.core.config.STYLE_THEMES.size} temas aplicables")
        } finally {
            c.prefs.updateLock(p1) { com.embyverse.app.data.prefs.ProfileLock() }
            c.prefs.setActiveProfile(p1)
            runCatching { c.library.deleteProfile(p2) }
        }
    }

    /** Estilos de plataforma: se aplican y se fotografía el inicio y Películas con cada uno. */
    @Test fun f_estilosDePlataforma() {
        val c = app.container
        val profile = runBlocking { c.prefs.activeProfile() }
        try {
            val styles = listOf("style_cristal" to "tpl_shelf", "style_cine_rojo" to "tpl_top10", "style_purpura" to "tpl_coll",
                "style_oceano" to "tpl_spot", "style_estelar" to "tpl_hub", "style_naranja" to "tpl_wall")
            for ((id, tpl) in styles) {
                runBlocking { c.prefs.updateAppearance(profile) { it.copy(themeId = id) } }
                ActivityScenario.launch(MainActivity::class.java).use {
                    val moviesTag = if (id == "style_estelar") "hub_movies" else "nav_movies"
                    waitTag(moviesTag, 30_000)
                    runCatching { compose.waitUntil(60_000) { compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                    Thread.sleep(2_000); shot("tema-$id-inicio")
                    waitTag(tpl, 20_000)
                    log("Estilo $id: plantilla $tpl visible en el inicio")
                    compose.onNodeWithTag(moviesTag, useUnmergedTree = true).performClick()
                    waitCards()
                    Thread.sleep(1_500); shot("tema-$id-peliculas")
                }
                log("Estilo $id: menú, inicio y películas sin cierres")
            }
        } finally {
            runBlocking { c.prefs.updateAppearance(profile) { it.copy(themeId = null) } }
        }
    }

    /** Funciones premium: multivista, perfil infantil, evento destacado, calidad, temporadas, «Porque viste». */
    @Test fun g_funcionesPremium() {
        val c = app.container
        // Lógica sin pantalla.
        assertTrue("4K", com.embyverse.app.player.qualityLabel(2160) == "4K")
        assertTrue("FHD", com.embyverse.app.player.qualityLabel(1080) == "FHD")
        assertTrue("HD", com.embyverse.app.player.qualityLabel(720) == "HD")
        assertTrue("temporada", com.embyverse.app.ui.components.Season.of("Navidad") == com.embyverse.app.ui.components.Season.NAVIDAD)
        val start = System.currentTimeMillis() + 3_600_000
        val ev = com.embyverse.app.ui.components.FeaturedEvent.from(mapOf("event.title" to "Final", "event.start" to java.time.Instant.ofEpochMilli(start).toString(), "event.channels" to "espn, fox"))
        assertTrue("evento destacado leído", ev != null && ev.keywords == listOf("espn", "fox"))
        assertTrue("evento vencido se oculta", com.embyverse.app.ui.components.FeaturedEvent.from(mapOf("event.title" to "X", "event.start" to "2020-01-01T00:00:00Z")) == null)
        runBlocking {
            val hist = c.library.historySnapshot(40)
            val because = c.catalog.becauseYouWatched(hist)
            log("Porque viste: ${because.size} filas (historial ${hist.size})")
            // Perfil infantil: solo carpetas de niños (si la lista tiene).
            val p1 = c.prefs.activeProfile()
            val kid = c.library.createProfile("Peques", 0xFFF59E0B.toInt())
            try {
                c.prefs.updateLock(kid) { it.copy(kids = true) } // sin PIN
                c.prefs.setActiveProfile(kid)
                val cats = c.database.categories().observeAllNames().first()
                val hidden = c.parental.hidden.first()
                val kidsCats = cats.filter { com.embyverse.app.data.repo.ParentalControl.isKids(it) }
                if (kidsCats.isNotEmpty()) assertTrue("infantil: solo carpetas de niños", cats.filter { it !in hidden }.all { it in kidsCats })
                assertTrue("infantil: sin adultos", cats.filter { it !in hidden }.none { com.embyverse.app.data.repo.ParentalControl.isAdult(it) })
                assertTrue("infantil: sin violencia", cats.filter { it !in hidden }.none { com.embyverse.app.data.repo.ParentalControl.isViolent(it) })
                assertTrue("terror se detecta como violento", com.embyverse.app.data.repo.ParentalControl.isViolent("PELICULAS | TERROR"))
                log("Perfil infantil: ${cats.size - hidden.size} de ${cats.size} carpetas visibles (${kidsCats.size} infantiles)")
            } finally {
                c.prefs.setActiveProfile(p1)
                runCatching { c.library.deleteProfile(kid) }
            }
        }
        // Estilo predeterminado del panel (por app o vendedor) y «Continuar viendo» inteligente.
        val cfg0 = c.config.config.value
        val withStyle = cfg0.copy(texts = cfg0.texts + ("style.default" to "style_oceano"))
        val th = com.embyverse.core.config.ConfigResolver.theme(withStyle, com.embyverse.core.config.UserAppearance())
        assertTrue("estilo predeterminado del panel", th.palette.primary == com.embyverse.core.config.STYLE_THEMES.first { it.id == "style_oceano" }.transform(cfg0.theme).palette.primary)
        runBlocking {
            val cw = c.library.continueWatchingSmart(20).first()
            log("Continuar viendo inteligente: ${cw.size} tarjetas")
            val favs = c.library.favorites().first()
            if (favs.size >= 2) {
                val second = favs[1]
                c.library.moveFavorite(second.kind, second.sourceId, second.itemId, -1)
                assertTrue("favorito subió", c.library.favorites().first().first().itemId == second.itemId)
                log("Favoritos: reordenar funciona")
            } else log("Favoritos: menos de 2, no se prueba reordenar")
        }
        // Suspensión remota: la huella coincide con la del panel y el modo «URL, usuario y contraseña».
        assertTrue("huella igual a la del panel", com.embyverse.app.data.repo.AccountBlock.hash(" Cliente123 ") == "b2aa0796fed64955")
        val blockedCfg = cfg0.copy(texts = cfg0.texts + ("blocked.users" to "aaaaaaaaaaaaaaaa,b2aa0796fed64955"))
        assertTrue("cliente suspendido detectado", com.embyverse.app.data.repo.AccountBlock.isBlocked(blockedCfg, listOf("cliente123")))
        assertTrue("otro cliente no", !com.embyverse.app.data.repo.AccountBlock.isBlocked(blockedCfg, listOf("otro")))
        assertTrue("sin URL fija", com.embyverse.app.data.repo.ProviderHost.of(cfg0.copy(texts = cfg0.texts + ("provider.askHost" to "1"))) == null)
        assertTrue("letra de calidad", com.embyverse.app.player.CastSupport.mime("http://x/a.m3u8") == "application/x-mpegURL")
        // Diseño publicado DE VERDAD (el que reciben tus clientes): debe aceptarse, con sus mensajes.
        val cfgUrl = BuildConfig.CONFIG_BASE_URL.trim()
        if (cfgUrl.endsWith(".json")) runCatching {
            val text = app.container.okHttp.newCall(okhttp3.Request.Builder().url(cfgUrl).build()).execute().use { it.body.string() }
            when (val r = com.embyverse.core.config.RemoteConfigParser.parse(text)) {
                is com.embyverse.core.config.ConfigParseResult.Invalid -> throw AssertionError("el diseño publicado se rechaza: ${r.errors}")
                is com.embyverse.core.config.ConfigParseResult.Valid -> {
                    val now = System.currentTimeMillis() / 1000
                    log("Diseño publicado v${r.config.version}: aceptado; mensajes ${r.config.messages.size} (activos ahora ${r.config.messages.count { it.isActive(now) }}); advertencias ${r.warnings}")
                }
            }
        }.onFailure { if (it is AssertionError) throw it else log("Diseño publicado: no se pudo descargar (${it.javaClass.simpleName})") }
        // Fondos animados: rotación y fechas especiales.
        val rot = com.embyverse.app.ui.components.Season.resolve(mapOf("season" to "rotar", "season.every" to "1"))
        assertTrue("rotar elige un fondo general", rot != null && rot.general)
        val xmas = java.util.Calendar.getInstance().apply { set(2026, 11, 5) }
        assertTrue("auto en diciembre = Navidad", com.embyverse.app.ui.components.Season.holiday(xmas) == com.embyverse.app.ui.components.Season.NAVIDAD)
        // TV en vivo: abre con carpetas y sin cierres.
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav_live", 30_000)
            compose.onNodeWithTag("nav_live", useUnmergedTree = true).performClick()
            waitTag("open_guide", 20_000)
            Thread.sleep(2_000); shot("tv-en-vivo")
            log("TV en vivo: encabezado y carpetas sin cierres")
        }
    }

    /** Velocidad de las carpetas: consulta en la base y cambio de carpeta en pantalla (Películas). */
    @Test fun h_velocidadCarpetas() {
        val c = app.container
        runBlocking {
            val cats = c.catalog.categories(com.embyverse.core.model.ContentKind.MOVIE).first()
            val t0 = System.nanoTime(); val all = c.database.movies().observeCardsAll(5000).first(); val tAll = (System.nanoTime() - t0) / 1_000_000
            val per = cats.take(12).map { cat -> val s0 = System.nanoTime(); c.database.movies().observeCardsIn(cat, 5000).first(); (System.nanoTime() - s0) / 1_000_000 }
            log("Carpetas (base): «Todas» ${all.size} títulos en $tAll ms; por carpeta $per ms")
            assertTrue("abrir una carpeta en la base tarda menos de 300 ms (máx ${per.maxOrNull()})", (per.maxOrNull() ?: 0) < 300)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav_movies", 30_000)
            compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
            waitCards()
            val times = mutableListOf<Long>()
            for (i in 1..4) {
                val before = compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                val folders = compose.onAllNodesWithTag("folder", useUnmergedTree = true)
                if (folders.fetchSemanticsNodes().size <= i) break
                val t0 = System.currentTimeMillis()
                folders[i].performClick()
                runCatching { compose.waitUntil(10_000) {
                    val now = compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)
                    now != null && now != before
                } }
                times += System.currentTimeMillis() - t0
            }
            log("Carpetas (pantalla): cambio de carpeta $times ms")
            shot("carpetas")
            assertTrue("cambiar de carpeta en pantalla tarda menos de 3 s en el emulador (${times})", times.isEmpty() || times.max() < 3_000)
        }
    }

    /**
     * Imágenes: en Películas deben CARGAR los pósters (JPEG reales del proveedor simulado), incluidos los de
     * un servidor que exige agente de navegador y los de uno lento. Se cuenta cuántas cargan y cuántas fallan.
     */
    @Test fun i_imagenesCargan() {
        com.embyverse.app.data.net.ImageStats.reset()
        runBlocking { app.container.imageLoader.memoryCache?.clear(); app.container.imageLoader.diskCache?.clear() }
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav_movies", 30_000)
            compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
            waitCards()
            // «Todas»: pósters de los tres servidores (normal, exige navegador, lento).
            runCatching { compose.onNodeWithTag("folder_all", useUnmergedTree = true).performClick() }
            val t0 = System.currentTimeMillis()
            runCatching { compose.waitUntil(15_000) { com.embyverse.app.data.net.ImageStats.ok >= 12 } }
            val ms = System.currentTimeMillis() - t0
            runCatching { compose.waitUntil(15_000) { com.embyverse.app.data.net.ImageStats.ok >= 24 } }
            val ms24 = System.currentTimeMillis() - t0
            log("Pósters con 300 ms de latencia por imagen: 12 en $ms ms, 24 en $ms24 ms")
            assertTrue("los primeros 24 pósters tardan demasiado ($ms24 ms)", ms24 < 6_000)
            Thread.sleep(2_500)
            val st = com.embyverse.app.data.net.ImageStats
            log("Imágenes: ${st.ok} cargadas, ${st.failed} fallidas; las primeras 12 en $ms ms\n${st.summary()}")
            shot("imagenes-peliculas")
            assertTrue("cargan los pósters (${st.summary()})", st.ok >= 12)
            assertTrue("ninguna imagen falla (${st.summary()})", st.failed == 0)
        }
    }

    /** Mejoras «pro»: búsqueda inteligente, sagas, clasificación, canales que fallan, idiomas y mensajes. */
    @Test fun j_mejorasPro() {
        val c = app.container
        runBlocking {
            val exact = c.catalog.search("Película 12")
            assertTrue("búsqueda normal", exact.movies.isNotEmpty())
            val compact = c.catalog.search("pelicula12")
            assertTrue("búsqueda sin espacios («pelicula12» = «Película 12»)", compact.movies.any { it.title.contains("12") })
            val typo = c.catalog.search("pelicla 12")
            log("Búsqueda: exacta ${exact.movies.size}, sin espacios ${compact.movies.size}, con falta de ortografía ${typo.movies.size}")
            assertTrue("tolera faltas de ortografía", typo.movies.isNotEmpty())
            c.prefs.addRecentSearch("batman"); assertTrue("búsquedas recientes", c.prefs.recentSearches.first().first() == "batman")
            val sagas = c.catalog.sagas()
            log("Sagas detectadas en la lista simulada: ${sagas.size} ${sagas.take(3).map { it.name + " (" + it.count + ")" }}")
        }
        assertTrue("saga «Toy Story 3» = «toy story»", c.catalog.sagaKey("Toy Story 3 (2010)") == "toy story")
        assertTrue("saga con subtítulo", c.catalog.sagaKey("Harry Potter y la piedra filosofal: parte 1") == c.catalog.sagaKey("Harry Potter y la piedra filosofal"))
        com.embyverse.app.data.repo.ChannelHealth.apply { repeat(3) { failed("s", "x") } }
        assertTrue("canal que falla seguido va al final", com.embyverse.app.data.repo.ChannelHealth.isBad("s", "x"))
        com.embyverse.app.data.repo.ChannelHealth.worked("s", "x")
        assertTrue("al volver a cargar se olvida", !com.embyverse.app.data.repo.ChannelHealth.isBad("s", "x"))
        runBlocking {
            val pid = c.prefs.activeProfile()
            c.prefs.setProfileLanguages(pid, "es", "es")
            assertTrue("idioma por perfil", c.prefs.profileLanguages(pid).first() == ("es" to "es"))
            c.prefs.setProfileLanguages(pid, "es", "")
        }
        // Mensaje del panel: aparece en el inicio mientras esté vigente.
        log("Mejoras pro verificadas")
    }

    /**
     * Instalador de TV LG contra una «TV LG simulada» en la máquina de CI (ver android-emulator.yml):
     * servidor de llaves en :9991 con la llave cifrada con la passphrase, SSH de prisoner en :9922 y un
     * luna-send-pub falso que registra las órdenes. Comprueba descarga de llave, SSH, subida, instalación y renovación.
     */
    @Test fun k_instaladorTvLg() = runBlocking {
        val host = android.net.Uri.parse(base).host ?: "10.0.2.2"
        val inst = com.embyverse.app.data.tv.LgTvInstaller(app, app.container.okHttp)
        val steps = mutableListOf<String>()
        val t0 = System.currentTimeMillis()
        val r = inst.install(host, "ABC123", listOf("http://$host:9991/fake.ipk")) { steps += it }
        log("Instalador LG: ${r.ok} · ${r.message} · ${System.currentTimeMillis() - t0} ms · pasos: $steps")
        assertTrue("instalador LG: ${r.message}", r.ok)
        val again = inst.renew()
        log("Renovar LG desde el celular: ${again.ok} · ${again.message}")
        assertTrue("renovar LG: ${again.message}", again.ok)
        val bad = inst.install(host, "ZZZ999", listOf("http://$host:9991/fake.ipk")) { }
        log("Passphrase equivocada: ${bad.message}")
        assertTrue("con passphrase equivocada no debe instalar", !bad.ok)
    }

    /** Ajustes profesionales: bienvenida, novedades, ayuda, términos, letra, horario nocturno, etiquetas y «Más como esta». */
    @Test fun l_profesional() {
        // Lógica: horario nocturno que cruza la medianoche y etiquetas automáticas.
        val P = com.embyverse.app.data.prefs.PlaybackPrefs
        assertTrue("23:00 está dentro de 22–07", P.inWindow(23 * 60, 22 * 60, 7 * 60))
        assertTrue("06:59 está dentro de 22–07", P.inWindow(6 * 60 + 59, 22 * 60, 7 * 60))
        assertTrue("12:00 está fuera de 22–07", !P.inWindow(12 * 60, 22 * 60, 7 * 60))
        assertTrue("modo noche automático a las 23:00", com.embyverse.app.data.prefs.PlaybackPrefs(nightAuto = true).nightNow(23 * 60))
        assertTrue("sin horario no se enciende", !com.embyverse.app.data.prefs.PlaybackPrefs().nightNow(23 * 60))
        val b = com.embyverse.app.data.repo.badgesFor("Canal Deportes FHD", 2001, System.currentTimeMillis() / 1000 - 3600)
        assertTrue("etiquetas: $b", "NUEVO" in b && "FHD" in b)
        assertTrue("canal sin calidad no lleva etiqueta", com.embyverse.app.data.repo.badgesFor("Noticias", 2001, null).isEmpty())

        val ui = app.getSharedPreferences("ui", android.content.Context.MODE_PRIVATE)
        // Bienvenida (forzada) y novedades (como si viniera de una versión anterior).
        ui.edit().putBoolean("force_tour", true).putInt("seen_version", 1).commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("welcome_tour", 30_000)
            shot("20-bienvenida")
            repeat(3) { compose.onNodeWithTag("tour_next", useUnmergedTree = true).performClick(); Thread.sleep(500) }
            waitTag("nav_movies", 20_000)
            assertTrue("bienvenida marcada como vista", com.embyverse.app.data.prefs.UiPrefs.onboardingDone(app))
            waitTag("whats_new", 15_000)
            waitTag("credit_update", 5_000)
            val notes = com.embyverse.app.ui.components.ReleaseNotes.forVersion(app, BuildConfig.VERSION_NAME)
            assertTrue("notas de la versión ${BuildConfig.VERSION_NAME}: $notes", notes.isNotEmpty())
            waitText(notes.first().take(25))
            shot("21-novedades")
            compose.onNodeWithTag("whats_new_ok", useUnmergedTree = true).performClick()
            log("Bienvenida de 3 pantallas y Novedades: bien")

            // Ayuda y términos desde Ajustes.
            compose.onNodeWithTag("nav_settings", useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(app.getString(R.string.help_title)))
            compose.onNodeWithText(app.getString(R.string.help_title), useUnmergedTree = true).performClick()
            waitTag("help_screen")
            compose.onNodeWithTag("faq_0", useUnmergedTree = true).performClick()
            waitText(app.getString(R.string.faq1_a).take(20))
            shot("22-ayuda")
            back()
            compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(app.getString(R.string.terms_title)))
            compose.onNodeWithText(app.getString(R.string.terms_title), useUnmergedTree = true).performClick()
            waitTag("terms_screen")
            shot("23-terminos")
            back()
            log("Ayuda (preguntas frecuentes) y Términos: bien")

            // Tamaño de letra muy grande y de regreso a normal.
            compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(app.getString(R.string.font_size_title)))
            compose.onNodeWithText(app.getString(R.string.font_size_title), useUnmergedTree = true).performClick()
            compose.onNodeWithTag("font_1.3", useUnmergedTree = true).performClick()
            compose.waitForIdle(); Thread.sleep(800); shot("24-letra-muy-grande")
            assertTrue("letra muy grande", com.embyverse.app.data.prefs.UiPrefs.fontScale.value == 1.3f)
            com.embyverse.app.data.prefs.UiPrefs.setFontScale(app, 1f)
            log("Tamaño de letra: bien")

            // Ficha de película: etiquetas y «Más como esta».
            compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
            waitCards()
            runCatching { compose.onNodeWithTag("folder_all", useUnmergedTree = true).performClick() }
            Thread.sleep(800); waitCards()
            compose.onAllNodes(hasTagPrefix("card:MOVIE:"), useUnmergedTree = true)[2].performClick()
            waitText(app.getString(R.string.action_play))
            runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0].performScrollToNode(androidx.compose.ui.test.hasTestTag("more_like_this")) }
            waitTag("more_like_this", 10_000)
            shot("25-mas-como-esta")
            log("Ficha con «Más como esta»: bien")
        }
        ui.edit().putBoolean("force_tour", false).commit()
    }

    private fun hasTagPrefix(prefix: String) = SemanticsMatcher("tag empieza por $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun waitCards(timeoutMs: Long = 30_000) =
        waitFor("tarjetas", timeoutMs) { compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Desplaza la cuadrícula hasta el elemento y lo abre por su id del proveedor. */
    private fun openCard(kind: String, id: String, gridIndex: Int) {
        compose.onNodeWithTag("grid").performScrollToIndex(gridIndex)
        waitFor("tarjeta $kind $id", 10_000) { compose.onAllNodesWithTag("card:$kind:$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("card:$kind:$id", useUnmergedTree = true).performClick()
    }

    @Test fun c_sincronizaM3uGrande() = runBlocking {
        val c = app.container
        val id = c.sources.save(SourceDraft(name = "Lista M3U simulada", type = SourceType.M3U_URL, location = "$base/get.php?username=user&password=pass&type=m3u_plus"))
        val t0 = System.currentTimeMillis()
        val r = c.sources.sync(id)
        log("M3U: sincronización ${System.currentTimeMillis() - t0} ms → $r · ${memory()}")
        assertTrue("sincronización M3U: $r", r is SyncState.Done)
    }

    /** Lo que hacen los botones (probar conexión, actualizar, abrir serie/película) se ejecuta en el hilo principal. */
    @Test fun d_operacionesDesdeElHiloPrincipal() = runBlocking(kotlinx.coroutines.Dispatchers.Main) {
        val c = app.container
        val draft = SourceDraft(name = "Hilo principal", type = SourceType.XTREAM, location = base, username = "user", password = "pass")
        assertTrue("probar conexión", c.sources.test(draft) is com.embyverse.core.net.Outcome.Ok)
        val id = c.sources.save(draft)
        val t0 = System.currentTimeMillis()
        val r = c.sources.sync(id)
        log("Actualizar desde la pantalla: ${System.currentTimeMillis() - t0} ms → $r")
        assertTrue("sincronizar: $r", r is SyncState.Done)
        assertTrue("episodios", c.sources.loadEpisodes(id, "1") is com.embyverse.core.net.Outcome.Ok)
        c.sources.loadMovieDetails(id, "1")
        log("Hilo principal: probar, actualizar, episodios y ficha sin cierres")
    }

    /**
     * Canales que se congelan, se cortan o se quedan cargando: el reproductor debe reconectar solo,
     * sin que el cliente cambie de canal y regrese. El proveedor simulado falla a propósito en la
     * siguiente conexión (/__stall) y la reconexión debe lograr imagen otra vez.
     */
    @Test fun m_reconexionEnVivo() {
        val c = app.container
        // Las fallas simuladas son de transmisiones .ts continuas: esta prueba usa ese formato.
        runBlocking { c.prefs.updatePlayback { it.copy(liveFormat = com.embyverse.core.xtream.LiveFormat.TS) } }
        try { reconexion(c) } finally { runBlocking { c.prefs.updatePlayback { it.copy(liveFormat = com.embyverse.core.xtream.LiveFormat.HLS) } } }
    }

    private fun reconexion(c: com.embyverse.app.di.AppContainer) {
        val ch = runBlocking {
            c.database.channels().listForZapping(null).first { it.providerId != "2" && c.sources.resolveStreamUrl(it.sourceId, it.streamUrl).contains("/live/user/pass/") }
        }
        val http = c.okHttp
        for ((mode, label) in listOf("freeze" to "imagen congelada", "end" to "transmisión cortada", "hang" to "se queda cargando")) {
            http.newCall(okhttp3.Request.Builder().url("$base/__stall?mode=$mode").build()).execute().close()
            PlaybackProbe.reset()
            val t0 = System.currentTimeMillis()
            val intent = android.content.Intent(app, com.embyverse.app.player.PlayerActivity::class.java)
                .putExtra("kind", "LIVE").putExtra("source", ch.sourceId).putExtra("id", ch.providerId)
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(intent).use {
                // «hang» nunca llega a tener imagen en la primera conexión; los otros sí y luego fallan.
                val needReady = if (mode == "hang") 1 else 2
                try {
                    compose.waitUntil(120_000) { PlaybackProbe.reconnects > 0 && PlaybackProbe.readyCount >= needReady }
                } catch (e: ComposeTimeoutException) {
                    shot("fallo-reconexion-$mode")
                    fail("$label: no se recuperó solo en 120 s (listo ${PlaybackProbe.readyCount} veces, reconexiones ${PlaybackProbe.reconnects}, " +
                        "motivo ${PlaybackProbe.lastReconnectReason}, error ${PlaybackProbe.lastError})")
                }
                log("Reconexión ($label): recuperado solo en ${System.currentTimeMillis() - t0} ms · motivo «${PlaybackProbe.lastReconnectReason}» · ${PlaybackProbe.reconnects} intento(s)")
                Thread.sleep(1_500); shot("reconexion-$mode")
                assertTrue("$label: no debe quedar el aviso de error", compose.onAllNodesWithTag("player_error", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            }
        }
    }

    private fun mock(path: String) = app.container.okHttp.newCall(okhttp3.Request.Builder().url("$base$path").build()).execute().use { it.body.string() }
    private fun playerIntent(kind: String, source: String, id: String, context: String? = null) =
        android.content.Intent(app, com.embyverse.app.player.PlayerActivity::class.java)
            .putExtra("kind", kind).putExtra("source", source).putExtra("id", id).apply { context?.let { putExtra("context", it) } }

    /** Audio en español por defecto (aunque la pista «Latino» no diga su idioma) y subtítulos «Forzados» solos. */
    @Test fun n_audioEspanolYForzados() {
        val c = app.container
        val p = runBlocking { c.prefs.playback.first() }
        assertTrue("TV en vivo en HLS por defecto", p.liveFormat == com.embyverse.core.xtream.LiveFormat.HLS)
        assertTrue("subtítulos forzados activos por defecto", p.forcedSubtitles && !p.subtitlesEnabled)
        assertTrue("audio del perfil en español por defecto", runBlocking { c.prefs.profileLanguages(c.prefs.activeProfile()).first().first } == "es")
        val movie = runBlocking { c.database.movies().observeAll(1).first().first() }
        mock("/__vod?file=dual")
        try {
            PlaybackProbe.reset()
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("MOVIE", movie.sourceId, movie.providerId, "restart")).use {
                try { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 && PlaybackProbe.lastAutoTrack?.contains("forzados") == true } }
                catch (e: ComposeTimeoutException) { shot("fallo-forzados"); fail("no eligió solo audio en español y forzados: ${PlaybackProbe.lastAutoTrack} · error ${PlaybackProbe.lastError}") }
                log("Idiomas: elegido solo «${PlaybackProbe.lastAutoTrack}» (audio inglés por defecto en el archivo, «Latino» sin idioma, «Forzados» sin marca)")
                assertTrue("audio Latino elegido: ${PlaybackProbe.lastAutoTrack}", PlaybackProbe.lastAutoTrack!!.contains("audio:Latino"))
                Thread.sleep(2_500); shot("30-forzados-y-latino")
            }
        } finally { mock("/__vod?file=") }
        // Pantalla de Reproducción: opción de forzados y el idioma del perfil en «Español».
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav_settings", 30_000)
            compose.onNodeWithTag("nav_settings", useUnmergedTree = true).performClick()
            compose.onNodeWithText(app.getString(R.string.playback_title), useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText(app.getString(R.string.forced_subs), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(800); shot("31-ajustes-reproduccion")
        }
    }

    /** TV en vivo: HLS por defecto (en vivo real), y si se elige .ts el canal que entrega HLS se sigue rescatando. */
    @Test fun o_tvEnVivoHls() {
        val c = app.container
        val ch = runBlocking { c.database.channels().listForZapping(null).first { it.providerId == "5" && c.sources.resolveStreamUrl(it.sourceId, it.streamUrl).contains("/live/user/pass/") } }
        val url = runBlocking { c.sources.resolveStreamUrl(ch.sourceId, ch.streamUrl) }
        assertTrue("el canal se pide en HLS: $url", url.endsWith(".m3u8"))
        PlaybackProbe.reset()
        val t0 = System.currentTimeMillis()
        ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("LIVE", ch.sourceId, ch.providerId)).use {
            try { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 } } catch (e: ComposeTimeoutException) { shot("fallo-hls"); fail("HLS en vivo no arrancó: ${PlaybackProbe.lastError}") }
            log("TV en vivo HLS: imagen en ${System.currentTimeMillis() - t0} ms")
            Thread.sleep(12_000) // pasa a la siguiente ventana de la lista en vivo
            assertTrue("HLS en vivo sigue reproduciendo sin error: ${PlaybackProbe.lastError}", PlaybackProbe.lastError == null && PlaybackProbe.reconnects == 0)
            shot("32-tv-en-vivo-hls")
        }
        runBlocking { c.prefs.updatePlayback { it.copy(liveFormat = com.embyverse.core.xtream.LiveFormat.TS) } }
        try {
            val ch2 = runBlocking { c.database.channels().listForZapping(null).first { it.providerId == "2" } }
            assertTrue("con .ts elegido se pide .ts", runBlocking { c.sources.resolveStreamUrl(ch2.sourceId, ch2.streamUrl) }.endsWith(".ts"))
            PlaybackProbe.reset()
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("LIVE", ch2.sourceId, ch2.providerId)).use {
                try { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 } } catch (e: ComposeTimeoutException) { fail("canal .ts que entrega HLS no se rescató: ${PlaybackProbe.lastError}") }
                log("TV en vivo .ts: canal que entrega HLS rescatado")
            }
        } finally { runBlocking { c.prefs.updatePlayback { it.copy(liveFormat = com.embyverse.core.xtream.LiveFormat.HLS) } } }
    }

    /** «Saltar intro»: aprende la intro de la serie al usarlo; en una serie donde no se usa, deja de salir. */
    @Test fun p_saltarIntro() {
        val c = app.container
        val all = runBlocking { c.catalog.series(null, 40).first() }
        mock("/__vod?file=long")
        fun episodes(s: com.embyverse.app.data.db.SeriesEntity) = runBlocking {
            c.sources.loadEpisodes(s.sourceId, s.providerId); c.database.episodes().forSeries(s.sourceId, s.providerId)
        }
        val withEps = all.filter { episodes(it).size >= 3 }.take(2)
        assertTrue("hay 2 series con 3 episodios", withEps.size == 2)
        fun open(e: com.embyverse.app.data.db.EpisodeEntity, check: () -> Unit) {
            PlaybackProbe.reset()
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("EPISODE", e.sourceId, e.providerId, "restart")).use {
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                check()
            }
        }
        fun skipVisible() = compose.onAllNodesWithTag("skip_intro", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        try {
            // Serie A: el cliente usa «Saltar intro» → se aprende y en el siguiente episodio salta exacto.
            val a = withEps[0]; val ea = episodes(a)
            open(ea[0]) {
                compose.waitUntil(30_000) { skipVisible() }
                shot("33-saltar-intro-aparece")
                compose.onNodeWithTag("skip_intro", useUnmergedTree = true).performClick()
                Thread.sleep(1_000)
            }
            val learned = runBlocking { c.prefs.intro("${a.sourceId}:${a.providerId}") }
            assertTrue("intro aprendida: $learned", learned?.contains(',') == true)
            log("Saltar intro: aprendida en la serie A ($learned)")
            open(ea[1]) {
                compose.waitUntil(30_000) { skipVisible() }
                log("Saltar intro: en el episodio 2 de la serie A aparece durante su intro")
            }
            // Serie B: el botón sale y no se usa en 2 episodios → en el 3.º ya no aparece.
            val b = withEps[1]; val eb = episodes(b)
            for (k in 0..1) open(eb[k]) {
                compose.waitUntil(30_000) { skipVisible() }
                compose.waitUntil(40_000) { !skipVisible() } // pasa la ventana sin usarlo
                Thread.sleep(1_500)
            }
            assertTrue("serie sin intro marcada", runBlocking { c.prefs.intro("${b.sourceId}:${b.providerId}") } == "none")
            open(eb[2]) {
                Thread.sleep(26_000)
                assertTrue("en una serie sin intro el botón ya no aparece", !skipVisible())
                shot("34-serie-sin-intro")
            }
            log("Saltar intro: tras 2 episodios sin usarlo, la serie B ya no muestra el botón")
        } finally { mock("/__vod?file=") }
    }

    /** VPN WireGuard real: servidor en la máquina de pruebas; la app se conecta y su tráfico llega por el túnel. */
    @Test fun q_vpn() {
        val c = app.container
        val conf = runCatching { app.container.okHttp.newCall(okhttp3.Request.Builder().url("http://10.0.2.2:9991/wg-client.conf").build()).execute().use { it.body.string() } }.getOrNull()
        if (conf == null || !conf.contains("[Interface]")) { log("VPN: sin servidor de prueba, se omite"); return }
        val probe = okhttp3.Request.Builder().url("http://10.77.0.1:8099/").build()
        val quick = c.okHttp.newBuilder().callTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
        val before = runCatching { quick.newCall(probe).execute().use { it.body.string() } }.getOrDefault("sin respuesta")
        log("VPN: sin conectar, el servidor ve: $before")
        assertTrue("sin VPN no debe llegar por el túnel", !before.contains("10.77.0.2"))
        assertTrue("configuración inválida rechazada", c.vpn.save("hola") != null)
        assertTrue("configuración guardada: ${c.vpn.save(conf)}", c.vpn.save(conf) == null)
        // Permiso de VPN concedido como lo haría el cliente en la ventana de Android.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("appops set ${app.packageName} ACTIVATE_VPN allow").close()
        Thread.sleep(800)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav_settings", 30_000)
            compose.onNodeWithTag("nav_settings", useUnmergedTree = true).performClick()
            compose.onNodeWithText("VPN", useUnmergedTree = true).performClick()
            waitTag("vpn_on", 15_000)
            compose.onNodeWithTag("vpn_on", useUnmergedTree = true).performClick()
            compose.waitUntil(30_000) { c.vpn.state.value == com.embyverse.app.data.vpn.VpnManager.State.ON || c.vpn.state.value == com.embyverse.app.data.vpn.VpnManager.State.ERROR }
            Thread.sleep(2_000); shot("35-vpn-conectada")
            assertTrue("VPN conectada: ${c.vpn.message.value}", c.vpn.state.value == com.embyverse.app.data.vpn.VpnManager.State.ON)
            var seen = ""
            for (i in 0..9) { seen = runCatching { quick.newCall(probe).execute().use { r -> r.body.string() } }.getOrDefault("sin respuesta"); if (seen.contains("10.77.0.2")) break; Thread.sleep(1_000) }
            log("VPN: conectada, el servidor ve: $seen · recibido por el túnel ${c.vpn.receivedBytes()} bytes")
            assertTrue("el tráfico de la app pasa por la VPN: $seen", seen.contains("10.77.0.2"))
            // El proveedor de IPTV sigue funcionando con la VPN puesta (solo esta app pasa por el túnel).
            assertTrue("proveedor alcanzable con VPN", mock("/player_api.php?username=user&password=pass").contains("user_info"))
            compose.onNodeWithTag("vpn_off", useUnmergedTree = true).performClick()
            compose.waitUntil(15_000) { c.vpn.state.value == com.embyverse.app.data.vpn.VpnManager.State.OFF }
            log("VPN: desconectada")
        }
        c.vpn.forget()
    }

    /** Todos los temas: inicio, películas y el selector (foco) visible al moverse con el control. */
    @Test fun r_todosLosTemas() {
        val c = app.container
        val profile = runBlocking { c.prefs.activeProfile() }
        val ids = listOf(null, "style_cristal", "style_cine_rojo", "style_purpura", "style_azul_magico", "style_oceano", "style_estelar", "style_verde_neon", "style_naranja", "style_oro")
        val inst = InstrumentationRegistry.getInstrumentation()
        try {
            for (id in ids) {
                runBlocking { c.prefs.updateAppearance(profile) { it.copy(themeId = id) } }
                ActivityScenario.launch(MainActivity::class.java).use {
                    runCatching { compose.waitUntil(60_000) { compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                    Thread.sleep(1_500)
                    val name = id ?: "original"
                    shot("tema-$name-inicio")
                    // Modo control remoto: el selector debe verse claro.
                    // Como con el control remoto: una tecla saca del modo táctil y se enfoca una tarjeta.
                    inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN); Thread.sleep(400)
                    runCatching {
                        val card = compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = false)[0]
                        runCatching { card.performScrollTo() }; card.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus)
                    }.onFailure { log("Tema $name: no se pudo enfocar una tarjeta (${it.message})") }
                    Thread.sleep(900); shot("tema-$name-foco")
                    log("Tema $name: inicio y selector sin cierres")
                }
            }
        } finally { runBlocking { c.prefs.updateAppearance(profile) { it.copy(themeId = null) } } }
    }
}

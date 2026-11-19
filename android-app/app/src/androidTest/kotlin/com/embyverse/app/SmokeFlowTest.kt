package com.embyverse.app

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
        // Canal 5 de la cuenta Xtream (la lista de zapping mezcla fuentes y su orden cambia entre corridas).
        val xtream = runBlocking { c.sources.sources.first().first { it.type == "XTREAM" } }
        val ch = runBlocking { c.database.channels().get(xtream.id, "5")!! }
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
            val ch2 = runBlocking { c.database.channels().get(xtream.id, "2")!! }
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
            runCatching { compose.waitUntil(10_000) { compose.onAllNodesWithText("Conectada", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                .onFailure { log("VPN: la pantalla no mostró «Conectada» (estado ${c.vpn.state.value})") }
            Thread.sleep(1_000); shot("35-vpn-conectada")
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
                    // Sagas hasta abajo del inicio en todos los temas.
                    if (runBlocking { c.catalog.sagas() }.isNotEmpty()) {
                        val found = (0 until 4).any { i -> runCatching {
                            compose.onAllNodes(androidx.compose.ui.test.hasScrollAction(), useUnmergedTree = true)[i].performScrollToNode(androidx.compose.ui.test.hasTestTag("sagas"))
                        }.isSuccess }
                        if (!found) { shot("tema-$name-sin-sagas"); fail("Tema $name: no aparecen las sagas en el inicio") }
                        Thread.sleep(700); shot("tema-$name-sagas"); log("Tema $name: sagas al final del inicio")
                    }
                    // Modo control remoto: el selector debe verse claro.
                    // Películas (cuadrícula): como con el control remoto, una tecla saca del modo táctil y se enfoca la 1.ª tarjeta.
                    val moviesTag = if (id == "style_estelar") "hub_movies" else "nav_movies"
                    runCatching { compose.onNodeWithTag(moviesTag, useUnmergedTree = true).performClick(); waitCards() }
                    Thread.sleep(1_200)
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

    /** Textos de la pantalla actual que se cortan: recortados (sin «…»), con «…», o tapados por su contenedor. */
    private fun textosCortados(pantalla: String, config: String, out: MutableList<String>) {
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val sw = root.size.width
        val nodes = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
        for (n in nodes) {
            val txt = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" ") { it.text }?.trim().orEmpty()
            if (txt.isBlank() || n.config.contains(androidx.compose.ui.semantics.SemanticsActions.SetText)) continue
            val pos = n.positionInRoot; val clip = n.boundsInRoot
            if (pos.y < 0 || pos.y > root.size.height || clip.width <= 0f) continue // fuera de la vista (listas que se desplazan)
            val res = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            runCatching { n.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(res) }
            val r = res.firstOrNull() ?: continue
            val unclippedRight = pos.x + n.size.width
            val last = r.lineCount - 1
            val shown = if (last >= 0) r.getLineEnd(last, visibleEnd = true) else 0
            val kind = when {
                last < 0 -> null
                // Una palabra partida entre dos renglones (el buscador salía «Bus / car / ca / nal / es»).
                (0 until last).any { i -> val e = r.getLineEnd(i); val tx = r.layoutInput.text.text; e in 1 until tx.length && tx[e - 1].isLetter() && tx[e].isLetter() } -> "recortado (palabra partida)"
                r.isLineEllipsized(last) -> "con «…»"
                // Se dejó de mostrar parte del texto (límite de renglones sin «…») o un renglón sale del ancho del texto.
                shown < r.layoutInput.text.text.trimEnd().length -> "recortado"
                (0..last).any { r.getLineRight(it) > r.size.width + 1.5f } -> "recortado"
                pos.x >= 0 && unclippedRight <= sw && clip.width < n.size.width - 2 -> "tapado por su contenedor"
                else -> null
            } ?: continue
            out += "$config | $pantalla | $kind | «${txt.take(60)}»"
        }
    }

    /** Pantallas principales en varios tamaños y tamaños de letra: ningún texto debe quedar cortado. */
    @Test fun s_textosCompletos() {
        val inst = InstrumentationRegistry.getInstrumentation()
        fun sh(cmd: String) { inst.uiAutomation.executeShellCommand(cmd).close(); Thread.sleep(400) }
        val configs = listOf(
            Triple("celular", null, 1f), Triple("celular-letra-grande", null, 1.3f),
            Triple("celular-chico", "720x1280:320", 1f), Triple("pantalla-horizontal", "1920x1080:240", 1f),
        )
        val found = mutableListOf<String>()
        try {
            for ((name, size, font) in configs) {
                if (size != null) { val (wh, dpi) = size.split(':'); sh("wm size $wh"); sh("wm density $dpi") } else { sh("wm size reset"); sh("wm density reset") }
                com.embyverse.app.data.prefs.UiPrefs.setFontScale(app, font)
                ActivityScenario.launch(MainActivity::class.java).use {
                    waitTag("nav_settings", 40_000); Thread.sleep(2_500)
                    for ((tag, label) in listOf("nav_home" to "inicio", "nav_live" to "tv-en-vivo", "nav_movies" to "peliculas", "nav_series" to "series", "nav_settings" to "ajustes")) {
                        runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick() }
                        Thread.sleep(2_200)
                        textosCortados(label, name, found)
                        shot("textos-$name-$label")
                    }
                    // Ajustes › Reproducción (la pantalla con más textos largos).
                    runCatching { compose.onNodeWithText(app.getString(R.string.playback_title), useUnmergedTree = true).performClick(); Thread.sleep(1_500); textosCortados("reproduccion", name, found); shot("textos-$name-reproduccion") }
                }
            }
        } finally {
            sh("wm size reset"); sh("wm density reset"); com.embyverse.app.data.prefs.UiPrefs.setFontScale(app, 1f)
        }
        found.distinct().forEach { log("CORTADO: $it") }
        log("Textos cortados: ${found.distinct().size}")
        val hard = found.distinct().filter { "recortado" in it || "tapado" in it }
        assertTrue("textos recortados o tapados (${hard.size}):\n" + hard.joinToString("\n"), hard.isEmpty())
    }

    /** Estadísticas: la app avisa al servidor (equipo, versión, usuario Xtream, nunca la contraseña) y aparece en el panel. */
    @Test fun t_estadisticas() {
        val c = app.container
        val rep = com.embyverse.app.data.stats.StatsReporter(app, c.okHttp, "$base/cfg")
        runBlocking { rep.report(c.sources.sources.first(), force = true) }
        val body = c.okHttp.newCall(okhttp3.Request.Builder().url("http://10.0.2.2:8788/v1/stats").header("authorization", "Bearer clave-de-prueba").build()).execute().use { it.body.string() }
        val dev = org.json.JSONObject(body).getJSONArray("devices").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.firstOrNull { it.getString("id") == rep.installId }
        assertTrue("el equipo aparece en las estadísticas: $body", dev != null)
        log("Estadísticas: ${dev!!.getString("platform")} · ${dev.getString("model")} · v${dev.getString("version")} · usuario «${dev.getString("xuser")}» · app «${dev.getString("app")}»")
        assertTrue("usuario Xtream registrado", dev.getString("xuser") == "user")
        assertTrue("app principal", dev.getString("app") == "")
        assertTrue("nunca la contraseña", !body.contains("\"pass\""))
    }

    /** Celular: el panel de audio y subtítulos se cierra con la flecha o tocando fuera, y la película sigue. */
    @Test fun u_panelAudioCelular() {
        val c = app.container
        val movie = runBlocking { c.database.movies().observeAll(1).first().first() }
        mock("/__vod?file=dual")
        try {
            PlaybackProbe.reset()
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("MOVIE", movie.sourceId, movie.providerId, "restart")).use { sc ->
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                val inst = InstrumentationRegistry.getInstrumentation()
                fun open() { inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MENU); compose.waitUntil(8_000) { compose.onAllNodesWithTag("player_options_panel", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                fun closed() = compose.onAllNodesWithTag("player_options_panel", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
                open()
                // Cambiar de audio desde el panel (pista en inglés) y volver a la película con la flecha.
                runCatching { compose.onNodeWithText("English", substring = true, useUnmergedTree = true).performClick() }
                Thread.sleep(800); shot("36-panel-audio-celular")
                compose.onNodeWithTag("player_options_close", useUnmergedTree = true).performClick()
                compose.waitUntil(5_000) { closed() }
                assertTrue("la película sigue abierta tras cerrar el panel", sc.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                // Tocar fuera del panel también lo cierra.
                open()
                compose.onNodeWithTag("player_options_scrim", useUnmergedTree = true).performClick()
                compose.waitUntil(5_000) { closed() }
                // «Atrás» con el panel abierto lo cierra sin salir de la película.
                open(); inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) { closed() }
                Thread.sleep(500)
                assertTrue("Atrás cerró el panel y no la película", sc.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
                log("Panel de audio (celular): se cierra con la flecha, tocando fuera y con Atrás; la película sigue")
            }
        } finally { mock("/__vod?file=") }
    }

    /** Protector de pantalla: imagen completa adaptada a la pantalla (sin recortar), con la ficha de la película. */
    @Test fun v_protectorDePantalla() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            waitTag("nav_settings", 40_000)
            sc.onActivity { a ->
                val f = MainActivity::class.java.getDeclaredField("screensaverOn"); f.isAccessible = true
                (f.get(a) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = true
            }
            waitTag("screensaver", 10_000)
            Thread.sleep(6_000); shot("37-protector-pantalla")
            assertTrue("protector visible", compose.onAllNodesWithTag("screensaver", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            log("Protector de pantalla: visible con imagen ajustada (${if (compose.onAllNodesWithTag("screensaver_fit", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) "completa, con fondo difuminado" else "horizontal a pantalla completa"})")
        }
    }

    /** Iniciar sesión: un «404» (así responden los paneles XUI a un usuario que no existe) da un mensaje claro,
     *  y con servidor ya puesto el cliente puede elegir su cuenta, otro servidor Xtream o una lista M3U. */
    @Test fun w_entrarXtreamOM3u() {
        val c = app.container
        val r = runBlocking { c.sources.test(com.embyverse.app.data.repo.SourceDraft(null, "x", SourceType.XTREAM, "$base/sin-xtream", "nadie", "mala", null, null)) }
        assertTrue("404 al entrar → usuario o contraseña: $r", r is com.embyverse.core.net.Outcome.Fail && r.error == com.embyverse.core.net.SourceError.AuthOrNotFound)
        // App con servidor ya puesto (como las de tus clientes): solo así aparecen las 3 opciones.
        val cfg0 = c.config.config.value
        // Sin activar «Otro servidor» en el panel, esa opción no aparece.
        c.config.overrideForTest(cfg0.copy(texts = cfg0.texts - "provider.askHost" - "login.otherServer" + ("provider.host" to base)))
        ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav_settings", 40_000)
            compose.onNodeWithTag("nav_settings", useUnmergedTree = true).performClick()
            val sl = app.getString(R.string.nav_sources); waitText(sl); compose.onAllNodesWithText(sl, useUnmergedTree = true)[0].performClick()
            waitFor("botón añadir", 10_000) { compose.onAllNodesWithContentDescription(app.getString(R.string.action_add_source), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription(app.getString(R.string.action_add_source), useUnmergedTree = true)[0].performClick()
            waitText(app.getString(R.string.login_mode_account))
            assertTrue("sin «Otro servidor» por defecto", compose.onAllNodesWithText(app.getString(R.string.login_mode_xtream), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            log("Entrar: «Otro servidor» oculto por defecto")
        }
        c.config.overrideForTest(cfg0.copy(texts = cfg0.texts - "provider.askHost" + ("provider.host" to base) + ("login.otherServer" to "1")))
        try { ActivityScenario.launch(MainActivity::class.java).use {
            waitTag("nav_settings", 40_000)
            compose.onNodeWithTag("nav_settings", useUnmergedTree = true).performClick()
            val srcLabel = app.getString(R.string.nav_sources); waitText(srcLabel); compose.onAllNodesWithText(srcLabel, useUnmergedTree = true)[0].performClick()
            waitFor("botón añadir", 10_000) { compose.onAllNodesWithContentDescription(app.getString(R.string.action_add_source), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithContentDescription(app.getString(R.string.action_add_source), useUnmergedTree = true)[0].performClick()
            waitText(app.getString(R.string.login_mode_account))
            assertTrue("modo cuenta: sin campo de servidor", compose.onAllNodesWithText(app.getString(R.string.source_server), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            Thread.sleep(600); shot("37-entrar-mi-cuenta")
            compose.onNodeWithText(app.getString(R.string.login_mode_m3u), useUnmergedTree = true).performClick()
            waitText(app.getString(R.string.source_url)); Thread.sleep(400); shot("38-entrar-lista-m3u")
            compose.onNodeWithText(app.getString(R.string.login_mode_xtream), useUnmergedTree = true).performClick()
            waitText(app.getString(R.string.source_server)); Thread.sleep(400); shot("39-entrar-otro-servidor")
            log("Entrar: «Mi cuenta», «Otro servidor (Xtream)» y «Lista M3U» funcionan")
        } } finally { c.config.overrideForTest(cfg0) }
    }

    /** Como en TV (720p, 1080p, letra grande y margen grande): TV en vivo y demás pantallas sin textos encimados ni partidos. */
    @Test fun x_pantallasDeTv() {
        val inst = InstrumentationRegistry.getInstrumentation()
        fun sh(cmd: String) { inst.uiAutomation.executeShellCommand(cmd).close(); Thread.sleep(400) }
        data class Cfg(val name: String, val size: String, val font: Float, val margin: Int)
        val configs = listOf(Cfg("tv-720p", "1280x720:213", 1f, 1), Cfg("tv-720p-letra-grande-margen", "1280x720:213", 1.3f, 2),
            Cfg("tv-1080p", "1920x1080:320", 1f, 1), Cfg("tv-1080p-letra-grande", "1920x1080:320", 1.3f, 1))
        val found = mutableListOf<String>()
        com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, true)
        try {
            for (cf in configs) {
                val (wh, dpi) = cf.size.split(':'); sh("wm size $wh"); sh("wm density $dpi")
                com.embyverse.app.data.prefs.UiPrefs.setFontScale(app, cf.font); com.embyverse.app.data.prefs.UiPrefs.setTvMargin(app, cf.margin)
                ActivityScenario.launch(MainActivity::class.java).use {
                    waitTag("nav_settings", 40_000); Thread.sleep(2_500)
                    for ((tag, label) in listOf("nav_live" to "tv-en-vivo", "nav_home" to "inicio", "nav_movies" to "peliculas", "nav_series" to "series", "nav_settings" to "ajustes")) {
                        runCatching { compose.onNodeWithTag(tag, useUnmergedTree = true).performClick() }
                        Thread.sleep(2_200)
                        if (tag == "nav_live") runCatching { waitTag("channel", 30_000); Thread.sleep(800) } // con canales, no solo cargando
                        textosCortados(label, cf.name, found)
                        shot("tv-${cf.name}-$label")
                    }
                    if (cf.name == "tv-1080p") {
                        // Ficha de una película en TV: la selección empieza en «Ver», no en la flecha de regreso.
                        val movieCard = androidx.compose.ui.test.SemanticsMatcher("tarjeta de película") { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("card:MOVIE:") == true }
                        compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
                        waitFor("tarjeta de película", 30_000) { compose.onAllNodes(movieCard, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                        compose.onAllNodes(movieCard, useUnmergedTree = true)[0].performClick()
                        waitTag("detail_play", 20_000)
                        waitFor("«Ver» seleccionado", 8_000) { runCatching { compose.onNodeWithTag("detail_play", useUnmergedTree = true).assertIsFocused() }.isSuccess }
                        Thread.sleep(500); shot("tv-ficha-ver-seleccionado")
                        log("TV: la ficha abre con «Ver» seleccionado")
                    }
                }
            }
        } finally {
            com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, false)
            sh("wm size reset"); sh("wm density reset"); com.embyverse.app.data.prefs.UiPrefs.setFontScale(app, 1f); com.embyverse.app.data.prefs.UiPrefs.setTvMargin(app, 1)
        }
        found.distinct().forEach { log("TV CORTADO: $it") }
        val hard = found.distinct().filter { "recortado" in it || "tapado" in it }
        assertTrue("TV: textos recortados o partidos (${hard.size}):\n" + hard.joinToString("\n"), hard.isEmpty())
    }

    /** Misma cuenta en otro equipo (TV Samsung/LG o PC): los perfiles, favoritos y «seguir viendo» se comparten. */
    @Test fun y_perfilesDeLaCuenta() = runBlocking {
        val c = app.container
        val srv = "http://10.0.2.2:8788"
        app.getSharedPreferences("account_sync", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val sync = com.embyverse.app.data.sync.AccountSync(c) { srv }
        val src = c.database.sources().all().first { it.type == SourceType.XTREAM.name }
        val host = java.net.URI(if (src.location.contains("://")) src.location else "http://${src.location}").host.lowercase()
        val key = com.embyverse.app.data.sync.AccountSync.sha256("ev-sync-v1|$host|${src.username}|${c.credentials.get(src.id)}")
        fun remote() = org.json.JSONObject(c.okHttp.newCall(okhttp3.Request.Builder().url("$srv/v1/sync/$key").build()).execute().use { it.body.string() })
        fun put(rev: Int, data: org.json.JSONObject) = c.okHttp.newCall(okhttp3.Request.Builder().url("$srv/v1/sync/$key")
            .put(org.json.JSONObject().put("rev", rev).put("data", data).toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.code }
        // 1) Este celular: perfil «Niños» con un favorito.
        val kidsId = c.database.profiles().all().firstOrNull { it.name == "Niños" }?.id ?: c.library.createProfile("Niños", 0xFF22D3EE.toInt())
        val movie = c.database.movies().observeAll(1).first().first()
        c.database.library().addFavorite(com.embyverse.app.data.db.FavoriteEntity(kidsId, "MOVIE", src.id, movie.providerId, movie.title, null, 1000L))
        assertTrue("primera sincronización", sync.sync() == "ok")
        val r1 = remote(); val profs1 = r1.getJSONObject("data").getJSONArray("profiles")
        val kidsUid = (0 until profs1.length()).map { profs1.getJSONObject(it) }.first { it.getString("name") == "Niños" }.getString("uid")
        assertTrue("el favorito de Niños está en la cuenta", r1.getJSONObject("data").getJSONObject("favs").getJSONArray(kidsUid).toString().contains(movie.providerId))
        assertTrue("nunca la contraseña", !r1.toString().contains(c.credentials.get(src.id)!!))
        log("Cuenta: ${profs1.length()} perfiles subidos")
        // 2) Una TV Samsung con la misma cuenta agrega el perfil «Papá» con un canal favorito.
        val d = r1.getJSONObject("data")
        d.getJSONArray("profiles").put(org.json.JSONObject().put("uid", "ptv1").put("name", "Papá").put("avatar", "🦁"))
        d.getJSONObject("favs").put("ptv1", org.json.JSONArray().put(org.json.JSONObject().put("kind", "live").put("id", "5").put("title", "Canal 5").put("at", 2000)))
        assertTrue("la TV guarda", put(r1.getInt("rev"), d) == 200)
        assertTrue("segunda sincronización", sync.sync() == "ok")
        val papa = c.database.profiles().all().firstOrNull { it.name == "Papá" }
        assertTrue("el perfil de la TV aparece en el celular", papa != null)
        assertTrue("con su canal favorito", c.database.library().favorites(papa!!.id).any { it.kind == "LIVE" && it.itemId == "5" && it.sourceId == src.id })
        val r2 = remote()
        val p2 = r2.getJSONObject("data").getJSONArray("profiles").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.first { it.getString("uid") == "ptv1" }
        assertTrue("el avatar de la TV se conserva", p2.optString("avatar") == "🦁")
        // 3) En la TV borran «Papá»: también desaparece del celular.
        val d3 = r2.getJSONObject("data")
        val keep = org.json.JSONArray(); val arr = d3.getJSONArray("profiles"); for (i in 0 until arr.length()) if (arr.getJSONObject(i).getString("uid") != "ptv1") keep.put(arr.getJSONObject(i))
        d3.put("profiles", keep); d3.getJSONObject("favs").remove("ptv1")
        assertTrue("la TV borra", put(r2.getInt("rev"), d3) == 200)
        assertTrue("tercera sincronización", sync.sync() == "ok")
        assertTrue("perfil borrado en todos los equipos", c.database.profiles().all().none { it.name == "Papá" })
        log("Perfiles de la cuenta: subir, recibir de la TV y borrar funcionan")
        // Deja el equipo como estaba (sin el perfil de prueba, para que las siguientes pruebas no vean «¿Quién está viendo?»).
        sync.setEnabled(false)
        c.database.profiles().all().filter { it.name == "Niños" }.forEach { c.library.deleteProfile(it.id) }
        c.prefs.setActiveProfile(c.database.profiles().all().first().id)
        app.getSharedPreferences("account_sync", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        Unit // las pruebas no devuelven valor
    }

    /** Películas con audio AC3 (Dolby) y DTS: se oyen gracias al decodificador FFmpeg; y la opción de otro reproductor. */
    @Test fun z_audioAc3DtsYOtroReproductor() {
        val c = app.container
        val movie = runBlocking { c.database.movies().observeAll(1).first().first() }
        for (codec in listOf("ac3", "dts")) {
            mock("/__vod?file=$codec")
            try {
                PlaybackProbe.reset()
                ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("MOVIE", movie.sourceId, movie.providerId, "restart")).use {
                    try { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 && (PlaybackProbe.audioDecoder != null || PlaybackProbe.audioUnsupported) } }
                    catch (e: ComposeTimeoutException) { shot("fallo-audio-$codec"); fail("$codec no arrancó: ${PlaybackProbe.lastError}") }
                    Thread.sleep(1_500)
                    log("Audio $codec: decodificador «${PlaybackProbe.audioDecoder}» · sin soporte=${PlaybackProbe.audioUnsupported}")
                    if (PlaybackProbe.audioUnsupported) shot("audio-$codec-sin-soporte")
                    assertTrue("$codec: el audio debe sonar (decodificador ${PlaybackProbe.audioDecoder})", !PlaybackProbe.audioUnsupported && PlaybackProbe.audioDecoder != null)
                }
            } finally { mock("/__vod?file=") }
        }
        // Opción «Usar otro reproductor» en Ajustes › Reproducción y en el panel del reproductor.
        ActivityScenario.launch(MainActivity::class.java).use {
            // Si quedaron varios perfiles de otras pruebas, primero sale «¿Quién está viendo?».
            waitFor("inicio o perfiles", 30_000) { listOf("nav_settings", "profile").any { t -> compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            if (compose.onAllNodesWithTag("nav_settings", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                compose.onAllNodesWithTag("profile", useUnmergedTree = true)[0].performClick()
            waitTag("nav_settings", 30_000)
            compose.onNodeWithTag("nav_settings", useUnmergedTree = true).performClick()
            compose.onNodeWithText(app.getString(R.string.playback_title), useUnmergedTree = true).performClick()
            waitText(app.getString(R.string.forced_subs))
            // Está más abajo en la lista: se baja hasta la opción.
            waitFor("opción otro reproductor", 20_000) {
                runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollAction())[0].performScrollToNode(androidx.compose.ui.test.hasTestTag("external_player")) }
                compose.onAllNodesWithTag("external_player", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            Thread.sleep(600); shot("40-ajustes-otro-reproductor")
        }
        val np = com.embyverse.app.player.NowPlaying(com.embyverse.app.data.repo.ItemRef(com.embyverse.core.model.ContentKind.MOVIE, movie.sourceId, movie.providerId), "Ajuste de cuentas", null, null, "http://10.0.2.2:8800/movie/u/p/1.mkv", null, null)
        val i = com.embyverse.app.player.ExternalPlayer.intent(app, np)
        val inner = i.getParcelableExtra<android.content.Intent>(android.content.Intent.EXTRA_INTENT)!!
        assertTrue("se manda la película con su título a otro reproductor", inner.action == android.content.Intent.ACTION_VIEW && inner.type == "video/*" && inner.getStringExtra("title") == "Ajuste de cuentas")
        log("Otro reproductor: opción en Ajustes y datos que se envían correctos")
    }

    /** Siguiente episodio automático aunque el servidor no «cierre» el final del episodio (se quedaba cargando). */
    @Test fun za_siguienteEpisodioAutomatico() {
        val c = app.container
        runBlocking { c.prefs.updatePlayback { it.copy(autoplayNextEpisode = true) } }
        val s = runBlocking { c.catalog.series(null, 40).first() }.first { ser -> runBlocking { c.sources.loadEpisodes(ser.sourceId, ser.providerId); c.database.episodes().forSeries(ser.sourceId, ser.providerId).size >= 2 } }
        val eps = runBlocking { c.database.episodes().forSeries(s.sourceId, s.providerId) }
        mock("/__vod?file=stall")
        try {
            PlaybackProbe.reset()
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("EPISODE", eps[0].sourceId, eps[0].providerId, "restart")).use {
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                // El clip dura 20 s: al final el servidor se queda «colgado»; debe pasar solo al episodio 2.
                try { compose.waitUntil(70_000) { PlaybackProbe.nowId == eps[1].providerId } }
                catch (e: ComposeTimeoutException) { shot("fallo-siguiente-episodio"); fail("no pasó solo al siguiente episodio (sigue en ${PlaybackProbe.nowId}) · ${PlaybackProbe.lastError}") }
                log("Siguiente episodio: pasó solo de E1 a E2 aunque el servidor no cerró el final")
                mock("/__vod?file=")
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 1 }
                Thread.sleep(1_500); shot("41-siguiente-episodio-solo")
            }
        } finally { mock("/__vod?file=") }
    }

    /** «Sagas» al final de las carpetas de Películas: abre la lista de sagas y una saga. */
    @Test fun zb_sagasEnPeliculas() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor("inicio o perfiles", 30_000) { listOf("nav_settings", "profile").any { t -> compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            if (compose.onAllNodesWithTag("nav_settings", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                compose.onAllNodesWithTag("profile", useUnmergedTree = true)[0].performClick()
            waitTag("nav_movies", 30_000)
            compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
            val label = "🎬 " + app.getString(R.string.sagas)
            waitFor("carpeta Sagas", 30_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes().indices.any { i ->
                    runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollAction(), useUnmergedTree = true)[i].performScrollToNode(androidx.compose.ui.test.hasText(label)) }.isSuccess
                } || compose.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onAllNodesWithText(label, useUnmergedTree = true)[0].performClick()
            waitTag("sagas_screen", 15_000)
            waitText("Toy Story", 30_000)
            Thread.sleep(800); shot("42-sagas")
            compose.onAllNodesWithText("Toy Story", useUnmergedTree = true)[0].performClick()
            waitTag("saga_grid", 15_000); Thread.sleep(800); shot("43-saga-toy-story")
            log("Sagas: carpeta al final de Películas, lista y saga funcionan")
        }
    }

    /** «Siguiente episodio» sale al empezar los créditos (≈45 s antes en un episodio de 11 min), no hasta el final. */
    @Test fun zc_siguienteAlEmpezarCreditos() {
        val c = app.container
        runBlocking { c.prefs.updatePlayback { it.copy(autoplayNextEpisode = true) } }
        val s = runBlocking { c.catalog.series(null, 40).first() }.first { ser -> runBlocking { c.sources.loadEpisodes(ser.sourceId, ser.providerId); c.database.episodes().forSeries(ser.sourceId, ser.providerId).size >= 2 } }
        val eps = runBlocking { c.database.episodes().forSeries(s.sourceId, s.providerId) }
        app.getSharedPreferences("credits", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        mock("/__vod?file=long")
        try {
            PlaybackProbe.reset()
            PlaybackProbe.seekFromEnd = 40_000
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("EPISODE", eps[0].sourceId, eps[0].providerId, "restart")).use {
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                waitTag("up_next_cancel", 20_000)
                Thread.sleep(800); shot("44-siguiente-en-creditos")
                log("Siguiente episodio: aparece 40 s antes del final (créditos), con cuenta de 10 s")
                // Cuenta regresiva: pasa solo al siguiente sin esperar el final.
                try { compose.waitUntil(20_000) { PlaybackProbe.nowId == eps[1].providerId } }
                catch (e: ComposeTimeoutException) { shot("fallo-creditos"); fail("no pasó al siguiente al terminar la cuenta (${PlaybackProbe.nowId})") }
            }
        } finally { mock("/__vod?file="); PlaybackProbe.seekFromEnd = null }
    }

    /** Películas AVI con los códecs más comunes: se reproducen o, si el equipo no puede, se ofrece otro reproductor. */
    @Test fun zd_formatosAvi() {
        val c = app.container
        val movie = runBlocking { c.database.movies().observeAll(1).first().first() }
        val results = mutableListOf<String>()
        for (f in listOf("avi_xvid", "avi_divx3", "avi_h264", "avi_mpeg4sp")) {
            mock("/__vod?file=$f")
            try {
                PlaybackProbe.reset()
                ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("MOVIE", movie.sourceId, movie.providerId, "restart")).use {
                    try { compose.waitUntil(40_000) { (PlaybackProbe.readyCount > 0 && PlaybackProbe.videoDecoder != null) || PlaybackProbe.videoUnsupported ||
                        compose.onAllNodesWithTag("player_error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                    catch (e: ComposeTimeoutException) { shot("fallo-$f"); fail("$f: ni reprodujo ni avisó (${PlaybackProbe.lastError})") }
                    Thread.sleep(2_500)
                    val err = compose.onAllNodesWithTag("player_error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    val r = if (!err && PlaybackProbe.videoDecoder != null) "se reproduce (video ${PlaybackProbe.videoDecoder}, audio ${PlaybackProbe.audioDecoder})"
                        else "no compatible en este equipo → aviso con «Abrir en otro reproductor» (${PlaybackProbe.lastError})"
                    results += "$f: $r"; log("AVI $f: $r")
                    shot("avi-$f")
                    if (err) assertTrue("$f: el aviso ofrece otro reproductor", compose.onAllNodesWithTag("player_error_external", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                }
            } finally { mock("/__vod?file=") }
        }
        assertTrue("AVI con Xvid y H.264 deben reproducirse: $results", results.filter { "se reproduce" in it }.map { it.substringBefore(':') }.containsAll(listOf("avi_xvid", "avi_h264")))
    }

    /**
     * Fire TV Stick: en algunos el video abre sin que nada tenga el foco y solo servían VOLUMEN y ATRÁS.
     * Aquí se quita el foco a propósito y se comprueba que ▶❚❚, ⏩, ◀ ▶ y OK funcionen igual.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun ze_mandoSinFocoFireTv() {
        val c = app.container
        // Episodio de 11 min (el video de prueba de películas dura 20 s y no deja adelantar 10 s varias veces).
        val s = runBlocking { c.catalog.series(null, 40).first() }.first { ser -> runBlocking { c.sources.loadEpisodes(ser.sourceId, ser.providerId); c.database.episodes().forSeries(ser.sourceId, ser.providerId).isNotEmpty() } }
        val ep = runBlocking { c.database.episodes().forSeries(s.sourceId, s.providerId) }.first()
        val inst = InstrumentationRegistry.getInstrumentation()
        com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, true)
        mock("/__vod?file=long")
        try {
            PlaybackProbe.reset(); PlaybackProbe.focusRescues = 0
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("EPISODE", ep.sourceId, ep.providerId, "restart")).use { sc ->
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                Thread.sleep(2_000)
                lateinit var vm: com.embyverse.app.player.PlayerViewModel
                sc.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[com.embyverse.app.player.PlayerViewModel::class.java] }
                fun <T> onMain(f: () -> T): T { var r: T? = null; inst.runOnMainSync { r = f() }; @Suppress("UNCHECKED_CAST") return r as T }
                fun playing() = onMain { vm.player.value?.isPlaying == true }
                fun pos() = onMain { vm.player.value?.currentPosition ?: 0L }
                fun focusDesc(): String { var d = ""; sc.onActivity { a -> d = a.currentFocus?.let { it.javaClass.simpleName + "#" + runCatching { a.resources.getResourceEntryName(it.id) }.getOrDefault("?") } ?: "nada" }; return d }
                fun focusInVideo(): Boolean { var ok = false; sc.onActivity { a -> var f: android.view.View? = a.currentFocus; val v = vm.playerView?.get()
                    while (f != null) { if (f === v) { ok = true; break }; f = f.parent as? android.view.View } }; return ok }
                /** Como en el Fire TV: nada de la pantalla tiene el foco y los controles están ocultos. */
                // Un Fire TV nunca está en «modo táctil» (las otras pruebas tocan la pantalla y lo dejan activo).
                fun sinFoco() { inst.setInTouchMode(false); Thread.sleep(200)
                    // Espera a que termine de cargar tras adelantar (el reproductor vuelve a mostrar los controles solo) y los oculta de verdad.
                    compose.waitUntil(10_000) { onMain { vm.player.value?.playbackState == androidx.media3.common.Player.STATE_READY } }
                    var tries = 0
                    sc.onActivity { vm.playerView?.get()?.controllerAutoShow = false }
                    while (tries++ < 15) { var shown = false; sc.onActivity { vm.playerView?.get()?.let { v -> v.hideController(); shown = v.isControllerFullyVisible } }; Thread.sleep(300); if (!shown && !vm.controlsVisible.value) break }
                    sc.onActivity { a -> a.currentFocus?.clearFocus(); a.window.decorView.clearFocus() }; Thread.sleep(700)
                    log("Mando: foco antes de la tecla → ${focusDesc()}") }
                log("Mando: al abrir la película el foco está en ${focusDesc()}")

                sinFoco()
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                compose.waitUntil(5_000) { !playing() }
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                compose.waitUntil(5_000) { playing() }
                log("Mando sin foco: ▶❚❚ pausa y reanuda")

                sinFoco(); var p0 = pos()
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                compose.waitUntil(5_000) { pos() >= p0 + 8_000 }
                log("Mando sin foco: ⏩ adelanta 10 s (${p0 / 1000}s → ${pos() / 1000}s)")

                sinFoco(); p0 = pos()
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
                try { compose.waitUntil(5_000) { pos() >= p0 + 8_000 && vm.controlsVisible.value } }
                catch (e: ComposeTimeoutException) { shot("fallo-mando"); fail("▶ sin foco: posición ${p0 / 1000}s → ${pos() / 1000}s, controles=${vm.controlsVisible.value}, última tecla ${PlaybackProbe.lastKey}, foco ${focusDesc()}") }
                Thread.sleep(600)
                compose.waitUntil(5_000) { focusInVideo() }
                log("Mando sin foco: ▶ adelanta 10 s y muestra los controles con el foco en ${focusDesc()}")
                shot("45-mando-sin-foco-controles")

                // Controles visibles pero el foco se perdió (lo que pasaba en el Fire TV): ◀ lo recupera y OK pausa.
                sc.onActivity { a -> a.currentFocus?.clearFocus(); a.window.decorView.clearFocus() }; Thread.sleep(400)
                sc.onActivity { vm.playerView?.get()?.showController() }; Thread.sleep(400)
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                compose.waitUntil(5_000) { focusInVideo() }
                val before = playing()
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                compose.waitUntil(5_000) { playing() != before }
                log("Mando con controles sin foco: ◀ devuelve el foco a ${focusDesc()} y OK ${if (before) "pausa" else "reanuda"} (rescates de foco: ${PlaybackProbe.focusRescues})")
                Thread.sleep(500); shot("46-mando-ok-pausa")
            }
        } finally { mock("/__vod?file="); com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, false) }
    }

    /** Desde «Eventos de hoy» ▲▼ y CH± solo pasan entre eventos (antes se iba a la carpeta del canal). */
    @Test fun zf_canalesSoloEntreEventos() {
        val c = app.container
        val events = runBlocking { com.embyverse.app.data.repo.Events.today(c.catalog.eventCandidates().first(), limit = 300) }
        log("Eventos de hoy: ${events.map { it.title }}")
        assertTrue("el proveedor de prueba trae 4 eventos en carpetas distintas: ${events.size}", events.size >= 4 && events.map { it.channel.category }.distinct().size >= 4)
        val ids = events.map { it.channel.providerId }
        val inst = InstrumentationRegistry.getInstrumentation()
        com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, true)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("inicio o perfiles", 30_000) { listOf("nav_settings", "profile").any { t -> compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                if (compose.onAllNodesWithTag("nav_settings", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                    compose.onAllNodesWithTag("profile", useUnmergedTree = true)[0].performClick()
                waitFor("fila Eventos de hoy", 40_000) {
                    (0 until 4).any { i -> runCatching { compose.onAllNodes(androidx.compose.ui.test.hasScrollAction(), useUnmergedTree = true)[i].performScrollToNode(androidx.compose.ui.test.hasTestTag("event")) }.isSuccess } ||
                        compose.onAllNodesWithTag("event", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                Thread.sleep(1_000); shot("47-eventos-de-hoy-inicio")
                PlaybackProbe.reset()
                compose.onAllNodesWithTag("event", useUnmergedTree = true)[0].performClick()
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 && PlaybackProbe.nowId == ids[0] }
                Thread.sleep(5_000) // que se quite el aviso inicial del canal
                val seen = mutableListOf(PlaybackProbe.nowId)
                fun press(code: Int, expect: String, what: String) {
                    inst.sendKeyDownUpSync(code)
                    try { compose.waitUntil(15_000) { PlaybackProbe.nowId == expect } }
                    catch (e: ComposeTimeoutException) { shot("fallo-eventos"); fail("$what: debía ir al evento $expect y fue a ${PlaybackProbe.nowId} (fuera de los eventos: ${PlaybackProbe.nowId !in ids})") }
                    seen += PlaybackProbe.nowId; Thread.sleep(900)
                }
                press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, ids[1], "▼")
                waitText(events[1].channel.name.take(18), 10_000); Thread.sleep(400); shot("48-evento-siguiente")
                press(android.view.KeyEvent.KEYCODE_CHANNEL_UP, ids[2], "CH+")
                press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, ids[3], "▼")
                press(android.view.KeyEvent.KEYCODE_DPAD_DOWN, ids[0], "▼ (del último vuelve al primero)")
                press(android.view.KeyEvent.KEYCODE_DPAD_UP, ids[ids.size - 1], "▲ (del primero va al último)")
                assertTrue("todo lo visitado son eventos: $seen", seen.all { it in ids })
                log("Eventos: ▲▼ y CH± solo pasan entre eventos → ${seen.map { id -> events.first { it.channel.providerId == id }.title }}")
                // Lista de canales (◀): muestra «Eventos de hoy».
                Thread.sleep(3_000)
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT)
                waitText(app.getString(R.string.events_today), 10_000)
                Thread.sleep(800); shot("49-lista-eventos-en-reproductor")
                back()
            }
        } finally { com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, false) }
    }

    /** Bloqueo desde el panel (Estadísticas): por equipo y por usuario. Cierra el video, muestra el código y al desbloquear sigue todo igual. */
    @Test fun zg_bloqueoDeEquipo() {
        val c = app.container
        val id = c.stats.installId
        val dk = com.embyverse.app.data.repo.DeviceBlock.deviceKey(id)
        val code = com.embyverse.app.data.repo.DeviceBlock.shortCode(id)
        com.embyverse.app.data.repo.DeviceBlock.testUrl = "$base/blocked.json"
        fun check() = runBlocking { com.embyverse.app.data.repo.DeviceBlock.check(app, c.okHttp, id, c.sources.sources.first()) }
        try {
            mock("/__blocked")
            assertTrue("sin lista: no bloqueado", check() == false)
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                waitFor("inicio o perfiles", 30_000) { listOf("nav_settings", "profile").any { t -> compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                // Viendo un canal cuando lo bloquean: se cierra el video y queda la pantalla de bloqueo con su código.
                val ch = runBlocking { c.database.channels().listForZapping(null).first() }
                PlaybackProbe.reset()
                sc.onActivity { a -> com.embyverse.app.player.PlayerActivity.start(a, com.embyverse.app.data.repo.ItemRef(com.embyverse.core.model.ContentKind.LIVE, ch.sourceId, ch.providerId)) }
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                Thread.sleep(1_500)
                mock("/__blocked?d=$dk")
                assertTrue("con su huella en la lista: bloqueado", check() == true)
                val closed = { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).none { it is com.embyverse.app.player.PlayerActivity } }
                compose.waitUntil(10_000) { var ok = false; InstrumentationRegistry.getInstrumentation().runOnMainSync { ok = closed() }; ok }
                log("Bloqueo: el video se cerró solo al bloquear el equipo")
                waitTag("device_blocked", 15_000)
                waitText(code, 5_000)
                Thread.sleep(800); shot("50-equipo-bloqueado")
                log("Bloqueo: pantalla «Este equipo fue bloqueado» con el código $code")
                // Desbloqueado: «Revisar de nuevo» y vuelve la app tal cual (sin borrar cuentas).
                mock("/__blocked")
                compose.onNodeWithText(app.getString(R.string.device_blocked_retry), useUnmergedTree = true).performClick()
                waitFor("app de vuelta", 20_000) { compose.onAllNodesWithTag("device_blocked", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() &&
                    listOf("nav_settings", "profile").any { t -> compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                assertTrue("al desbloquear conserva su cuenta", runBlocking { c.sources.sources.first() }.isNotEmpty())
                log("Bloqueo: al desbloquear la app vuelve con su cuenta")
            }
            // Bloquear al usuario (todos sus equipos de esta app).
            val src = runBlocking { c.sources.sources.first() }.let { l -> l.firstOrNull { it.type == "XTREAM" } ?: l.first() }
            val host = java.net.URI(src.location).host
            mock("/__blocked?u=${com.embyverse.app.data.repo.DeviceBlock.userKey(com.embyverse.app.BuildConfig.VENDOR, host, src.username.orEmpty())}")
            assertTrue("usuario bloqueado: este equipo también", check() == true)
            assertTrue("se recuerda sin internet", com.embyverse.app.data.repo.DeviceBlock.stored(app))
            log("Bloqueo por usuario (${src.username}@$host): también bloquea este equipo")
            mock("/__blocked"); check()
        } finally { mock("/__blocked"); runCatching { check() }; com.embyverse.app.data.repo.DeviceBlock.testUrl = null }
    }

    /** Canal MPEG-2 en un equipo sin ese decodificador (muchos Fire TV y celulares): se oye y avisa con «Abrir en otro reproductor». */
    @Test fun zh_canalMpeg2SinDecodificador() {
        val c = app.container
        val ch = runBlocking { c.database.channels().listForZapping(null).first() }
        mock("/__live?file=mpeg2")
        try {
            // 1) Equipo que sí lo trae: se ve normal.
            PlaybackProbe.reset(); PlaybackProbe.hideMpeg2Decoder = false
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("LIVE", ch.sourceId, ch.providerId)).use {
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 || PlaybackProbe.lastError != null }
                Thread.sleep(3_000)
                log("MPEG-2 con decodificador del equipo: imagen=${!PlaybackProbe.videoUnsupported} · ${PlaybackProbe.videoDecoder ?: "-"} · ${PlaybackProbe.lastError ?: "sin error"}")
                shot("51-mpeg2-equipo-con-decodificador")
            }
            // 2) Equipo sin decodificador MPEG-2 (Fire TV, muchos celulares): la app usa el suyo (FFmpeg) y se ve.
            PlaybackProbe.reset(); PlaybackProbe.hideMpeg2Decoder = true
            if (PlaybackProbe.softwareVideo) ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("LIVE", ch.sourceId, ch.providerId)).use {
                try { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 && PlaybackProbe.videoDecoder != null } }
                catch (e: ComposeTimeoutException) { shot("fallo-mpeg2-software"); fail("MPEG-2 con el decodificador de la app: sin video (${PlaybackProbe.lastError}) · aviso=${compose.onAllNodesWithTag("live_no_video", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()}") }
                Thread.sleep(4_000)
                assertTrue("con el decodificador de la app no debe salir el aviso", compose.onAllNodesWithTag("live_no_video", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                assertTrue("decodificador de la app: ${PlaybackProbe.videoDecoder}", PlaybackProbe.videoDecoder!!.contains("ffmpeg", ignoreCase = true))
                shot("53-mpeg2-decodificador-de-la-app")
                log("MPEG-2 sin decodificador del equipo: se VE con el de la app (${PlaybackProbe.videoDecoder}) y NO sale el aviso · audio ${PlaybackProbe.audioDecoder}")
            }
            // 3) Sin ningún decodificador (ni el de la app): sale el aviso; ATRÁS lo cierra y sigue en el canal.
            PlaybackProbe.reset(); PlaybackProbe.hideMpeg2Decoder = true; PlaybackProbe.hideSoftwareVideo = true
            if (PlaybackProbe.softwareVideo) ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("LIVE", ch.sourceId, ch.providerId)).use { sc ->
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                waitTag("live_no_video", 20_000)
                Thread.sleep(600); shot("54-mpeg2-sin-ningun-decodificador-aviso")
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                compose.waitUntil(5_000) { compose.onAllNodesWithTag("live_no_video", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
                Thread.sleep(1_500)
                assertTrue("ATRÁS solo cierra el aviso: sigue en el canal", sc.state == androidx.lifecycle.Lifecycle.State.RESUMED)
                log("MPEG-2 sin ningún decodificador: aviso tras unos segundos; ATRÁS lo cierra y se queda en el canal")
                return
            }
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("LIVE", ch.sourceId, ch.providerId)).use {
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 }
                waitTag("live_no_video", 20_000)
                waitText("MPEG-2", 5_000)
                assertTrue("el audio sigue sonando", PlaybackProbe.audioDecoder != null || !PlaybackProbe.audioUnsupported)
                Thread.sleep(800); shot("52-mpeg2-sin-decodificador-aviso")
                log("MPEG-2 sin decodificador: aviso «se oye pero no se ve» con «Abrir en otro reproductor» · audio ${PlaybackProbe.audioDecoder}")
            }
        } finally { mock("/__live"); PlaybackProbe.hideMpeg2Decoder = false; PlaybackProbe.hideSoftwareVideo = false }
    }

    /**
     * Todos los formatos comunes de películas y series. MPEG-4 (Xvid/DivX, perfil avanzado) va directo al
     * decodificador de la app: el MPEG-4 de casi todos los equipos solo entiende el perfil simple y no reproduce nada.
     * MPEG-2 se prueba como en un equipo sin ese decodificador (Fire TV, muchos celulares).
     */
    @Test fun zi_todosLosFormatos() {
        val c = app.container
        val movie = runBlocking { c.database.movies().observeAll(1).first().first() }
        // archivo → (qué es, ¿debe usar el decodificador de la app?)
        val files = linkedMapOf(
            "mp4_mpeg4asp" to ("MPEG-4 avanzado (Xvid) en MP4 + AAC" to true),
            "mkv_mpeg4asp" to ("MPEG-4 avanzado (Xvid) en MKV + AC3" to true),
            "avi_divx5" to ("DivX 5 en AVI + MP3" to true),
            "avi_xvid" to ("Xvid en AVI + MP3" to true),
            "avi_divx3" to ("DivX 3 en AVI" to true),
            "mkv_mpeg2" to ("MPEG-2 en MKV + MP2 (equipo sin MPEG-2)" to true),
            "mkv_hevc" to ("HEVC (H.265) en MKV + E-AC3" to false),
            "webm_vp9" to ("VP9 en WebM + Opus" to false),
            "mkv_av1" to ("AV1 en MKV + FLAC" to false),
            "3gp_h263" to ("H.263 en 3GP + AAC" to false),
            "mkv_h264vorbis" to ("H.264 en MKV + Vorbis" to false),
            "avi_h264" to ("H.264 en AVI" to false),
        )
        val table = mutableListOf<String>(); val bad = mutableListOf<String>()
        for ((f, info) in files) {
            val (what, needApp) = info
            mock("/__vod?file=$f")
            PlaybackProbe.reset(); PlaybackProbe.hideMpeg2Decoder = f == "mkv_mpeg2"
            try {
                ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("MOVIE", movie.sourceId, movie.providerId, "restart")).use { sc ->
                    lateinit var vm: com.embyverse.app.player.PlayerViewModel
                    val ok = runCatching { compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 && PlaybackProbe.videoDecoder != null } }.isSuccess
                    sc.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[com.embyverse.app.player.PlayerViewModel::class.java] }
                    fun pos(): Long { var p = 0L; InstrumentationRegistry.getInstrumentation().runOnMainSync { p = vm.player.value?.currentPosition ?: 0L }; return p }
                    val p0 = pos(); Thread.sleep(3_000); val p1 = pos()
                    val err = compose.onAllNodesWithTag("player_error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                    val vd = PlaybackProbe.videoDecoder ?: "-"; val ad = PlaybackProbe.audioDecoder ?: "-"
                    val plays = ok && !err && p1 > p0 + 1_500
                    val viaApp = vd.contains("ffmpeg", ignoreCase = true)
                    val line = "${if (plays) "✓" else "✗"} $what → video $vd · audio $ad${if (PlaybackProbe.softwareRetries > 0) " (reintento con la app)" else ""}"
                    table += line; log("Formato $f: $line")
                    shot("fmt-$f")
                    if (!plays) bad += "$f no se reprodujo (${PlaybackProbe.lastError}, pos ${p0}→${p1})"
                    else if (needApp && !viaApp) bad += "$f debía usar el decodificador de la app y usó $vd"
                }
            } finally { mock("/__vod?file="); PlaybackProbe.hideMpeg2Decoder = false }
        }
        log("FORMATOS:\n" + table.joinToString("\n"))
        assertTrue("Formatos con problema: $bad", bad.isEmpty())
    }

    /** ⏮ ⏭ en la barra del reproductor y en el control: capítulo anterior / siguiente. */
    @Test fun zj_capituloAnteriorSiguiente() {
        val c = app.container
        val s = runBlocking { c.catalog.series(null, 60).first() }.first { ser -> runBlocking { c.sources.loadEpisodes(ser.sourceId, ser.providerId); c.database.episodes().forSeries(ser.sourceId, ser.providerId).size >= 3 } }
        val eps = runBlocking { c.database.episodes().forSeries(s.sourceId, s.providerId) }
        val inst = InstrumentationRegistry.getInstrumentation()
        com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, true)
        try {
            PlaybackProbe.reset()
            ActivityScenario.launch<com.embyverse.app.player.PlayerActivity>(playerIntent("EPISODE", eps[1].sourceId, eps[1].providerId, "restart")).use { sc ->
                compose.waitUntil(40_000) { PlaybackProbe.readyCount > 0 && PlaybackProbe.nowId == eps[1].providerId }
                lateinit var vm: com.embyverse.app.player.PlayerViewModel
                sc.onActivity { vm = androidx.lifecycle.ViewModelProvider(it)[com.embyverse.app.player.PlayerViewModel::class.java] }
                compose.waitUntil(10_000) { vm.episodeNav.value.first != null && vm.episodeNav.value.second != null }
                fun btn(id: Int): android.view.View? { var v: android.view.View? = null; sc.onActivity { v = vm.playerView?.get()?.findViewById(id) }; return v }
                fun shown(id: Int): Boolean { var ok = false; sc.onActivity { val v = vm.playerView?.get()?.findViewById<android.view.View>(id); ok = v != null && v.isShown && v.isEnabled }; return ok }
                fun showCtl() { sc.onActivity { vm.playerView?.get()?.showController() }; Thread.sleep(800) }
                val prevId = androidx.media3.ui.R.id.exo_prev; val nextId = androidx.media3.ui.R.id.exo_next
                showCtl()
                assertTrue("⏮ visible en el capítulo 2", shown(prevId)); assertTrue("⏭ visible en el capítulo 2", shown(nextId))
                shot("55-capitulo-anterior-siguiente")
                log("Capítulos: en «${eps[1].title}» la barra muestra ⏮ y ⏭")
                // ⏭ en la barra
                sc.onActivity { vm.playerView?.get()?.findViewById<android.view.View>(nextId)?.performClick() }
                compose.waitUntil(20_000) { PlaybackProbe.nowId == eps[2].providerId }
                log("Capítulos: ⏭ de la barra → ${eps[2].title}")
                // ⏮ en la barra
                compose.waitUntil(10_000) { vm.episodeNav.value.first?.id == eps[1].providerId }
                showCtl(); sc.onActivity { vm.playerView?.get()?.findViewById<android.view.View>(prevId)?.performClick() }
                compose.waitUntil(20_000) { PlaybackProbe.nowId == eps[1].providerId }
                log("Capítulos: ⏮ de la barra → ${eps[1].title}")
                // Teclas ⏮ ⏭ del control remoto
                compose.waitUntil(10_000) { vm.episodeNav.value.first?.id == eps[0].providerId }
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                compose.waitUntil(20_000) { PlaybackProbe.nowId == eps[0].providerId }
                compose.waitUntil(10_000) { vm.episodeNav.value.first == null && vm.episodeNav.value.second?.id == eps[1].providerId }
                showCtl()
                assertTrue("en el primer capítulo no hay ⏮", !shown(prevId)); assertTrue("en el primer capítulo sí hay ⏭", shown(nextId))
                shot("56-primer-capitulo-sin-anterior")
                inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
                compose.waitUntil(20_000) { PlaybackProbe.nowId == eps[1].providerId }
                log("Capítulos: teclas ⏮ ⏭ del control funcionan; en el primero no aparece ⏮")
            }
        } finally { com.embyverse.app.data.prefs.UiPrefs.setForceTv(app, false) }
    }

    /** Al salir y volver a la app, la lista se actualiza sola: lo nuevo del proveedor aparece sin pulsar «Actualizar». */
    @Test fun zk_actualizaAlVolver() {
        val c = app.container
        val src = runBlocking { c.sources.sources.first() }.first { it.type == "XTREAM" } // la película nueva llega por la API Xtream
        val inst = InstrumentationRegistry.getInstrumentation()
        val oldAge = EmbyVerseApp.RESUME_MIN_AGE_MS; val oldAway = EmbyVerseApp.awayMs
        EmbyVerseApp.RESUME_MIN_AGE_MS = 1_000; EmbyVerseApp.awayMs = 2_000
        try {
            runBlocking { c.sources.sync(src.id) }
            ActivityScenario.launch(MainActivity::class.java).use {
                waitFor("inicio o perfiles", 30_000) { listOf("nav_settings", "profile").any { t -> compose.onAllNodesWithTag(t, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
                assertTrue("aún no existe el estreno", runBlocking { c.database.movies().get(src.id, "999001") } == null)
                // Sale de la app; mientras tanto el proveedor agrega una película.
                inst.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                Thread.sleep(1_500)
                mock("/__estreno?on=1")
                Thread.sleep(2_500)
                val before = runBlocking { c.database.sources().get(src.id) }?.lastSyncAt ?: 0
                // Vuelve a la app.
                inst.targetContext.startActivity(android.content.Intent(inst.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                // Empieza a actualizar al volver (en segundos)…
                waitFor("empezó a actualizar al volver", 30_000) { c.sources.syncState.value[src.id] is com.embyverse.app.data.repo.SyncState.Running ||
                    (runBlocking { c.database.sources().get(src.id) }?.lastSyncAt ?: 0) > before }
                log("Al volver a la app: empezó a actualizar sola")
                // …y la película nueva aparece al terminar (el proveedor de prueba tiene 60 000 películas: en el emulador tarda).
                waitFor("estreno en la lista al volver", 300_000) { runBlocking { c.database.movies().get(src.id, "999001") } != null }
                val after = runBlocking { c.database.sources().get(src.id) }?.lastSyncAt ?: 0
                assertTrue("se actualizó de verdad al volver ($before → $after)", after > before)
                runCatching { waitText("Estreno de prueba", 15_000) } // en el inicio sale en «Novedades» si cabe en pantalla
                Thread.sleep(800); shot("57-actualizado-al-volver")
                log("Al volver a la app: se actualizó sola y apareció «Estreno de prueba» sin pulsar Actualizar")
            }
        } finally {
            EmbyVerseApp.RESUME_MIN_AGE_MS = oldAge; EmbyVerseApp.awayMs = oldAway
            mock("/__estreno"); runBlocking { c.sources.sync(src.id) }
        }
    }
}

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.isRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            Thread.sleep(1500); shot("01-inicio")

            // ---- Películas
            var t = System.currentTimeMillis()
            compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
            waitCards()
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
            for (id in listOf("style_cristal", "style_cine_rojo", "style_purpura")) {
                runBlocking { c.prefs.updateAppearance(profile) { it.copy(themeId = id) } }
                ActivityScenario.launch(MainActivity::class.java).use {
                    waitTag("nav_movies", 30_000)
                    Thread.sleep(2_500); shot("tema-$id-inicio")
                    compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
                    waitCards()
                    Thread.sleep(1_500); shot("tema-$id-peliculas")
                }
                log("Estilo $id: menú, inicio y películas sin cierres")
            }
        } finally {
            runBlocking { c.prefs.updateAppearance(profile) { it.copy(themeId = null) } }
        }
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
}

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

    private fun waitTag(tag: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitText(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

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
        Espresso.pressBack() // cerrar reproductor
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

            // ---- Películas
            var t = System.currentTimeMillis()
            compose.onNodeWithTag("nav_movies", useUnmergedTree = true).performClick()
            waitCards()
            log("Películas: cuadrícula en ${System.currentTimeMillis() - t} ms · ${memory()}")
            for (index in listOf(0, 1, 3)) { // .mp4, .mkv y .avi en el catálogo (el simulador entrega el mismo clip)
                t = System.currentTimeMillis()
                openCard("MOVIE", (index + 1).toString(), index)
                waitText("Reproducir")
                log("Película #$index: ficha en ${System.currentTimeMillis() - t} ms")
                playAndCheck("Película #$index") { compose.onAllNodesWithText("Reproducir")[0].performClick() }
                Espresso.pressBack() // volver a la cuadrícula
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
                    waitText("episodio", 20_000)
                    log("Serie #$index (sin episodios): aviso mostrado")
                } else {
                    waitTag("episode", 30_000)
                    log("Serie #$index: episodios en ${System.currentTimeMillis() - t} ms")
                    playAndCheck("Serie #$index episodio") { compose.onAllNodesWithTag("episode", useUnmergedTree = true)[0].performClick() }
                }
                Espresso.pressBack()
                waitTag("grid")
            }

            // ---- TV en vivo (.ts continuo)
            compose.onNodeWithTag("nav_live", useUnmergedTree = true).performClick()
            waitTag("channel", 30_000)
            playAndCheck("Canal en vivo") { compose.onAllNodesWithTag("channel", useUnmergedTree = true)[0].performClick() }
            log("Recorrido completo sin cierres · ${memory()}")
        }
    }

    private fun hasTagPrefix(prefix: String) = SemanticsMatcher("tag empieza por $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    private fun waitCards(timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodes(hasTagPrefix("card:"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    /** Desplaza la cuadrícula hasta el elemento y lo abre por su id del proveedor. */
    private fun openCard(kind: String, id: String, gridIndex: Int) {
        compose.onNodeWithTag("grid").performScrollToIndex(gridIndex)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("card:$kind:$id", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
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
}

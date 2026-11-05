package com.embyverse.core

import com.embyverse.core.config.ConfigParseResult
import com.embyverse.core.config.RemoteConfigParser
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** El diseño publicado de verdad (copia de config/app.json) debe aplicarse en la app, con sus mensajes. */
class PublishedConfigTest {
    @Test fun publishedConfigIsAccepted() {
        val text = javaClass.getResource("/published-app.json")!!.readText()
        when (val r = RemoteConfigParser.parse(text)) {
            is ConfigParseResult.Invalid -> fail("Rechazado: ${r.errors}")
            is ConfigParseResult.Valid -> {
                println("Advertencias: ${r.warnings}")
                assertTrue(r.config.messages.isNotEmpty(), "trae mensajes")
            }
        }
    }
}

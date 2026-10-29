package com.embyverse.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.embyverse.app.data.secure.CredentialStore
import com.embyverse.core.config.ConfigParseResult
import com.embyverse.core.config.RemoteConfigParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun credencialesCifradasConKeystore() {
        val store = CredentialStore(ctx)
        store.put("src-test", "contraseña-secreta")
        assertEquals("contraseña-secreta", store.get("src-test"))
        val raw = ctx.getSharedPreferences("ev_secure_credentials", 0).getString("pw_src-test", "")!!
        assertTrue("el valor guardado no es el texto plano", !raw.contains("secreta"))
        store.remove("src-test")
        assertNull(store.get("src-test"))
    }

    @Test fun configuracionEmpaquetadaEsValida() {
        val text = ctx.assets.open("default_config.json").bufferedReader().readText()
        assertTrue(RemoteConfigParser.parse(text) is ConfigParseResult.Valid)
    }
}

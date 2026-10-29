package com.embyverse.app.data.secure

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda las contraseñas de los proveedores cifradas con AES-256-GCM. La clave vive en
 * Android Keystore (no extraíble; respaldada por hardware cuando el dispositivo lo permite).
 * El archivo de preferencias sólo contiene texto cifrado y está excluido de las copias de
 * seguridad (ver res/xml/backup_rules.xml y data_extraction_rules.xml).
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("ev_secure_credentials", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_ALIAS = "embyverse_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(sourceId: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString("pw_$sourceId", packed).apply()
    }

    /** Devuelve null si no existe o si la clave del Keystore se invalidó (el usuario deberá reintroducirla). */
    @Synchronized
    fun get(sourceId: String): String? {
        val packed = prefs.getString("pw_$sourceId", null) ?: return null
        return try {
            val (ivB64, ctB64) = packed.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun remove(sourceId: String) { prefs.edit().remove("pw_$sourceId").apply() }
}

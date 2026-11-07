package com.embyverse.app.data.prefs

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Preferencias de presentación que se leen al instante al abrir la app (sin esperar a DataStore):
 * bienvenida vista, última versión de la que se mostraron las novedades y tamaño de letra.
 */
object UiPrefs {
    private const val FILE = "ui"
    private val _fontScale = MutableStateFlow(1f)
    /** 1 = normal, 1.15 = grande, 1.3 = muy grande. */
    val fontScale: StateFlow<Float> = _fontScale

    /** La bienvenida está en pantalla (las «Novedades» esperan a que termine). */
    val tourVisible = MutableStateFlow(false)

    fun init(ctx: Context) { _fontScale.value = sp(ctx).getFloat("font_scale", 1f) }
    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setFontScale(ctx: Context, v: Float) { sp(ctx).edit().putFloat("font_scale", v).apply(); _fontScale.value = v }

    fun onboardingDone(ctx: Context) = sp(ctx).getBoolean("onboarding_done", false) && !sp(ctx).getBoolean("force_tour", false)
    fun setOnboardingDone(ctx: Context) { sp(ctx).edit().putBoolean("onboarding_done", true).putBoolean("force_tour", false).apply() }
    /** Pruebas: forzar la bienvenida aunque ya haya cuentas. */
    fun forceTour(ctx: Context) = sp(ctx).getBoolean("force_tour", false)

    /** Versión de la que el cliente ya vio «Novedades». 0 = instalación nueva (no se muestran). */
    fun lastSeenVersion(ctx: Context) = sp(ctx).getInt("seen_version", 0)
    fun setSeenVersion(ctx: Context, code: Int) { sp(ctx).edit().putInt("seen_version", code).apply() }
}

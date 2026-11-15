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

    /** Margen de pantalla en TV (algunos TV recortan los bordes, «overscan»): 0 = sin margen, 1 = normal, 2 = grande. */
    private val _tvMargin = MutableStateFlow(1)
    val tvMargin: StateFlow<Int> = _tvMargin
    fun setTvMargin(ctx: Context, v: Int) { sp(ctx).edit().putInt("tv_margin", v).apply(); _tvMargin.value = v }

    fun init(ctx: Context) { _fontScale.value = sp(ctx).getFloat("font_scale", 1f); _tvMargin.value = sp(ctx).getInt("tv_margin", 1) }
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

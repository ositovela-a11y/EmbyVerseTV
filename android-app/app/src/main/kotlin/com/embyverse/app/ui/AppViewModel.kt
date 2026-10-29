package com.embyverse.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.embyverse.app.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer no disponible") }

/** Crea un ViewModel con acceso al contenedor de dependencias (sin reflexión). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalAppContainer.current
    return viewModel(key = key) { create(c) }
}

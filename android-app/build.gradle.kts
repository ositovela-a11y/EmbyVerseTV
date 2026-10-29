// Proyecto raíz de EmbyVerse (Android). Módulos:
//  :core → Kotlin/JVM puro: M3U, Xtream, XMLTV, configuración remota, recomendaciones (sin Android).
//  :app  → Aplicación Android (Compose, Media3, Room, DataStore, WorkManager).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

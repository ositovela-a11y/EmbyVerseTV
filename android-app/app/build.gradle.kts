import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// ---------- Configuración externa (sin secretos en el repositorio) ----------
// URL pública del backend que sirve la configuración visual. No es un secreto.
// Se define en ~/.gradle/gradle.properties, en android-app/local.properties o con -Pembyverse.configUrl=...
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun prop(name: String): String? = (project.findProperty(name) as String?) ?: localProps.getProperty(name) ?: System.getenv(name.uppercase().replace('.', '_'))
val configBaseUrl = prop("embyverse.configUrl") ?: ""
// App de un vendedor: identificador propio para que conviva con las demás en el mismo equipo.
// Ejemplo: -Pembyverse.vendor=juan → com.embyverse.app.v.juan
val vendor = (prop("embyverse.vendor") ?: "").lowercase().filter { it.isLetterOrDigit() }.takeIf { it.isNotEmpty() && it[0].isLetter() }

// Firma de producción: el propietario crea su keystore y keystore.properties (NO se versiona).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrBlank() }

android {
    namespace = "com.embyverse.app"
    compileSdk = 37

    defaultConfig {
        applicationId = if (vendor != null) "com.embyverse.app.v.$vendor" else "com.embyverse.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CONFIG_BASE_URL", "\"${configBaseUrl.replace("\"", "")}\"")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        // Clave de DEPURACIÓN compartida (contraseña pública "android"), versionada a propósito para que
        // cada APK de prueba compilado en GitHub se instale encima del anterior sin desinstalar.
        // NO sirve para producción: la de producción va en keystore.properties (fuera del repositorio).
        getByName("debug") {
            storeFile = file("embyverse-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sin keystore.properties el APK release queda SIN firmar (no se inventa ninguna firma).
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // El tema oscuro es intencional y el contenido proviene de fuentes externas.
        disable += setOf("MissingTranslation")
        abortOnError = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// app/src/main/assets/default_config.json es una copia de ../shared/default-config.json.
// Se mantiene sincronizada con scripts/sync-shared.sh (la tarea comprueba que no difieran).
val checkDefaultConfigSync by tasks.registering {
    val shared = rootProject.file("../shared/default-config.json")
    val bundled = file("src/main/assets/default_config.json")
    inputs.files(shared, bundled)
    doLast {
        if (shared.exists() && shared.readText() != bundled.readText()) {
            throw GradleException("default_config.json no coincide con shared/default-config.json. Ejecuta scripts/sync-shared.sh")
        }
    }
}
tasks.named("preBuild") { dependsOn(checkDefaultConfigSync) }

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

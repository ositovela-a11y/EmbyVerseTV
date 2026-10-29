# Compilar, instalar y actualizar

## Requisitos

| Herramienta | Versión |
|---|---|
| Android Studio | Una versión que soporte Android Gradle Plugin 9.4 (2026.x) |
| JDK | 17 o superior (el incluido en Android Studio sirve) |
| Android SDK | Plataforma 37 (compileSdk), Build Tools 36 |
| Node.js (backend y panel) | 22.13 o superior |

Versiones de dependencias (`android-app/gradle/libs.versions.toml`), comprobadas en las notas oficiales el 29-oct-2026: AGP 9.4.0 (requiere Gradle 9.6.0, fijado en el wrapper), Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2026.09.00, Media3 1.11.1, Room 2.8.5, Activity 1.13.0, Core 1.19.1, Lifecycle 2.11.0, Navigation 2.10.2, DataStore 1.3.0. OkHttp 5.1.0, Coil 3.3.0, WorkManager 2.10.5 y coroutines 1.10.2 son versiones estables conocidas, pero no son necesariamente las últimas.

> **No se ha ejecutado ninguna compilación Gradle de esta configuración** (el entorno de la entrega no tenía acceso a los repositorios Maven). Si tu Android Studio no reconoce AGP 9.4, usa *Tools › AGP Upgrade Assistant* o baja `agp` a la versión que proponga Studio.

## APK de depuración

```bash
cp android-app/local.properties.example android-app/local.properties
# Edita sdk.dir (Studio lo rellena solo) y embyverse.configUrl (URL https del backend; vacía = diseño integrado)
./scripts/build-apk.sh
```

Equivalente manual:

```bash
cd android-app
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
# Resultado: app/build/outputs/apk/debug/app-debug.apk  (applicationId com.embyverse.app.debug)
```

Desde Android Studio: *File › Open* → carpeta `android-app` → espera la sincronización → *Build › Build App Bundle(s) / APK(s) › Build APK(s)*.

**Instalar:** `adb install -r out/EmbyVerse-debug.apk`. En un TV Box: activa *Opciones de desarrollador › Depuración USB/ADB por red*, `adb connect IP_DEL_TV:5555` y el mismo comando.

**Pruebas instrumentadas** (Room, Keystore, configuración empaquetada) con un emulador o dispositivo conectado: `./gradlew :app:connectedDebugAndroidTest`.

## APK de producción firmado

La firma la controla el propietario. No hay ninguna clave en el repositorio.

```bash
cd android-app
keytool -genkeypair -v -keystore ../embyverse-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias embyverse
cp keystore.properties.example keystore.properties      # rellena las contraseñas
./gradlew :app:assembleRelease                           # → app/build/outputs/apk/release/app-release.apk
```

Guarda el `.jks` y sus contraseñas fuera del repositorio y con copia de seguridad: si se pierden, no podrás publicar actualizaciones de la misma app. Para Google Play usa `bundleRelease` (AAB) y Play App Signing. Sin `keystore.properties`, `assembleRelease` produce un APK **sin firmar** que no se puede instalar.

## Actualizar la app

1. Sube `versionCode` (y `versionName`) en `android-app/app/build.gradle.kts`.
2. Si cambiaste el esquema de Room, añade una `Migration` (la base no se borra nunca automáticamente).
3. Compila el release y distribúyelo (Play Store o tu canal). Android solo instala una actualización firmada con la misma clave.
4. Si publicas diseños que requieren la nueva versión, pon `minAppVersionCode` en la configuración: las apps antiguas seguirán recibiendo la última versión compatible.

Los cambios de diseño (colores, secciones, banners, textos) **no** necesitan nueva versión de la app: se publican desde el Design Studio.

## Backend y panel

```bash
cd admin-web && npm install && npm run build       # genera admin-web/dist
cd ../backend && npm install && npm test            # compila y ejecuta las 40 pruebas
cp .env.example .env                                # ajusta PUBLIC_BASE_URL, DATA_DIR…
ADMIN_EMAIL=... ADMIN_PASSWORD=... npm start        # primer arranque: crea el propietario
```

Después del primer arranque **elimina `ADMIN_PASSWORD` del entorno**. Alternativa: `npm run cli -- create-owner tu@email.com`.

**Producción con HTTPS:** `backend/docker-compose.example.yml` levanta el servicio detrás de Caddy con certificado de Let's Encrypt (archivos de ejemplo, no probados en esta entrega). Cualquier proxy inverso sirve; define `TRUST_PROXY=true`.

**Copias de seguridad:** todo el estado está en `DATA_DIR` (`embyverse.db` y `assets/`). Copia la carpeta con el servicio detenido, o usa `sqlite3 embyverse.db ".backup copia.db"`.

### Costes

El backend no usa servicios de pago. Necesitas un servidor (un VPS pequeño basta, típicamente 4–6 USD/mes), un dominio y un certificado (Let's Encrypt es gratuito). Una CDN delante de `/api/v1/public/*` es opcional.

## Si la compilación de `:app` falla

Como no se pudo compilar aquí, puede haber errores de API (firmas de Compose o Media3 que hayan cambiado). Copia el primer error de Gradle completo (archivo y línea) y envíalo para corregirlo: suelen ser cambios de una línea. El núcleo (`:core`) ya está verificado: `./scripts/test-core-jvm.sh` lo compila y prueba incluso sin Android SDK.

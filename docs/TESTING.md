# Pruebas y resultados

Leyenda: **✔ ejecutada aquí y aprobada** · **◻ escrita, no ejecutada aquí** (requiere Android SDK/dispositivo) · **☐ manual** (procedimiento abajo).
Nada se marca como aprobado sin haberse ejecutado. Registros completos en `docs/test-logs/`.

## Resultado de esta entrega (29-oct-2026)

| Suite | Herramienta | Resultado | Registro |
|---|---|---|---|
| Núcleo Android `:core` (M3U, Xtream, XMLTV, configuración, preferencias, sincronización, seguridad) | Kotlin 2.0.21 + JUnit 4 (`scripts/test-core-jvm.sh`) | **40/40 ✔** | `test-logs/core-junit.txt` |
| Backend (API, permisos, publicación, imágenes, esquema compartido) | `node --test` | **40/40 ✔** | `test-logs/backend-node-test.txt` |
| Design Studio en navegador real | Playwright + Chromium | **15/15 ✔** | `test-logs/admin-e2e-playwright.txt`, capturas en `screenshots/` |
| App `:app` — pruebas JVM (`OkHttpFetcherTest`) | Gradle | ◻ | — |
| App `:app` — instrumentadas (Room, Keystore, configuración empaquetada) | Gradle + dispositivo | ◻ | — |
| Compilación del APK | Gradle | ◻ **no realizada** (sin SDK ni repositorios Maven) | — |

## Matriz de pruebas obligatorias

| Requisito | Cobertura | Estado |
|---|---|---|
| Importación de una lista M3U | `M3uParserTest` (atributos, comas, cabeceras, relativas, duplicados, CRLF, límites); `SourceAdaptersTest` por URL y archivo | ✔ |
| Conexión a una URL de prueba | `SourceAdaptersTest` contra un servidor HTTP local real; `OkHttpFetcherTest` (MockWebServer) | ✔ / ◻ |
| Autenticación incorrecta | Xtream `auth:0`, HTTP 401/403, contraseña vacía, cuenta caducada | ✔ |
| Respuestas de proveedor inválidas | HTML, JSON roto, 404, 5xx, timeout, sección que falla sin romper el resto, HLS de un solo stream | ✔ |
| Reproducción de streams autorizados | Procedimiento manual con `tests/fixtures/m3u/demo-authorized.m3u` | ☐ |
| Actualización de catálogos | `CatalogLogicTest` (altas, cambios, bajas, duplicados del proveedor); ids estables entre sincronizaciones | ✔ |
| Guardado de favoritos | `LibraryDaoTest.favoritosPorPerfil` | ◻ |
| Continuar viendo | `LibraryDaoTest.continuarViendoExcluyeTerminadosYEnVivo` | ◻ |
| Cambio de tema | `ConfigResolverTest` (Kotlin) y `resolve` (TS): tema, acento permitido y bloqueado | ✔ |
| Publicación de banners | API: subida, banner publicado, host no autorizado rechazado; E2E: banner creado y publicado | ✔ |
| Actualización remota del diseño | API + E2E: cambio publicado llega a `/public/config`; ETag/304; apps antiguas reciben versión compatible; `ConfigManagerTest` aplica en el dispositivo | ✔ |
| Configuración inválida | 14 fixtures inválidos rechazados por Kotlin y TS; borrador inválido no se publica (API y E2E) | ✔ |
| Funcionamiento sin conexión | `ConfigManagerTest`: última válida o diseño empaquetado; reversión tras fallos de arranque | ✔ |
| Permisos del panel administrativo | API: viewer/editor/publisher/owner, CSRF, bloqueo, último propietario, sesión cerrada | ✔ |
| Diferentes tamaños de pantalla | E2E: vista previa teléfono/tableta/TV y panel en 390 px; en la app ☐ (procedimiento abajo) | ✔ / ☐ |

## Cómo ejecutar

```bash
./scripts/test-core-jvm.sh                     # núcleo, sin Android SDK
cd android-app && ./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest
cd backend && npm test
# E2E del panel (backend en marcha con PUBLIC_BASE_URL https y panel compilado):
EMAIL=... PASSWORD=... BASE=http://localhost:8080 BANNER_IMAGE=tests/e2e/banner-test.png node tests/e2e/admin-panel.e2e.mjs
```

## Procedimiento manual: reproducción con streams autorizados

No se usa contenido protegido como recurso de prueba. `tests/fixtures/m3u/demo-authorized.m3u` contiene streams de demostración publicados para probar reproductores (películas abiertas de Blender Foundation, CC BY, y ejemplos HLS de Mux y Apple). **Verifica que las URLs siguen activas**: no se pudieron comprobar desde el entorno de la entrega.

1. Sirve el archivo (`python3 -m http.server` en `tests/fixtures/m3u`) o cópialo al dispositivo.
2. En la app: *Añadir fuente › Lista M3U (URL)* → `http://IP_DE_TU_PC:8000/demo-authorized.m3u` → *Probar conexión* (debe indicar 2 canales, 2 películas y 1 serie) → *Guardar*.
3. Comprueba: TV en vivo (HLS) carga y muestra el indicador; *Películas* reproduce MP4; *Series* muestra 1 temporada con 2 episodios.
4. Avanza una película a la mitad, sal: aparece en *Continuar viendo* con barra; reabrir ofrece *Continuar* y *Desde el principio*.
5. Cambio de canal: en TV con el mando, ▲/▼ con los controles ocultos o CH+/CH−; menú/guía abre la lista de canales.
6. Corta la red durante la reproducción en vivo: el reproductor reintenta (1 s, 2 s, 4 s) y, al volver la red, se reanuda.
7. Audio/subtítulos: usa el botón de ajustes del reproductor con un stream que tenga varias pistas.
8. Fuente de prueba Xtream: usa un servidor de pruebas propio o del proveedor con permiso; prueba también una contraseña incorrecta.

## Procedimiento manual: tamaños de pantalla y TV

Emuladores recomendados: Pixel 8 (teléfono), Pixel Tablet, Android TV 1080p (API 34) y un dispositivo con Android 7.0 (API 24, mínimo soportado). Verifica: navegación por foco con el mando en todas las pantallas, anillo de foco visible, menú lateral en TV, margen de 40 dp, teclado en formularios de fuentes, rotación en teléfono y reproductor en horizontal.

# EmbyVerse / EverseTV: contexto del proyecto (lo lee Claude al abrir este repositorio)

Este archivo es el "prompt" del proyecto. Con él, una sesión nueva retoma el trabajo como estaba.
Última actualización: 19 nov 2026 (Android 1.15.18 / versionCode 45 · TV y PC 1.2.10).

## Cómo trabajar con el dueño (reglas que NO se negocian)

- Responder siempre en **español de México**, claro y sin tecnicismos innecesarios.
- Ser honesto: decir qué se probó (emulador, navegador, CI) y qué NO (equipos reales).
- **Publicar a clientes (merge `beta` → `main`) SOLO cuando el dueño diga "publica"/"publícala".**
  Antes: probar en `beta` y mandar resultados y capturas.
  - Si pide publicar solo una parte, se arma una rama desde `main` con solo eso (p. ej. `rel`),
    se prueba con `gh workflow run <wf> --ref rel` y luego se une a `main`. Después se une `main` a `beta`.
- El dueño publica diseños desde el panel al mismo tiempo. Al unir ramas, en conflictos de
  `web-player/config/vendors/*.json` y `app.json` **conservar la versión de `main`** (la del dueño).
- Nunca pedir que pegue tokens o claves en el chat. Los secretos los pone él en GitHub → Settings → Secrets.
- La APK no lleva secretos. Nada para saltarse DRM. No copiar logos ni nombres de plataformas.
  No disfrazar funciones para pasar revisiones de tiendas.
- El correo del dueño es privado.
- En cada versión nueva de Android: subir `versionCode`/`versionName` en `android-app/app/build.gradle.kts`
  y agregar notas (es/en, sin el nombre de la app) en `android-app/app/src/main/assets/release-notes.json`.
  TV/PC: subir `version` en `tv-app/package.json`.

## Qué es

Reproductor IPTV/OTT (Xtream Codes y listas M3U) **marca blanca**: una app principal (EmbyVerse) y una por
vendedor (planetatv, streampro, allinone), cada una con su nombre, logo, colores y servidor.
No incluye contenido: cada cliente entra con su usuario/contraseña del proveedor.

| Parte | Carpeta | Tecnología | Sale en |
|---|---|---|---|
| App Android (celular, tableta, Android TV, Fire TV) | `android-app/` (`:app` + `:core`) | Kotlin, Compose, Media3 1.11.1, Room, WorkManager | release `apk-latest` + sitio |
| App Smart TV Samsung (Tizen) / LG (webOS) | `tv-app/` | React + TypeScript, esbuild | release `tv-latest` (.wgt / .ipk) |
| App PC Windows | `desktop/` (Electron, usa `tv-app` con `EDITION=pc`) | Electron | release `pc-latest` (instalador y portable) |
| Panel "Design Studio" | `admin-web/` → compila a `web-player/studio/` | React | eversetv.com/studio |
| Sitio de descarga y configs | `web-player/` → rama `gh-pages` | HTML | eversetv.com (y github.io) |
| Dominio propio | `site-worker/` (Cloudflare Worker `eversetv-site`) | Worker | eversetv.com, www |
| Estadísticas, sincronización de perfiles | `stats-worker/` (Worker + D1 `embyverse-stats`) | Worker + D1 | embyverse-stats.<cuenta>.workers.dev |
| API de ejemplo (no se usa en producción) | `backend/` | Node | — |

## Repositorios (separados para proteger el código)

- **`ositovela-a11y/EmbyVerse-codigo` (PRIVADO):** todo el código, ramas `main`/`beta`, flujos, versiones beta
  (`apk-beta`, `tv-beta`, `pc-beta`), `ci-logs`, FFmpeg y respaldos (con `CLAVES.txt` o `CLAVES-cifradas.zip`).
- **`ositovela-a11y/EmbyVerseTV` (PÚBLICO):** solo la rama `gh-pages` (sitio, panel, configs) y las descargas
  `apk-latest`, `pc-latest`, `tv-latest`. Las apps instaladas leen `ositovela-a11y.github.io/EmbyVerseTV`, por eso
  ese nombre no cambia. Los flujos publican ahí con el secreto `SITE_TOKEN` (`PUB` en los .yml).
- Mientras no exista `SITE_TOKEN`, los flujos publican en el mismo repositorio (antes de separar).

## Autorización del dueño (que nadie use la app sin permiso)

- Panel → editar app → «Autorización»: `auth.only`="1" (solo servidores autorizados: el del proveedor y los de
  `auth.hosts`; subdominios incluidos; archivos M3U sueltos no), `app.disabled`="1" (apaga la app a distancia).
  Apagado por defecto: lo enciende el dueño por app. `AppAuth.kt` (Android) y `config.ts` (TV/PC).
- Copias alteradas: `BuildConfig.SIGNING_SHA256` (huella de la firma, se calcula al compilar) vs. la firma real;
  si no coincide, la app no abre (`AppAuth.genuine`).

## Configuración publicada (lo que leen las apps)

- `web-player/config/app.json` (tu app) y `web-player/config/vendors/<slug>.json` (vendedores; `index.json` = lista).
  Textos clave en `texts`: `provider.host`, `login.otherServer`, `login.m3u`, `style.default`, `style.brandColors`,
  `support.message`, `license.*`, `blocked.users*` (clientes suspendidos), `season`, `event.*`.
- `web-player/config/licenses.json`: activaciones de PC/TV por equipo (huella del código).
- `web-player/config/blocked.json`: equipos/usuarios bloqueados desde Estadísticas (solo huellas sha256).
- `web-player/config/stats.json`: dirección del servidor de estadísticas.
- `web-player/config/downloader-codes.json`: códigos de la app Downloader por vendedor.
- Las APK traen la dirección de su config (`…/config/app.json` o `…/config/vendors/x.json`); las listas compartidas
  se buscan en la carpeta `config/` con `ConfigUrls.file()` (core). Las apps la releen cada 2 min.

## Automatizaciones (`.github/workflows/`)

| Archivo | Nombre en Actions | Qué hace |
|---|---|---|
| `android-emulator.yml` | Prueba en emulador Android | `SmokeFlowTest` (~37 pruebas, ~30 min) con `tests/android/mock-xtream.mjs`. En `beta` deja APK de revisión (`apk-beta`). Capturas y `resumen.txt` en la release `ci-logs` |
| `android-apk.yml` | APK Android | En `main`: compila las 4 APK (una por marca) y las deja en `apk-nueva` (esperando aprobación); luego dispara el sitio |
| `web.yml` | Sitio (descarga y panel) | Pasa a `*-latest` lo aprobado en `config/aprobadas.json` (`.github/scripts/versiones.py`), escribe `config/versiones.json` y publica `gh-pages` (página, panel, configs, APK, `update.json`). Luego renueva la copia de eversetv.com |
| `pc.yml` / `tv.yml` | App PC / App TV | Compilan, prueban (Electron/Chromium) y suben a `pc-nueva`/`tv-nueva` en main (`*-beta` en beta) |
| `site-worker.yml` | Dominio propio | Prueba siempre; despliega el Worker de eversetv.com SOLO en `main`, con copia de TODO el sitio + instaladores de PC en `copia/` (archivos >24 MB en partes `.parteNNN` + `.partes.json`) |
| `stats-worker.yml` | Servidor de estadísticas | Despliega el Worker + D1 y aplica `schema.sql` (en `main`) |
| `ffmpeg-audio.yml` / `ffmpeg-video.yml` | Decodificadores FFmpeg | AAR LGPL para Media3 1.11.1 (`ffmpeg-media3-1.11.1[-video]`): audio AC3/DTS… y video MPEG-2, Xvid/DivX, DivX 3, VC-1 |
| `respaldo.yml` | Respaldo | Cada semana (y a mano): copia código + apps + datos cifrados a la release `respaldo-AAAA-MM-DD` |

Secretos del repositorio del código (los pone el dueño): `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`, `STATS_ADMIN_KEY`,
`SITE_TOKEN` (publicar en el repositorio público) y `BACKUP_PASSWORD` (cifra las claves del respaldo).
`copiar-descargas.yml`: copia única de las descargas al repositorio público después de separar.
Ver capturas/registros de CI: `gh api -H "Accept: application/octet-stream" repos/ositovela-a11y/EmbyVerseTV/releases/assets/<id>`.

## Actualizaciones con aprobación (botón del dueño)

- Nada compilado en `main` llega solo a los clientes: queda en `apk-nueva`/`tv-nueva`/`pc-nueva` (repo del código).
- Panel → **Actualizaciones**: «Actualizar versión» por marca (Android) o para todas (TV y PC) escribe
  `web-player/config/aprobadas.json` (`{android: {"EmbyVerse-<slug>.apk": versionCode}, tv: "1.2.x", pc: "1.2.x"}`);
  `web.yml` copia lo aprobado a `*-latest` y regenera `update.json`. Así "publica" (merge a main) ≠ llegar a clientes.

## Fallos, vencimiento, respaldo de eversetv.com y privacidad

- Fallos: `StatsReporter.reportProblem` (Android: cierres por `CrashReport` + errores de formato/decodificador) y
  `stats.ts reportProblem` (TV/PC) → `stats-worker /v1/crash` (tabla `crashes`) → panel Estadísticas › Fallos.
- Vencimiento del proveedor (`exp_date`): Android banner en Inicio, TV/PC diálogo diario (`expiry.ts`).
  2 días antes (`expiry.days`=2); interruptor por app en el panel (`expiry.off`).
- Si GitHub falla: Android (`SiteMirror` en core + interceptor en `Network`) y TV/PC (`mirror.ts`) piden lo mismo
  a eversetv.com; el Worker sirve su copia (`env.ASSETS`) si GitHub no responde o da 404.
- Aviso de privacidad: `web-player/privacidad/` (eversetv.com/privacidad, es/en).
- LG: la app ya extiende sola el modo desarrollador (`lgdev.ts`).

## Detalles técnicos que importan

- **Firma de las APK:** `android-app/app/embyverse-debug.keystore` (en el repo a propósito). No cambiarla:
  los clientes tendrían que desinstalar.
- **Reproductor Android:** decodificador del equipo primero; si no puede, FFmpeg de la app (`WithSoftwareVideo`
  en `player/StreamSource.kt`). MPEG-4 parte 2 (Xvid/DivX) y DivX 3 van directo a FFmpeg (`SoftwareVideo.ALWAYS`).
  Si el decodificador del equipo falla, se reintenta con FFmpeg (`SoftwareVideo.markFailed`).
- **Fire TV:** las teclas del reproductor se atienden en `PlayerActivity.dispatchKeyEvent` sin depender del foco.
- **Bloqueo:** `DeviceBlock` (Android) y `device-block.ts` (TV/PC) leen `blocked.json`; huellas
  `sha256("blk-dev:"+id)` y `sha256("blk-user:"+app+"|"+host+"|"+usuario)` (16 hex).
- **Sincronización de perfiles:** `/v1/sync/<sha256 de ev-sync-v1|host|user|pass>` en stats-worker; misma lógica
  Kotlin (`AccountSync`) y TS (`account-sync.ts`).
- **Actualizar al volver:** Android `EmbyVerseApp` (ON_START, fuera ≥1 min → sincroniza listas de >2 min);
  TV/PC `main.tsx` (visibilitychange ≥1 min → `clearCache()` y recarga la pantalla).
- **Pruebas locales:** `node site-worker/test/site.test.mjs`, `node --test tv-app/tests/*.test.mjs`,
  `admin-web/test/*.mjs`, `cd tv-app && node tests/e2e.cjs <carpeta>` (con el mock en :8800 y `dist` en :8090).
  Android solo compila en CI (el entorno local no tiene SDK).
- **Ganchos de prueba (solo debug):** `PlaybackProbe.*` (hideMpeg2Decoder, hideSoftwareVideo, focusRescues…),
  `ConfigRepository.overrideForTest`, `DeviceBlock.testUrl`, `EmbyVerseApp.awayMs/RESUME_MIN_AGE_MS`.
- Errores intermitentes conocidos del emulador: "Failed to inject touch input" y la prueba de velocidad de
  carpetas (3 s): se vuelve a correr.

## Pendientes conocidos

- Microsoft Store: falta que el dueño reserve el nombre y pase los 3 datos de identidad para armar el MSIX.
- Recomendaciones en espera: guía de TV (EPG), PIN parental, firma nueva con rotación (después de separar repos),
  nombre final para tiendas. Correo público `soporte@eversetv.com` (falta que el dueño active Email Routing en Cloudflare).
- Separar repositorios (código privado). Hasta hacerlo, la clave de firma sigue en un repo público.

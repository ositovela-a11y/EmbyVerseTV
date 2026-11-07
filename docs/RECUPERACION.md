# EmbyVerse: recuperación rápida

Este documento es para levantar todo en el menor tiempo posible si algo falla: la app, el sitio de descarga, el panel o el repositorio. Empieza por la tabla, que te dice qué hacer según lo que veas. Si no alcanza, copia el **prompt de recuperación** del final en una conversación nueva con Claude.

---

## 1. Qué hacer según lo que ves (de lo más rápido a lo más lento)

| Lo que ves | Causa probable | Solución rápida | Tiempo |
|---|---|---|---|
| La app se cierra o se ve mal **justo después de publicar un diseño** en el panel | Diseño publicado con un problema | Panel → **Versiones** → restaurar la versión anterior → Publicar. Las apps la toman al abrirse. En el equipo afectado también sirve Ajustes → Acerca de → **Volver al diseño anterior** | 2 min |
| No cargan canales, películas ni series en **ningún** cliente | Cambió o se cayó el servidor del proveedor | Panel → Identidad → **Servidor del proveedor**: pon el nuevo y publica. Las apps se pasan solas y actualizan sus listas | 3 min |
| La app se cierra **después de instalar una versión nueva** | Error en esa versión de la APK | Volver a la APK anterior, ver sección 3 | 10 min |
| El enlace de descarga o el código de Downloader no abren | El sitio (GitHub Pages) no se publicó | GitHub → Actions → **"Sitio (descarga y panel)"** → Run workflow | 5 min |
| El panel no deja entrar | El token de GitHub venció | Crea un token nuevo (sección 4) y entra con él | 5 min |
| No llegan actualizaciones a las apps | No se generó `update.json` | Vuelve a correr "Sitio (descarga y panel)". Luego revisa que `update.json` en la rama `gh-pages` tenga el número de versión nuevo | 5 min |
| Un cliente suspendido ya pagó | Sigue en la lista de suspendidos | Panel → Temporada y eventos → Clientes suspendidos → **Reactivar** → Publicar | 2 min |
| Se perdió el repositorio o la cuenta de GitHub | — | Restaura desde tu respaldo (sección 5) y pega el prompt de recuperación | 30–60 min |

---

## 2. Dónde está cada cosa

- **Código:** GitHub `ositovela-a11y/EmbyVerseTV`. Tiene dos ramas:
  - `main`: lo que reciben tus clientes.
  - `beta`: versiones de revisión que todavía no se publican.
- **App Android (celular y TV):** `android-app/`. La versión está en `android-app/app/build.gradle.kts` (`versionCode` / `versionName`).
- **Textos de la app:** `scripts/i18n/strings.py`. Después de cambiarlo, ejecuta `python3 scripts/i18n/strings.py`.
- **Panel (Design Studio):** el código está en `admin-web/`. Se compila con `cd admin-web && node build.mjs --github` y queda en `web-player/studio/`.
- **Diseño publicado (lo que leen las apps):**
  - `web-player/config/app.json`: tu app.
  - `web-player/config/vendors/*.json`: una por vendedor.
- **Sitio de descarga, APK y `update.json`:** rama `gh-pages`, publicada en GitHub Pages. Si usas dominio propio, va en la variable del repositorio `CUSTOM_DOMAIN`.
- **Automatizaciones (GitHub Actions):**
  - `android-emulator.yml`: prueba en emulador en `main` y `beta`. En `beta` deja la APK de revisión en la release `apk-beta`.
  - `android-apk.yml`: en `main` compila la APK final y la sube a la release `apk-latest`.
  - `web.yml`: publica el sitio, las APK, el panel y `update.json`.
- **Firma de la APK:** `android-app/app/embyverse-debug.keystore`, que está en el repositorio a propósito. Con esa firma cada versión se instala encima de la anterior sin desinstalar. **No la borres ni la cambies:** si cambia la firma, los clientes tendrían que desinstalar y volver a instalar.
- **Servidor del proveedor por defecto:** `https://krnosmx.com:443`. Va en la APK y se puede cambiar desde el panel (texto `provider.host`).
- **La APK no guarda contraseñas de administrador ni claves secretas.** Las cuentas de los clientes solo se guardan en su propio equipo.

---

## 3. Volver a la versión anterior de la APK (cuando una versión nueva falla)

1. En GitHub → **Code → Commits** de la rama `main`, busca la última versión que funcionaba (los mensajes empiezan con el número, por ejemplo "1.10.0: …").
2. **Opción rápida, sin programar:** en GitHub → Actions → "APK Android", abre la última ejecución correcta de esa versión y vuelve a correrla con **Re-run all jobs**. Al terminar, corre "Sitio (descarga y panel)".
3. **Importante:** las apps solo se actualizan si el `versionCode` es **mayor** que el instalado. Para que los clientes que ya tienen la versión mala reciban la buena, hay que volver al código anterior **subiendo** el número de versión. El prompt de recuperación se lo pide a Claude.

---

## 4. Token nuevo para el panel

1. GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token.
2. Repositorio: solo `ositovela-a11y/EmbyVerseTV`. Permisos: **Contents: Read and write** y **Actions: Read and write**.
3. Copia el token y entra al panel con él. No lo compartas ni lo pongas en ningún archivo.

---

## 5. Respaldo (hazlo una vez al mes)

- En GitHub → Code → **Download ZIP** de la rama `main`, y guárdalo en tu computadora y en la nube.
- Descarga también la APK actual (`apk-latest`).
- Del panel guarda una **foto o nota** de lo importante: servidor, número de WhatsApp, lista de clientes suspendidos (los nombres solo se recuerdan en tu navegador) y vendedores.

---

## 6. Prompt de recuperación (cópialo completo en una conversación nueva con Claude)

```
Eres el desarrollador de mi app IPTV/OTT "EmbyVerse" (Android celular + Android TV, Kotlin/Compose,
listas M3U/Xtream; servidor por defecto https://krnosmx.com:443, el cliente solo pone usuario y contraseña).
Respóndeme en español (México). Sé honesto con lo que pruebas y lo que no.

Repositorio: github.com/ositovela-a11y/EmbyVerseTV (agrégalo a la sesión).
- Ramas: main = lo que reciben mis clientes; beta = versiones de revisión.
- Workflows: android-emulator.yml (prueba SmokeFlowTest en emulador; en beta deja APK de revisión en la
  release apk-beta), android-apk.yml (en main compila la APK firmada y la sube a apk-latest),
  web.yml (publica gh-pages: sitio, APK, panel en /studio y update.json para la actualización dentro de la app).
- Panel: admin-web (se compila con `cd admin-web && node build.mjs --github` → web-player/studio).
- Diseño publicado: web-player/config/app.json y vendors/*.json (lo lee la app; textos clave:
  provider.host, provider.askHost, provider.epgUrl, season, event.*, support.whatsapp, home.channels,
  style.default, blocked.users*).
- Textos de la app: scripts/i18n/strings.py → `python3 scripts/i18n/strings.py`.
- En CADA versión nueva agrega sus notas en android-app/app/src/main/assets/release-notes.json (sin el nombre
  de la app): se muestran al ofrecer la actualización y en «Novedades».
- Firma: android-app/app/embyverse-debug.keystore (NO cambiarla: los clientes tendrían que reinstalar).
- La APK no debe llevar secretos. Nada de saltarse DRM. Publica a mis clientes (merge beta → main)
  SOLO cuando yo diga "publícala"; antes, entrégame la APK de revisión de beta.

PROBLEMA ACTUAL: [describe aquí lo que pasa: qué ves, desde cuándo, en qué equipos, foto si tienes]

Haz esto en orden:
1. Clona el repo, revisa los últimos commits de main y las últimas ejecuciones de Actions (gh run list)
   para ver qué cambió y qué falló.
2. Si el problema es del diseño publicado, restaura el último app.json que funcionaba y publícalo.
3. Si es de la APK, regresa el código de la app a la última versión buena SUBIENDO el versionCode
   (para que update.json la ofrezca a todos), pásala por la prueba del emulador en beta y dime el resultado.
4. Si es del sitio o de update.json, vuelve a correr web.yml y verifica gh-pages (update.json con la versión correcta).
5. Dime en pocas palabras qué estaba mal, qué hiciste, qué probaste y qué falta probar en un equipo real.
```

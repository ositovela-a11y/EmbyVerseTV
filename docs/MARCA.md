# Cambiar nombre, logotipo y colores (marca blanca)

Guía para vender la app con otro nombre o diseño. Todos los archivos se pueden editar desde el navegador del teléfono en GitHub: abre el archivo, pulsa el lápiz (✏️ *Edit*), cambia el texto y pulsa *Commit changes*. GitHub vuelve a compilar el APK y a publicar la web automáticamente.

## 1. Nombre de la app

| Dónde se ve | Archivo | Qué cambiar |
|---|---|---|
| Nombre bajo el ícono (teléfono y TV) | `android-app/app/src/main/res/values/strings.xml` | `<string name="app_name" …>EmbyVerse</string>` |
| Textos dentro de la app (inicio, bienvenida) | `shared/default-config.json` **y** `android-app/app/src/main/assets/default_config.json` (iguales) | `"appName"` y `"tagline"` |
| Identificador único de la app | `android-app/app/build.gradle.kts` | `applicationId = "com.embyverse.app"` → por ejemplo `"com.tumarca.tv"` |
| Pestaña del navegador (web) | `web-player/index.html` | `<title>EmbyVerse Web</title>` |
| Web: textos de entrada | `web-player/app.js` | Busca `Tu universo de entretenimiento` |
| Nombre al instalar la web como app | `web-player/manifest.webmanifest` | `"name"` y `"short_name"` |

> Si cambias `applicationId`, Android la considera **otra app**: los clientes deberán instalarla de nuevo, no se actualizará encima de la anterior.

## 2. Logotipo e íconos

1. Sube tu logotipo (PNG cuadrado o similar, fondo negro u oscuro) a `assets/brand/embyverse-logo-original.png`, reemplazando el actual.
2. En una computadora con Node.js ejecuta `node scripts/generate-brand-assets.mjs`. Genera todos los tamaños: ícono del teléfono, ícono adaptable, banner de Android TV, logotipo de bienvenida y favicon de la web.
3. Sin computadora: reemplaza a mano estos archivos con imágenes del mismo tamaño:
   - Ícono: `android-app/app/src/main/res/mipmap-*/ic_launcher.webp` (48, 72, 96, 144, 192 px) y `drawable-nodpi/ic_launcher_foreground.webp` (432 px)
   - Banner de TV: `android-app/app/src/main/res/drawable-xhdpi/tv_banner.webp` (640 × 360 px)
   - Logo en la app: `android-app/app/src/main/res/drawable-nodpi/embyverse_logo.webp` y `embyverse_planet.webp`
   - Web: el logo va incrustado en `web-player/index.html` (atributo `src="data:image/webp;base64,…"` de `class="logo"`); cámbialo por `src="logo.webp"` y sube `web-player/logo.webp`. Ícono: `web-player/icon-192.png`.

## 3. Colores

| Parte | Archivo | Qué cambiar |
|---|---|---|
| App Android (todo el diseño) | `shared/default-config.json` + copia en `android-app/app/src/main/assets/default_config.json` | Bloque `"palette"`: `primary` (botones y acentos, verde), `accent` (cian), `secondary` (azul), `background`, `surface`… También `"background"` (degradado) |
| Pantalla de arranque de Android | `android-app/app/src/main/res/values/colors.xml` | `ev_background`, `ev_primary` |
| Web | `web-player/styles.css` | Bloque `:root` al principio: `--green`, `--cyan`, `--blue`, `--bg`, `--surface`… |

**Sin recompilar el APK:** si montas el panel *Design Studio* (carpeta `backend/` + `admin-web/`), puedes cambiar colores, logotipo aprobado, textos, banners y secciones del inicio desde el navegador. Las apps lo reciben al abrirse. Ver `docs/BUILD.md` › *Backend y panel*.

## 4. Firma propia para vender

El APK que se compila ahora usa una clave de **prueba** pública. Para distribuir comercialmente:

1. Crea tu clave privada (`keytool`, ver `docs/BUILD.md` › *APK de producción firmado*) y guárdala fuera de GitHub con copia de seguridad.
2. Guárdala como *secret* del repositorio (Settings › Secrets and variables › Actions) para que GitHub firme el APK de producción. Esta parte del flujo de compilación aún no está configurada; pídela cuando tengas la clave.

## 5. Antes de vender: puntos legales

- **El nombre.** «Emby» es el nombre comercial de un servidor multimedia de la empresa Emby LLC. Usar «EmbyVerse» comercialmente podría generar un conflicto de marca. Antes de vender, consulta la base de marcas del IMPI (México) y de los países donde vendas, o con un abogado de propiedad intelectual. Cambiar de nombre es más barato antes de lanzar que después.
- **El producto es el reproductor, no el contenido.** Vender una app que reproduce listas que el cliente ya tiene es legal en la mayoría de países. Incluir, vender o preconfigurar listas o accesos a canales sin licencia no lo es.
- **Tipografías incluidas** (Inter y Poppins): licencia SIL OFL 1.1, permite uso comercial; no las vendas por separado (`assets/fonts/`).
- **Política de privacidad:** completa `docs/PRIVACY.md` con tu nombre y contacto; Google Play la exige.

# Panel de diseño (Design Studio) — gratis, sin servidor

Dirección: **https://ositovela-a11y.github.io/EmbyVerseTV/studio/**  (PC o tableta; en celular también funciona en horizontal)

## Primera vez: entrar (2 minutos)
1. Abre https://github.com/settings/personal-access-tokens/new (con tu cuenta de GitHub).
2. **Token name:** `Design Studio`. **Expiration:** la que prefieras (por ejemplo 1 año).
3. **Repository access** → *Only select repositories* → **EmbyVerseTV**.
4. **Permissions → Repository permissions → Contents** → **Read and write**.
5. **Generate token**, cópialo y pégalo en el panel. Se guarda solo en ese navegador.

## Qué puedes cambiar
| Sección | Qué cambia | Cuándo se ve |
|---|---|---|
| Identidad | Nombre, lema, **logotipo** (botón «Subir mi logotipo»), tipografía, fondo | App y web al abrirlas (1–2 min). **Nombre e ícono de la APK:** se genera una APK nueva (~10 min) |
| Colores y temas | Todos los colores y los temas que puede elegir el usuario | App y web al abrirlas |
| Inicio | Orden y tipo de filas del inicio | App al abrirla |
| Formas y menú | Bordes, tamaños, menú inferior/lateral/superior | App al abrirla |
| Banners | Anuncios propios (imagen, texto, botón, fechas de inicio y fin) | App al abrirla |
| Textos | Textos de bienvenida y mensajes | App al abrirla |
| Versiones | Historial; volver a una versión anterior | — |

Pulsa **Publicar…** para aplicar. La vista previa de la derecha muestra el resultado antes de publicar.

## Novedades en el inicio
El banner principal de la app y de la web muestra **automáticamente lo último que agregó tu proveedor** (películas y series), con su sinopsis e imagen; se actualiza solo cada vez que se carga la lista. Los banners que crees en el panel aparecen primero y después las novedades.

## Nombre y logotipo de la APK
Android no permite cambiar el nombre bajo el ícono ni el ícono sin instalar una APK nueva. Al publicar, GitHub compila una APK con tu nombre y logotipo (`scripts/apply-brand.mjs`) en el mismo enlace de siempre:
https://github.com/ositovela-a11y/EmbyVerseTV/releases/download/apk-latest/EmbyVerse.apk — tus clientes la actualizan con el mismo código de Downloader.

## Seguridad
- El token solo da acceso a ese repositorio y solo se envía a `api.github.com`. Puedes revocarlo cuando quieras en GitHub → Settings → Developer settings → Personal access tokens.
- Cada publicación queda como un commit («Diseño vN: …»), así que todo cambio se puede revisar o revertir.

Prueba automática: `tests/e2e/studio-github.e2e.mjs` (entrar, cambiar nombre y lema, subir logotipo, publicar, historial y la web aplicando el cambio) → `docs/test-logs/studio-github-e2e.txt`.

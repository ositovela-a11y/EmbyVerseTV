# Fuentes y proveedores

Cada tipo de fuente tiene un adaptador separado (`android-app/core/.../source/SourceAdapters.kt`). La app **no** asume que una URL sea compatible con cualquier protocolo: si un servidor no responde como Xtream, se informa y se sugiere añadirlo como lista M3U.

## Lista M3U / M3U8 (URL o archivo)

Analizador en streaming (`M3uParser`), probado con `tests/fixtures/m3u/`:

- `#EXTM3U` con `url-tvg` / `x-tvg-url` (guía EPG).
- `#EXTINF` con atributos entre comillas dobles, simples o sin comillas; comas dentro de valores y en el título.
- `tvg-id`, `tvg-name`, `tvg-logo`, `tvg-chno`, `group-title`, `catchup-days`, `tvg-type`.
- `#EXTGRP`, `#EXTVLCOPT:http-user-agent` y `http-referrer` (se envían al reproducir ese stream).
- URLs relativas resueltas contra la URL de la lista. Se descartan `file:`, `content:`, `javascript:`, `data:`.
- URLs duplicadas: se conserva la primera. BOM y CRLF tolerados. Límites: 300 000 entradas, 150 MB.
- Una playlist HLS de un solo stream (`#EXT-X-TARGETDURATION`, `#EXT-X-STREAM-INF`) o una página HTML se rechazan con un mensaje claro.

**Clasificación** (en orden): ruta estilo Xtream (`/movie/`, `/series/`, `/live/`) → `tvg-type` → extensión de VOD (`.mp4`, `.mkv`…) → patrón de episodio (`S01E02`, `1x02`, `Temporada 1 Episodio 2`) fuera de grupos de directo → palabras clave del grupo → por defecto, canal en vivo. Los episodios se agrupan en series y temporadas por el título.

## Servidores compatibles con Xtream Codes

`XtreamClient` usa `player_api.php`. Una fuente se considera compatible solo si la autenticación devuelve un objeto con `user_info`.

| Endpoint | Uso | Si falla |
|---|---|---|
| `player_api.php?username&password` | Probar conexión: `auth`, `status`, caducidad, conexiones, formatos permitidos | Credenciales incorrectas / cuenta no activa / no es Xtream |
| `action=get_live_categories`, `get_vod_categories`, `get_series_categories` | **Detección de capacidades** (respuesta JSON de tipo lista) | La sección se omite |
| `action=get_live_streams` | Canales | Aviso, el resto sigue |
| `action=get_vod_streams` / `get_vod_info&vod_id` | Películas / ficha (sinopsis, géneros, duración, fondo) bajo demanda | Aviso |
| `action=get_series` / `get_series_info&series_id` | Series / temporadas y episodios bajo demanda (`episodes` como objeto o lista) | Mensaje en la ficha |
| `action=get_short_epg&stream_id&limit` | Disponible en el cliente (títulos en Base64) | — |
| `xmltv.php` | Guía completa (gzip detectado automáticamente) | Sin guía |

URLs de reproducción: `/live/{u}/{p}/{id}.{ts|m3u8}` (según *Ajustes › Reproducción* y `allowed_output_formats`), `/movie/{u}/{p}/{id}.{ext}`, `/series/{u}/{p}/{id}.{ext}`. Usuario y contraseña se codifican en la ruta y nunca se guardan en la base de datos (se usan marcadores).

Puedes pegar en *Servidor* un enlace `get.php?username=…&password=…`: la app extrae servidor, usuario y contraseña.

**Errores clasificados:** URL no válida, credenciales incorrectas (incluye HTTP 401/403 y `auth: 0`), cuenta no activa, HTTP 404/5xx, tiempo de espera, red, no es M3U, no es Xtream, formato no reconocido, fuente vacía, lista demasiado grande.

## Guía EPG (XMLTV)

Prioridad: URL indicada por el usuario → `x-tvg-url` de la lista → `xmltv.php` del servidor Xtream. Se guarda la ventana de −2 h a +36 h y solo para los `tvg-id` de los canales de la fuente. Si no hay guía válida, la app funciona igual y lo indica en el panel del canal.

## No admitido (documentado, no simulado)

- Portales Stalker/MAG, APIs propietarias de proveedores concretos, Emby/Jellyfin/Plex: requerirían un adaptador nuevo que implemente `SourceAdapter`.
- Contenido con DRM: Media3 lo soporta con licencias válidas, pero las listas M3U no incluyen un mecanismo estándar para ello; la app muestra "contenido protegido con DRM" y no intenta eludirlo.
- `#KODIPROP` y extensiones específicas de Kodi: se ignoran.
- Catch-up/timeshift: se lee `tv_archive`/`catchup-days` pero la reproducción en diferido no está implementada.
- Selector de archivos en algunos TV Box: si el sistema no tiene uno, la app lo indica y propone usar URL.

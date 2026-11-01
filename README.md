# Sitio de EmbyVerse (GitHub Pages)

Ya no incluye reproductor web: la reproducción es solo en la app Android (los navegadores bloquean los canales de la mayoría de proveedores IPTV). Esta carpeta publica:

| Ruta | Para qué |
|---|---|
| `index.html` | Página de descarga (con `?v=<vendedor>` muestra la marca del vendedor) |
| `a/` | Enlace corto para Downloader: baja la APK más reciente (`a/?v=<vendedor>` la del vendedor) |
| `studio/` | Panel de diseño (Design Studio) — se genera con `cd admin-web && node build.mjs --github` |
| `config/` | Diseños publicados que leen las apps: `app.json`, `vendors/<id>.json`, `assets/` |

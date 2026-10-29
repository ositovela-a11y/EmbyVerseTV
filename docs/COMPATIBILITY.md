# Compatibilidad y limitaciones

## Plataformas

| | Valor |
|---|---|
| Android mínimo | 7.0 (API 24) — muchos TV Box económicos usan 7.1 |
| Android objetivo | API 36; compilado contra API 37 |
| Dispositivos | Teléfonos, tabletas, Android TV / Google TV / TV Box con mando (navegación por foco, banner de TV, `LEANBACK_LAUNCHER`) |
| Navegación | Táctil y D-pad. En TV y pantallas ≥ 840 dp, menú lateral |
| Idiomas | Español (predeterminado) e inglés; selector por app (Android 13+ también desde Ajustes del sistema) |
| Panel web | Navegadores actuales (Chrome/Edge/Firefox/Safari de los últimos 2 años); adaptado a escritorio, tableta y teléfono |
| Backend | Node.js ≥ 22.13 en Linux/macOS/Windows o contenedor |

## Formatos de reproducción

Media3 1.11 incluye: HLS, DASH, MPEG-TS, MP4/fMP4, Matroska/WebM y otros contenedores progresivos. Los códecs dependen de los decodificadores del dispositivo (H.264/AAC prácticamente universales; HEVC, AV1, AC-3/E-AC-3 y DTS varían según el hardware). RTMP/RTSP/UDP se aceptan en listas pero **no** se incluyeron sus extensiones de Media3, por lo que esos streams fallarán con "formato no compatible".

**Ningún formato se ha probado aún en dispositivo**: la tabla anterior describe lo que la versión de Media3 declara, no resultados medidos. Usa el procedimiento de `TESTING.md` y anota aquí los resultados reales.

## Limitaciones conocidas

- El APK no se compiló en esta entrega (ver BUILD.md).
- Catálogos muy grandes: las listas se muestran hasta 5 000 elementos por categoría y la búsqueda devuelve 60 por tipo. Para catálogos mayores conviene paginar (Paging 3).
- EPG: guía por canal (ahora y próximos) en lugar de una rejilla completa de programación; sin grabación ni catch-up.
- Sin reproducción en segundo plano, Picture-in-Picture ni Chromecast.
- Perfiles locales por dispositivo; no hay cuentas de usuario final en la nube ni sincronización entre dispositivos.
- Sin DRM (ver PROVIDERS.md).
- Tipografías disponibles: Poppins, Inter y la del sistema (incluidas en la app; se pueden añadir más con una actualización).
- El backend usa SQLite: un solo proceso. Para varias réplicas, migrar a PostgreSQL.

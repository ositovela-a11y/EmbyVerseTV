# EmbyVerse Web

Reproductor web de listas M3U/M3U8 y servidores Xtream. No necesita servidor: se publica como archivos estáticos (GitHub Pages) y guarda todo en el navegador.

- Motores: hls.js 1.5.17 (HLS) y mpegts.js 1.7.3 (MPEG-TS), cargados desde jsDelivr; MP4/WebM con el reproductor nativo.
- Navegable con mando a distancia: flechas, OK, Atrás; CH+/CH− o RePág/AvPág cambian de canal.
- Limitaciones del navegador: en una página https no se pueden cargar listas ni canales http://, y el servidor del proveedor debe permitir CORS. Los .mkv/.avi no se reproducen en navegadores. Para esos casos, usa la app Android.
- Prueba: `tests/e2e/web-player.e2e.mjs` (11 pasos, registro en `docs/test-logs/web-player-e2e.txt`).

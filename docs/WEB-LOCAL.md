# EmbyVerse Web Local (para ver en navegador listas que la web en internet no puede abrir)

**Problema:** la web publicada (https://…github.io) no puede leer proveedores que usan `http://` o que no permiten el acceso desde páginas web (CORS). El navegador los bloquea y aparece «NetworkError» o «no permite reproducir desde una página web». Ninguna página web puede saltarse ese bloqueo por sí sola.

**Solución:** `EmbyVerse-Web-Local.exe` es la misma web dentro de un programa de un solo archivo que corre en tu PC. El programa descarga listas, API Xtream y video en nombre del navegador y se los entrega sin bloqueos. No instala nada.

Descarga: https://github.com/ositovela-a11y/EmbyVerseTV/releases/download/web-local/EmbyVerse-Web-Local.exe

## Uso
1. Doble clic en `EmbyVerse-Web-Local.exe`. Si Windows muestra «Windows protegió su PC»: *Más información → Ejecutar de todas formas* (el programa no está firmado con certificado comercial).
2. Si el firewall pregunta, marca **Redes privadas** y permite el acceso.
3. Se abre el navegador en `http://localhost:8080`. Entra con tu lista M3U o Xtream como siempre.
4. En la TV (misma red Wi‑Fi): en el navegador de Downloader escribe la dirección que muestra la ventana, por ejemplo `http://192.168.1.50:8080`.
5. Deja la ventana abierta mientras ves contenido. La PC debe estar encendida.

## Seguridad
- Solo atiende a equipos de tu red local (rechaza conexiones desde internet).
- No guarda nada; tus usuarios y contraseñas siguen solo en el navegador. En la consola se ocultan.
- Opciones: `-port 9000`, `-no-open`, `-user-agent "..."`.

## Código y pruebas
`local-server/main.go`; compila con `scripts/build-local-server.sh` (GitHub lo compila solo, flujo *EmbyVerse Web Local*). Prueba: `tests/e2e/web-local.e2e.mjs` con un proveedor sin CORS: sin el programa se bloquea, con el programa entra, reproduce un canal HLS en vivo y una película (`docs/test-logs/web-local-e2e.txt`).

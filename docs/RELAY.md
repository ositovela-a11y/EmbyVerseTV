# Intermediario en la nube (EmbyVerse Relay)

**Para qué sirve:** la web (https://ositovela-a11y.github.io/EmbyVerseTV/) no puede abrir proveedores que usan `http://` o que bloquean navegadores (CORS). Ninguna página web puede saltarse eso sola. El intermediario es un pequeño servicio gratuito en internet que descarga listas y video por la web y se los entrega al navegador. Con él, la web funciona en **iPhone, Android, PC y TV**, sin dejar ninguna PC encendida.

Se instala **una sola vez** y sirve para todos tus dispositivos.

## Opción A (recomendada): Deno Deploy, gratis, se entra con tu cuenta de GitHub
1. Abre https://console.deno.com/new?clone=https://github.com/ositovela-a11y/EmbyVerseTV&path=relay
2. Pulsa **Sign in with GitHub** y acepta.
3. Si te pide una organización, crea una con cualquier nombre (por ejemplo `embyverse`).
4. Deja que cree el repositorio y pulsa **Create App** / **Deploy**. Si te pregunta el *entrypoint*, escribe `main.ts`.
5. Al terminar verás una dirección del tipo `https://algo.deno.net`. Ábrela: debe mostrar `{"ok":true,"service":"EmbyVerse Relay"...}`.
6. Envíame esa dirección (o escríbela en la web: **Fuentes → Servidor intermediario → Guardar**).

Límites del plan gratuito (deno.com/pricing, oct-2026): 1 millón de peticiones y **20 GB de datos al mes**. Una película en HD consume 1–3 GB; los canales en vivo ~1–2 GB por hora. Para uso intensivo, la app Android no gasta nada de esto (se conecta directo al proveedor).

## Opción B: Cloudflare Workers (sin límite de datos en el plan gratuito)
Botón: https://deploy.workers.cloudflare.com/?url=https://github.com/ositovela-a11y/EmbyVerseTV/tree/main/relay

Limitación conocida: Cloudflare no permite conectarse a servidores que se identifican solo con una **IP** (por ejemplo `http://123.45.67.89:8080`). Si tu proveedor usa un nombre (`http://proveedor.com:8080`) puede funcionar; si usa IP, usa la opción A.

## Seguridad
- No guarda nada: ni listas, ni usuarios, ni contraseñas.
- Solo responde a la web de EmbyVerse (variable `ALLOWED_ORIGINS`) y no permite llegar a redes privadas.
- Opcional: define la variable `KEY` y añade `?k=TU_CLAVE` a la dirección del intermediario para que nadie más lo use.

## Para todos los dispositivos a la vez
Pon la dirección en `web-player/relay-config.js`:
```js
window.EV_RELAY = 'https://algo.deno.net';
```
Así cualquier navegador que abra la web ya lo usa sin configurar nada.

## Código y pruebas
`relay/worker.js` (mismo código en Deno y Cloudflare), `relay/main.ts` (Deno). Prueba: `tests/e2e/web-relay.e2e.mjs` con un proveedor sin CORS: sin intermediario se bloquea; con él entra, reproduce un canal HLS en vivo y una película con saltos (vista iPhone). Resultado: `docs/test-logs/web-relay-e2e.txt`.

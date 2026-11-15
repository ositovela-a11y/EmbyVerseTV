// Dominio propio delante del sitio: eversetv.com/… sirve ositovela-a11y.github.io/EmbyVerseTV/…
// y /descargas/pc/<archivo>.exe sirve el instalador de PC publicado en GitHub, sin mostrar esas direcciones.
// La dirección vieja de GitHub sigue funcionando igual (las apps ya instaladas no cambian nada).
export const ORIGIN = 'https://ositovela-a11y.github.io/EmbyVerseTV';
export const RELEASES = 'https://github.com/ositovela-a11y/EmbyVerseTV/releases/download/pc-latest/';
const PC_FILE = /^[A-Za-z][A-Za-z0-9]{1,30}-PC-(Instalador|Portable)\.exe$/;
const TEXT = /^(text\/|application\/(json|javascript|manifest\+json)|image\/svg)/;
const DROP = ['x-github-request-id', 'x-fastly-request-id', 'x-served-by', 'x-cache', 'x-cache-hits', 'x-timer', 'via', 'x-proxy-cache', 'server', 'set-cookie'];

const page = (status, title, msg) => new Response(
  `<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title>`
  + '<style>body{background:#02050C;color:#E6EEF8;font:18px system-ui,sans-serif;display:grid;place-content:center;min-height:100vh;text-align:center;padding:16px}a{color:#7BE52F}</style></head>'
  + `<body><h1>${title}</h1><p>${msg}</p><p><a href="/">Ir al inicio</a></p></body></html>`,
  { status, headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } });

function cleanHeaders(h) {
  const out = new Headers(h);
  for (const k of DROP) out.delete(k);
  out.set('x-content-type-options', 'nosniff');
  return out;
}

export default {
  async fetch(req) {
    const u = new URL(req.url);
    if (u.hostname.startsWith('www.')) return Response.redirect(`https://${u.hostname.slice(4)}${u.pathname}${u.search}`, 301);
    if (req.method !== 'GET' && req.method !== 'HEAD') return new Response('Método no permitido', { status: 405, headers: { allow: 'GET, HEAD' } });
    const site = `https://${u.host}`;

    // Instalador de PC: se entrega con tu dominio (GitHub queda oculto).
    const pc = /^\/descargas\/pc\/([^/]+)$/.exec(u.pathname);
    if (pc) {
      const name = decodeURIComponent(pc[1]);
      if (!PC_FILE.test(name)) return page(404, 'No encontrado', 'Ese archivo no existe.');
      const r = await fetch(RELEASES + name, { method: req.method, redirect: 'follow' });
      if (!r.ok) return page(r.status === 404 ? 404 : 502, 'Todavía no está lista', 'La app para PC se está preparando. Intenta de nuevo en unos minutos.');
      const h = new Headers();
      h.set('content-type', 'application/octet-stream');
      h.set('content-disposition', `attachment; filename="${name}"`);
      const len = r.headers.get('content-length'); if (len) h.set('content-length', len);
      h.set('cache-control', 'public, max-age=300');
      return new Response(req.method === 'HEAD' ? null : r.body, { status: 200, headers: h });
    }

    const r = await fetch(ORIGIN + u.pathname + u.search, {
      method: req.method, redirect: 'manual',
      headers: { 'accept': req.headers.get('accept') || '*/*', 'accept-encoding': req.headers.get('accept-encoding') || '', ...(req.headers.get('if-none-match') ? { 'if-none-match': req.headers.get('if-none-match') } : {}), ...(req.headers.get('range') ? { range: req.headers.get('range') } : {}) },
    });
    const h = cleanHeaders(r.headers);
    if (r.status >= 300 && r.status < 400) {
      const loc = h.get('location');
      if (loc) h.set('location', loc.replace(/^https?:\/\/ositovela-a11y\.github\.io\/EmbyVerseTV/, site));
      return new Response(null, { status: r.status, headers: h });
    }
    if (r.status === 404) return page(404, 'No encontrado', 'Esta página no existe.');
    const type = h.get('content-type') || '';
    if (req.method === 'GET' && r.ok && TEXT.test(type) && !h.get('content-range')) {
      // Por si alguna página trae la dirección completa de GitHub, se cambia por la tuya.
      const body = (await r.text()).split(ORIGIN).join(site);
      h.delete('content-length'); h.delete('content-encoding'); h.delete('etag');
      return new Response(body, { status: r.status, headers: h });
    }
    return new Response(r.body, { status: r.status, headers: h });
  },
};

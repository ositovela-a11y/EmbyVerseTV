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

// Páginas de descarga con marca: /?v=<vendedor>, /a/?v=… y /pc/?v=… salen desde el servidor ya con el
// nombre y el logo de esa marca (sin el logo de EmbyVerse un instante antes) y con vista previa propia
// al compartir el enlace (WhatsApp, Facebook, Telegram…). Sin ?v= es tu app principal.
const BRANDED = new Set(['/', '/index.html', '/a/', '/a/index.html', '/pc/', '/pc/index.html']);
const SLUG = /^[a-z][a-z0-9]{1,30}$/;
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

export async function brandFor(slug, site) {
  let c = null;
  try {
    const r = await fetch(`${ORIGIN}/config/${slug ? `vendors/${slug}` : 'app'}.json`, { headers: { accept: 'application/json' } });
    if (r.ok) c = await r.json();
  } catch { c = null; }
  const b = (c && c.brand) || {};
  if (slug && !c) return null; // vendedor inexistente: la página queda como está
  const name = (b.appName || (slug ? slug : 'EmbyVerse')).trim();
  const url = b.logo && b.logo.variant === 'asset' && /^https:\/\//.test(b.logo.url || '') ? b.logo.url.split(ORIGIN).join(site) : '';
  // La app principal usa su logo; un vendedor solo el suyo (si no subió logo, ninguno, nunca el de EmbyVerse).
  const logo = slug ? url : (url || `${site}/logo.png`);
  return { name, tagline: b.tagline || (slug ? '' : 'Tu universo de entretenimiento'), logo, own: !!url, slug };
}

export function applyBrand(html, br, path, site, search) {
  const title = path.startsWith('/pc/') ? `${br.name} — App para PC` : `${br.name} — Descargar`;
  const desc = br.tagline || `Descarga la app de ${br.name}`;
  const pageUrl = site + path + search;
  const meta = [
    `<meta name="description" content="${esc(desc)}">`,
    `<meta property="og:type" content="website">`,
    `<meta property="og:site_name" content="${esc(br.name)}">`,
    `<meta property="og:title" content="${esc(title)}">`,
    `<meta property="og:description" content="${esc(desc)}">`,
    `<meta property="og:url" content="${esc(pageUrl)}">`,
    ...(br.logo ? [`<meta property="og:image" content="${esc(br.logo)}">`, `<meta name="twitter:image" content="${esc(br.logo)}">`] : []),
    `<meta name="twitter:card" content="summary">`,
    `<meta name="twitter:title" content="${esc(title)}">`,
    `<meta name="twitter:description" content="${esc(desc)}">`,
    ...(br.logo ? [`<link rel="icon" href="${esc(br.logo)}">`, `<link rel="apple-touch-icon" href="${esc(br.logo)}">`] : []),
  ].join('');
  let out = html
    .replace(/<link rel="icon"[^>]*>/g, '')
    .replace(/<title>[^<]*<\/title>/, `<title>${esc(title)}</title>${meta}`)
    .replace(/<html lang="es"/, `<html lang="es" data-brand="${esc(br.slug || 'main')}"`);
  if (path === '/' || path === '/index.html') {
    out = out
      .replace(/<h1 id="name">[^<]*<\/h1>/, `<h1 id="name">${esc(br.name)}</h1>`)
      .replace(/<p id="tagline">[^<]*<\/p>/, `<p id="tagline">${esc(br.tagline)}</p>`)
      .replace(/<img class="logo"[^>]*>/, br.slug
        ? (br.own ? `<img class="logo" alt="${esc(br.name)}" src="${esc(br.logo)}">` : '')
        : (br.own ? `<img class="logo" alt="${esc(br.name)}" src="${esc(br.logo)}">` : (m) => m));
  }
  return out;
}

function cleanHeaders(h) {
  const out = new Headers(h);
  for (const k of DROP) out.delete(k);
  out.set('x-content-type-options', 'nosniff');
  return out;
}

// Copia propia de TODO el sitio dentro del Worker (diseño, listas, update.json, app de TV, APK e instaladores
// de PC): si GitHub falla o baja el sitio, eversetv.com sigue entregando todo. Se renueva cada vez que se publica
// el sitio (flujo «Dominio propio»). Los archivos grandes van en partes de 24 MB («<archivo>.partes.json»).
async function backup(env, u, method, extra) {
  if (!env || !env.ASSETS) return null;
  const get = (p) => env.ASSETS.fetch(new Request(`https://copia.local${p}`, { method: 'GET' }));
  try {
    let r = await get(u.pathname);
    let body; const h = new Headers();
    if (r.ok) { for (const [k, v] of r.headers) h.set(k, v); body = r.body; }
    else {
      const m = await get(u.pathname + '.partes.json');
      if (!m.ok) return null;
      const { size, partes } = await m.json();
      const ts = typeof FixedLengthStream === 'function' ? new FixedLengthStream(size) : new TransformStream();
      (async () => {
        const w = ts.writable;
        for (let i = 0; i < partes; i++) {
          const p = await get(`${u.pathname}.parte${String(i).padStart(3, '0')}`);
          if (!p.ok) throw new Error('falta una parte');
          await p.body.pipeTo(w, { preventClose: true });
        }
        await w.close();
      })().catch((e) => ts.writable.abort(e).catch(() => {}));
      body = ts.readable;
      h.set('content-type', 'application/octet-stream'); h.set('content-length', String(size));
    }
    h.set('x-eversetv-copia', '1'); h.set('cache-control', 'no-cache'); h.set('access-control-allow-origin', '*');
    if (/\.apk$/.test(u.pathname)) h.set('content-type', 'application/vnd.android.package-archive');
    for (const [k, v] of Object.entries(extra || {})) h.set(k, v);
    if (method === 'HEAD' && body && body.cancel) { body.cancel().catch(() => {}); body = null; }
    return new Response(body, { status: 200, headers: h });
  } catch { return null; }
}

export default {
  async fetch(req, env) {
    const u = new URL(req.url);
    if (u.hostname.startsWith('www.')) return Response.redirect(`https://${u.hostname.slice(4)}${u.pathname}${u.search}`, 301);
    if (req.method !== 'GET' && req.method !== 'HEAD') return new Response('Método no permitido', { status: 405, headers: { allow: 'GET, HEAD' } });
    const site = `https://${u.host}`;

    // Instalador de PC: se entrega con tu dominio (GitHub queda oculto).
    const pc = /^\/descargas\/pc\/([^/]+)$/.exec(u.pathname);
    if (pc) {
      const name = decodeURIComponent(pc[1]);
      if (!PC_FILE.test(name)) return page(404, 'No encontrado', 'Ese archivo no existe.');
      let r = null;
      try { r = await fetch(RELEASES + name, { method: req.method, redirect: 'follow' }); } catch { r = null; }
      if (!r || !r.ok) {
        // GitHub caído o sin el archivo: el instalador sale de la copia propia.
        const c = await backup(env, new URL(`https://x/descargas/pc/${name}`), req.method, { 'content-disposition': `attachment; filename="${name}"`, 'content-type': 'application/octet-stream' });
        if (c) return c;
        return page(r && r.status === 404 ? 404 : 502, 'Todavía no está lista', 'La app para PC se está preparando. Intenta de nuevo en unos minutos.');
      }
      const h = new Headers();
      h.set('content-type', 'application/octet-stream');
      h.set('content-disposition', `attachment; filename="${name}"`);
      const len = r.headers.get('content-length'); if (len) h.set('content-length', len);
      h.set('cache-control', 'public, max-age=300');
      return new Response(req.method === 'HEAD' ? null : r.body, { status: 200, headers: h });
    }

    let r;
    try {
      r = await fetch(ORIGIN + u.pathname + u.search, {
        method: req.method, redirect: 'manual',
        headers: { 'accept': req.headers.get('accept') || '*/*', 'accept-encoding': req.headers.get('accept-encoding') || '', ...(req.headers.get('if-none-match') ? { 'if-none-match': req.headers.get('if-none-match') } : {}), ...(req.headers.get('range') ? { range: req.headers.get('range') } : {}) },
      });
    } catch { r = null; }
    // GitHub caído, con error o sin el archivo: se usa la copia propia.
    if (!r || r.status >= 500 || r.status === 404) {
      const c = await backup(env, u, req.method);
      if (c) r = c;
      else if (!r) return page(503, 'Volvemos en un momento', 'El sitio no responde ahora. Intenta de nuevo en unos minutos.');
    }
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
      let body = (await r.text()).split(ORIGIN).join(site);
      if (BRANDED.has(u.pathname) && type.startsWith('text/html')) {
        const v = (u.searchParams.get('v') || '').toLowerCase();
        const br = await brandFor(SLUG.test(v) ? v : '', site);
        if (br) {
          body = applyBrand(body, br, u.pathname, site, u.search);
          // El nombre y logo cambian desde el panel: que no se quede guardada una versión vieja.
          h.set('cache-control', 'no-cache');
        }
      }
      h.delete('content-length'); h.delete('content-encoding'); h.delete('etag');
      return new Response(body, { status: r.status, headers: h });
    }
    return new Response(r.body, { status: r.status, headers: h });
  },
};

// EmbyVerse Relay — intermediario en la nube para que EmbyVerse Web reproduzca listas en CUALQUIER
// navegador (iPhone, Android, PC, TV) aunque el proveedor use http:// o no permita CORS.
//
// Funciona igual en Deno Deploy (relay/main.ts) y en Cloudflare Workers (este archivo).
//   GET /p?u=<url>[&k=<clave>]   descarga <url> y la entrega con CORS; reescribe listas HLS.
//   GET /health                   comprobación
// Variables opcionales:
//   KEY              clave de acceso (si se define, cada petición debe llevar &k=KEY)
//   ALLOWED_ORIGINS  orígenes web permitidos, separados por comas (por defecto la web publicada)
//   USER_AGENT       agente enviado al proveedor (por defecto, el de VLC)

const DEFAULT_ORIGINS = 'https://ositovela-a11y.github.io,http://localhost:8765,http://localhost:8080';
const HLS_TAG = /^#EXT-X-(TARGETDURATION|STREAM-INF|MEDIA-SEQUENCE|KEY|MAP|MEDIA|I-FRAME-STREAM-INF|PLAYLIST-TYPE)/m;

function cors(origin) {
  return {
    'access-control-allow-origin': origin || '*',
    'access-control-allow-headers': 'range',
    'access-control-expose-headers': 'content-length, content-range, content-type',
    vary: 'origin',
  };
}

// Nunca se permite usar el relay para llegar a redes privadas (protección SSRF).
function privateHost(host) {
  const h = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (h === 'localhost' || h.endsWith('.localhost') || h.endsWith('.local') || h.endsWith('.internal')) return true;
  const m = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(h);
  if (m) {
    const [a, b] = [+m[1], +m[2]];
    return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127);
  }
  return h === '::1' || h.startsWith('fc') || h.startsWith('fd') || h.startsWith('fe80');
}

export async function handle(request, env = {}) {
  const url = new URL(request.url);
  const origin = request.headers.get('origin');
  const allowed = (env.ALLOWED_ORIGINS || DEFAULT_ORIGINS).split(',').map((s) => s.trim()).filter(Boolean);
  const originOk = !origin || allowed.includes('*') || allowed.includes(origin);
  const h = cors(originOk ? origin : null);
  if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: { ...h, 'access-control-allow-methods': 'GET, HEAD, OPTIONS' } });
  if (url.pathname === '/' || url.pathname === '/health') {
    return new Response(JSON.stringify({ ok: true, service: 'EmbyVerse Relay', key: !!env.KEY }), { headers: { ...h, 'content-type': 'application/json' } });
  }
  if (url.pathname !== '/p') return new Response('No encontrado', { status: 404, headers: h });
  if (!originOk) return new Response('Origen no permitido', { status: 403, headers: h });
  if (env.KEY && url.searchParams.get('k') !== env.KEY) return new Response('Clave incorrecta', { status: 401, headers: h });
  if (request.method !== 'GET' && request.method !== 'HEAD') return new Response('Método no permitido', { status: 405, headers: h });

  let target;
  try { target = new URL(url.searchParams.get('u') || ''); } catch { return new Response('URL no válida', { status: 400, headers: h }); }
  if (!/^https?:$/.test(target.protocol) || (!env.ALLOW_PRIVATE && privateHost(target.hostname))) return new Response('URL no permitida', { status: 400, headers: h });

  const fwd = new Headers({ 'user-agent': env.USER_AGENT || 'VLC/3.0.21 LibVLC/3.0.21', accept: '*/*' });
  const isList = /\.m3u8?$/i.test(target.pathname);
  const range = request.headers.get('range');
  if (range && !isList) fwd.set('range', range);

  let resp;
  try {
    resp = await fetch(target.toString(), { headers: fwd, redirect: 'follow' });
  } catch (e) {
    return new Response('El proveedor no responde', { status: 502, headers: h });
  }
  const finalUrl = new URL(resp.url || target.toString());
  const ct = resp.headers.get('content-type') || '';
  const maybeHls = /mpegurl/i.test(ct) || /\.m3u8$/i.test(finalUrl.pathname);
  const out = new Headers(h);
  out.set('cache-control', 'no-store');

  if (maybeHls && resp.ok) {
    const text = await resp.text();
    if (HLS_TAG.test(text)) {
      const k = url.searchParams.get('k');
      const via = (ref) => {
        let abs; try { abs = new URL(ref.trim(), finalUrl).toString(); } catch { return ref; }
        return `${url.origin}/p?${k ? `k=${encodeURIComponent(k)}&` : ''}u=${encodeURIComponent(abs)}`;
      };
      const body = text.split(/\r?\n/).map((line) => {
        const t = line.trim();
        if (!t) return line;
        if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${via(u)}"`);
        return via(t);
      }).join('\n');
      out.set('content-type', 'application/vnd.apple.mpegurl');
      return new Response(body, { status: 200, headers: out });
    }
    out.set('content-type', ct || 'text/plain');
    return new Response(text, { status: resp.status, headers: out });
  }
  for (const k of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
    const v = resp.headers.get(k); if (v) out.set(k, v);
  }
  return new Response(request.method === 'HEAD' ? null : resp.body, { status: resp.status, headers: out });
}

export default { fetch: (request, env) => handle(request, env) };

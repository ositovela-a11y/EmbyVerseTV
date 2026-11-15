// Servidor de estadísticas: las apps avisan cuando se abren (/v1/ping), las páginas de descarga cuentan
// cada descarga (/v1/dl) y el panel lee todo con la clave ADMIN_KEY (/v1/stats). Sin contraseñas de nadie.
const CORS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET, POST, PUT, DELETE, OPTIONS',
  'access-control-allow-headers': 'content-type, authorization',
  'access-control-max-age': '86400',
};
const json = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...CORS } });
const MAX_SYNC = 400_000;
async function sha256(t) { const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode('ev-sync|' + t)); return [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, '0')).join(''); }
const clip = (v, n) => (typeof v === 'string' ? v.trim().slice(0, n) : '');
const SLUG = /^[a-z][a-z0-9]{1,30}$/;
const PLATFORMS = new Set(['android', 'androidtv', 'samsung', 'lg', 'pc', 'web', 'otro']);

function authorized(req, env) {
  const h = req.headers.get('authorization') || '';
  return !!env.ADMIN_KEY && h === `Bearer ${env.ADMIN_KEY}`;
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
    const country = clip(req.headers.get('cf-ipcountry') || '', 2) || '??';
    const now = Date.now();

    // La app avisa al abrirse (y una vez al día mientras se usa).
    if (url.pathname === '/v1/ping' && req.method === 'POST') {
      let b;
      try { b = await req.json(); } catch { return json({ ok: false, error: 'json' }, 400); }
      const id = clip(b.id, 64);
      if (!/^[A-Za-z0-9-]{8,64}$/.test(id)) return json({ ok: false, error: 'id' }, 400);
      const app = clip(b.app, 31).toLowerCase();
      if (app && !SLUG.test(app)) return json({ ok: false, error: 'app' }, 400);
      const platform = PLATFORMS.has(b.platform) ? b.platform : 'otro';
      await env.DB.prepare(
        `INSERT INTO devices (id, app, platform, model, version, xuser, host, country, first_seen, last_seen, opens)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?9, 1)
         ON CONFLICT(id) DO UPDATE SET app = excluded.app, platform = excluded.platform, model = excluded.model,
           version = excluded.version, xuser = COALESCE(NULLIF(excluded.xuser, ''), devices.xuser),
           host = COALESCE(NULLIF(excluded.host, ''), devices.host), country = excluded.country,
           last_seen = excluded.last_seen, opens = devices.opens + 1`,
      ).bind(id, app, platform, clip(b.model, 80), clip(b.version, 20), clip(b.user, 80), clip(b.host, 120), country, now).run();
      return json({ ok: true });
    }

    // Conteo de descargas desde las páginas de descarga (sin datos personales).
    if (url.pathname === '/v1/dl' && req.method === 'GET') {
      const app = clip(url.searchParams.get('app') || '', 31).toLowerCase();
      const platform = PLATFORMS.has(url.searchParams.get('platform')) ? url.searchParams.get('platform') : 'otro';
      if (!app || SLUG.test(app)) {
        const day = new Date(now).toISOString().slice(0, 10);
        await env.DB.prepare(
          `INSERT INTO downloads (day, app, platform, country, n) VALUES (?1, ?2, ?3, ?4, 1)
           ON CONFLICT(day, app, platform, country) DO UPDATE SET n = downloads.n + 1`,
        ).bind(day, SLUG.test(app) ? app : '', platform, country).run();
      }
      return new Response(null, { status: 204, headers: CORS });
    }

    // Panel: todo, con la clave del panel.
    if (url.pathname === '/v1/stats' && req.method === 'GET') {
      if (!authorized(req, env)) return json({ ok: false, error: 'clave' }, 401);
      const devices = await env.DB.prepare(
        `SELECT id, app, platform, model, version, xuser, host, country, first_seen, last_seen, opens
         FROM devices ORDER BY last_seen DESC LIMIT 50000`).all();
      const downloads = await env.DB.prepare(`SELECT day, app, platform, country, n FROM downloads ORDER BY day DESC LIMIT 20000`).all();
      return json({ ok: true, now, devices: devices.results, downloads: downloads.results });
    }

    // Panel: borrar un equipo del registro (pruebas o equipos que ya no existen).
    if (url.pathname.startsWith('/v1/devices/') && req.method === 'DELETE') {
      if (!authorized(req, env)) return json({ ok: false, error: 'clave' }, 401);
      await env.DB.prepare('DELETE FROM devices WHERE id = ?1').bind(decodeURIComponent(url.pathname.slice(12))).run();
      return json({ ok: true });
    }

    // Perfiles de la cuenta en todos sus equipos. La llave (64 hex) la calcula el equipo con servidor,
    // usuario y contraseña; aquí se guarda solo su huella (sha256), así ni la llave queda en la base.
    const sm = /^\/v1\/sync\/([a-f0-9]{64})$/.exec(url.pathname);
    if (sm) {
      const id = await sha256(sm[1]);
      if (req.method === 'GET') {
        const row = await env.DB.prepare('SELECT rev, data FROM sync WHERE id = ?1').bind(id).first();
        return json({ ok: true, rev: row ? row.rev : 0, data: row ? JSON.parse(row.data) : null });
      }
      if (req.method === 'PUT') {
        const text = await req.text();
        if (text.length > MAX_SYNC) return json({ ok: false, error: 'grande' }, 413);
        let b; try { b = JSON.parse(text); } catch { return json({ ok: false, error: 'json' }, 400); }
        if (!b || typeof b.rev !== 'number' || !b.data || typeof b.data !== 'object' || Array.isArray(b.data)) return json({ ok: false, error: 'datos' }, 400);
        const row = await env.DB.prepare('SELECT rev, data FROM sync WHERE id = ?1').bind(id).first();
        const cur = row ? row.rev : 0;
        // Otro equipo guardó antes: se devuelve lo actual para que el equipo lo combine y reintente.
        if (b.rev !== cur) return json({ ok: false, error: 'conflicto', rev: cur, data: row ? JSON.parse(row.data) : null }, 409);
        const data = JSON.stringify(b.data);
        if (row) await env.DB.prepare('UPDATE sync SET rev = ?2, data = ?3, updated = ?4 WHERE id = ?1 AND rev = ?5').bind(id, cur + 1, data, now, cur).run();
        else await env.DB.prepare('INSERT INTO sync (id, rev, data, updated) VALUES (?1, ?2, ?3, ?4)').bind(id, 1, data, now).run();
        return json({ ok: true, rev: cur + 1 });
      }
      return json({ ok: false, error: 'método' }, 405);
    }

    if (url.pathname === '/' || url.pathname === '/v1') return json({ ok: true, service: 'estadisticas' });
    return json({ ok: false, error: 'no existe' }, 404);
  },
};

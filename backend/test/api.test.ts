import assert from 'node:assert/strict';
import { after, before, describe, it } from 'node:test';
import { Client, PASSWORD, PUBLIC_BASE, defaults, png, startServer, userClient, type TestServer } from './helpers.js';

describe('API del panel y configuración remota', () => {
  let s: TestServer;
  let owner: Client, publisher: Client, editor: Client, viewer: Client;

  before(async () => {
    s = await startServer({ allowedImageHosts: ['images.partner.test'], withAdmin: true });
    owner = await userClient(s, 'owner');
    publisher = await userClient(s, 'publisher');
    editor = await userClient(s, 'editor');
    viewer = await userClient(s, 'viewer');
  });
  after(async () => { await s.close(); });

  it('salud, cabeceras de seguridad y nada publicado al inicio', async () => {
    const anon = new Client(s.base);
    const h = await anon.req('GET', '/healthz');
    assert.equal(h.status, 200);
    assert.equal(h.headers.get('x-content-type-options'), 'nosniff');
    assert.equal(h.headers.get('x-frame-options'), 'DENY');
    assert.match(h.headers.get('content-security-policy') ?? '', /frame-ancestors 'none'/);
    const c = await anon.req('GET', '/api/v1/public/config?platform=android&appVersion=1');
    assert.equal(c.status, 404);
    assert.equal(c.json.error, 'not_published');
  });

  it('autenticación: credenciales incorrectas, cookie segura y CSRF obligatorio', async () => {
    const c = new Client(s.base);
    const bad = await c.login('owner@embyverse.test', 'incorrecta');
    assert.equal(bad.status, 401);
    assert.equal(bad.json.message, 'Email o contraseña incorrectos');
    const unknown = await c.login('nadie@embyverse.test', PASSWORD);
    assert.equal(unknown.json.message, bad.json.message, 'no revela si el email existe');

    const raw = await fetch(s.base + '/api/v1/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ email: 'viewer@embyverse.test', password: PASSWORD }) });
    const cookie = raw.headers.get('set-cookie') ?? '';
    assert.match(cookie, /HttpOnly/); assert.match(cookie, /SameSite=Strict/);

    const noCsrf = await fetch(s.base + '/api/v1/config/draft', { method: 'PUT', headers: { cookie: owner.cookie, 'content-type': 'application/json' }, body: '{"config":{}}' });
    assert.equal(noCsrf.status, 403);
    assert.equal((await noCsrf.json()).error, 'csrf');

    assert.equal((await new Client(s.base).req('GET', '/api/v1/config/draft')).status, 401);
  });

  it('bloqueo temporal tras 5 intentos fallidos', async () => {
    s.app.auth.createUser('lock@embyverse.test', 'L', 'viewer', PASSWORD);
    const c = new Client(s.base);
    for (let i = 0; i < 5; i++) assert.equal((await c.login('lock@embyverse.test', 'mal')).status, 401);
    const locked = await c.login('lock@embyverse.test', PASSWORD);
    assert.equal(locked.status, 429);
  });

  it('permisos verificados en el servidor por rol', async () => {
    const d = await viewer.req('GET', '/api/v1/config/draft');
    assert.equal(d.status, 200);
    assert.equal((await viewer.req('PUT', '/api/v1/config/draft', { config: d.json.config })).status, 403);
    assert.equal((await editor.req('PUT', '/api/v1/config/draft', { config: d.json.config })).status, 200);
    assert.equal((await editor.req('POST', '/api/v1/config/publish', {})).status, 403);
    assert.equal((await editor.req('GET', '/api/v1/users')).status, 403);
    assert.equal((await viewer.req('GET', '/api/v1/audit')).status, 403);
    const me = await editor.req('GET', '/api/v1/auth/me');
    assert.deepEqual(me.json.user.permissions.sort(), ['assets:read', 'assets:write', 'config:edit', 'config:read']);
  });

  it('publicación de un cambio de diseño y entrega a la app con ETag', async () => {
    const cfg = structuredClone(defaults) as any;
    cfg.theme.palette.primary = '#22D3EE';
    cfg.home.sections = cfg.home.sections.filter((x: any) => x.id !== 'recommended');
    const saved = await editor.req('PUT', '/api/v1/config/draft', { config: cfg });
    assert.equal(saved.json.validation.ok, true);
    const pub = await publisher.req('POST', '/api/v1/config/publish', { note: 'Color cian' });
    assert.equal(pub.status, 201);
    assert.equal(pub.json.version, 1);

    const app = new Client(s.base);
    const r = await app.req('GET', '/api/v1/public/config?platform=android&appVersion=1');
    assert.equal(r.status, 200);
    assert.equal(r.json.version, 1);
    assert.equal(r.json.theme.palette.primary, '#22D3EE');
    assert.ok(!r.json.home.sections.some((x: any) => x.id === 'recommended'));
    const etag = r.headers.get('etag')!;
    const again = await fetch(s.base + '/api/v1/public/config?appVersion=1', { headers: { 'if-none-match': etag } });
    assert.equal(again.status, 304);
  });

  it('configuración inválida: se guarda el borrador pero no se publica', async () => {
    const cfg = structuredClone(defaults) as any;
    cfg.theme.palette.primary = 'verde';
    cfg.banners[0].action.route = 'https://evil.example.com';
    const saved = await editor.req('PUT', '/api/v1/config/draft', { config: cfg });
    assert.equal(saved.status, 200);
    assert.equal(saved.json.validation.ok, false);
    assert.equal(saved.json.validation.errors.length, 2);
    const pub = await publisher.req('POST', '/api/v1/config/publish', {});
    assert.equal(pub.status, 422);
    assert.equal((await new Client(s.base).req('GET', '/api/v1/public/config')).json.version, 1, 'la app sigue recibiendo la versión válida');
    await editor.req('DELETE', '/api/v1/config/draft');
  });

  it('biblioteca de imágenes: tipos reales, tamaño y banners publicados', async () => {
    const up = await editor.req('POST', '/api/v1/assets?kind=banner', undefined, { 'content-type': 'image/png', 'x-filename': 'promo.png' }, png(1280, 720));
    assert.equal(up.status, 201);
    assert.equal(up.json.asset.width, 1280);
    assert.ok(up.json.asset.url.startsWith(`${PUBLIC_BASE}/api/v1/public/assets/`));

    const svg = await editor.req('POST', '/api/v1/assets?kind=image', undefined, { 'content-type': 'image/svg+xml' }, Buffer.from('<svg onload="alert(1)"/>'));
    assert.equal(svg.status, 415);
    const big = await editor.req('POST', '/api/v1/assets?kind=image', undefined, { 'content-type': 'image/png' }, Buffer.alloc(3 * 1024 * 1024 + 10));
    assert.equal(big.status, 413);

    const file = up.json.asset.previewUrl as string;
    const img = await fetch(s.base + file);
    assert.equal(img.status, 200);
    assert.equal(img.headers.get('content-type'), 'image/png');
    assert.equal((await fetch(s.base + '/api/v1/public/assets/..%2F..%2Fetc%2Fpasswd')).status, 404);

    const draft = (await editor.req('GET', '/api/v1/config/draft')).json.config;
    draft.banners = [
      { id: 'promo1', placement: 'promo', title: 'Nuevo', imageUrl: up.json.asset.url, action: { route: 'movies' }, startsAt: null, endsAt: null },
      { id: 'externo', placement: 'promo', title: 'Externo', imageUrl: 'https://otro.example.com/x.png', action: { route: null } },
    ];
    const bad = await editor.req('PUT', '/api/v1/config/draft', { config: draft });
    assert.equal(bad.json.validation.ok, false);
    assert.match(bad.json.validation.assetErrors[0], /no está autorizado/);
    draft.banners[1].imageUrl = 'https://images.partner.test/x.png';
    assert.equal((await editor.req('PUT', '/api/v1/config/draft', { config: draft })).json.validation.ok, true);
    const pub = await publisher.req('POST', '/api/v1/config/publish', { note: 'Banners' });
    assert.equal(pub.json.version, 2);
    const live = await new Client(s.base).req('GET', '/api/v1/public/config');
    assert.equal(live.json.banners[0].imageUrl, up.json.asset.url);

    const del = await editor.req('DELETE', `/api/v1/assets/${up.json.asset.id}`);
    assert.equal(del.status, 409, 'no se puede borrar una imagen en uso');
  });

  it('logotipos: solo variantes aprobadas por un propietario', async () => {
    const up = await editor.req('POST', '/api/v1/assets?kind=logo', undefined, { 'content-type': 'image/png' }, png(512, 512));
    assert.equal(up.json.asset.approved, false);
    const draft = (await editor.req('GET', '/api/v1/config/draft')).json.config;
    draft.brand.logo = { variant: 'asset', url: up.json.asset.url };
    const r1 = await editor.req('PUT', '/api/v1/config/draft', { config: draft });
    assert.match(r1.json.validation.assetErrors.join(), /no está aprobado/);
    assert.equal((await editor.req('POST', `/api/v1/assets/${up.json.asset.id}/approve`, { approved: true })).status, 403);
    assert.equal((await owner.req('POST', `/api/v1/assets/${up.json.asset.id}/approve`, { approved: true })).status, 200);
    const r2 = await editor.req('PUT', '/api/v1/config/draft', { config: draft });
    assert.equal(r2.json.validation.ok, true);
    await editor.req('DELETE', '/api/v1/config/draft');
  });

  it('historial, recuperar borrador y revertir una publicación defectuosa', async () => {
    const versions = (await viewer.req('GET', '/api/v1/config/versions')).json.versions;
    assert.deepEqual(versions.map((v: any) => v.version), [2, 1]);
    assert.equal(versions[0].published_by, 'publisher@embyverse.test');

    const rb = await publisher.req('POST', '/api/v1/config/rollback', { toVersion: 1 });
    assert.equal(rb.status, 201);
    assert.equal(rb.json.version, 3);
    const live = await new Client(s.base).req('GET', '/api/v1/public/config');
    assert.equal(live.json.version, 3);
    assert.equal(live.json.banners.length, defaults.banners.length, 'contenido de la v1');

    const restore = await editor.req('POST', '/api/v1/config/versions/2/restore-draft');
    assert.equal(restore.status, 200);
    const draft = await editor.req('GET', '/api/v1/config/draft');
    assert.equal(draft.json.config.banners[0].id, 'promo1');
    await editor.req('DELETE', '/api/v1/config/draft');
  });

  it('conflicto si otro administrador publicó mientras se editaba', async () => {
    const d = (await editor.req('GET', '/api/v1/config/draft')).json;
    await editor.req('PUT', '/api/v1/config/draft', { config: d.config });
    // otro publicador revierte (crea v4) y borra el borrador; el editor vuelve a guardar sobre la base antigua
    await publisher.req('POST', '/api/v1/config/rollback', { toVersion: 2 });
    const stale = structuredClone(d.config);
    s.app.config.saveDraft(stale, 1, 3);
    const pub = await publisher.req('POST', '/api/v1/config/publish', {});
    assert.equal(pub.status, 409);
    assert.equal((await publisher.req('POST', '/api/v1/config/publish', { force: true })).status, 201);
  });

  it('apps antiguas reciben la última versión compatible', async () => {
    const d = (await editor.req('GET', '/api/v1/config/draft')).json.config;
    d.minAppVersionCode = 5;
    d.brand.appName = 'Solo app nueva';
    await editor.req('PUT', '/api/v1/config/draft', { config: d });
    const v = (await publisher.req('POST', '/api/v1/config/publish', {})).json.version;
    const oldApp = await new Client(s.base).req('GET', '/api/v1/public/config?appVersion=1');
    assert.notEqual(oldApp.json.version, v);
    assert.notEqual(oldApp.json.brand.appName, 'Solo app nueva');
    const newApp = await new Client(s.base).req('GET', '/api/v1/public/config?appVersion=7');
    assert.equal(newApp.json.version, v);
  });

  it('gestión de usuarios y registro de auditoría', async () => {
    const weak = await owner.req('POST', '/api/v1/users', { email: 'nuevo@embyverse.test', name: 'N', role: 'editor', password: 'corta' });
    assert.equal(weak.status, 422);
    const ok = await owner.req('POST', '/api/v1/users', { email: 'nuevo@embyverse.test', name: 'N', role: 'editor', password: PASSWORD });
    assert.equal(ok.status, 201);
    const me = (await owner.req('GET', '/api/v1/auth/me')).json.user;
    assert.equal((await owner.req('DELETE', `/api/v1/users/${me.id}`)).status, 409);
    assert.equal((await owner.req('PATCH', `/api/v1/users/${me.id}`, { role: 'viewer' })).status, 409, 'último propietario');

    const audit = (await owner.req('GET', '/api/v1/audit?limit=200')).json.entries.map((e: any) => e.action);
    for (const a of ['auth.login', 'auth.login_failed', 'config.published', 'config.rolled_back', 'asset.uploaded', 'asset.approved', 'user.created']) {
      assert.ok(audit.includes(a), `falta ${a} en auditoría`);
    }
  });

  it('cerrar sesión invalida la cookie', async () => {
    const c = await userClient(s, 'viewer', 'viewer2@embyverse.test');
    assert.equal((await c.req('POST', '/api/v1/auth/logout')).status, 200);
    c.cookie = c.cookie || '';
    assert.equal((await c.req('GET', '/api/v1/auth/me')).status, 401);
  });

  it('sirve el panel con respaldo SPA y no expone archivos fuera de la carpeta', async () => {
    const idx = await fetch(s.base + '/editor/inicio');
    assert.equal(idx.status, 200);
    assert.match(await idx.text(), /Panel/);
    assert.equal((await fetch(s.base + '/assets/app.js')).status, 200);
    assert.equal((await fetch(s.base + '/../../etc/passwd.txt')).status, 404);
    assert.equal((await fetch(s.base + '/api/v1/no-existe')).status, 404);
  });

  it('protección contra abuso: límite de peticiones a la API pública', async () => {
    let last = 0;
    for (let i = 0; i < 125; i++) last = (await fetch(s.base + '/api/v1/public/config')).status;
    assert.equal(last, 429);
  });
});

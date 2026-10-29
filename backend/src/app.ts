import { randomUUID } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import { extname, join, normalize, resolve } from 'node:path';
import { AssetStore } from './assets.js';
import { Auth, checkPasswordPolicy, hashPassword, permissionsFor, verifyPassword } from './auth.js';
import { ConfigService } from './config/service.js';
import type { RemoteConfig } from './config/schema.js';
import type { DB } from './db.js';
import { HttpError, RateLimiter, readBody, readJson, Router, sendJson, type Ctx, type Role } from './http.js';

export interface AppOptions {
  db: DB;
  dataDir: string;
  defaults: RemoteConfig;
  publicBaseUrl: string;
  secureCookies: boolean;
  trustProxy: boolean;
  allowedImageHosts?: string[];
  adminDist?: string | null;
  log?: (line: string) => void;
}

export interface App { server: Server; auth: Auth; config: ConfigService; assets: AssetStore }

const STATIC_TYPES: Record<string, string> = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.png': 'image/png', '.webp': 'image/webp', '.jpg': 'image/jpeg', '.svg': 'image/svg+xml', '.ico': 'image/x-icon',
  '.json': 'application/json', '.woff2': 'font/woff2', '.map': 'application/json',
};

const SECURITY_HEADERS: Record<string, string> = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=()',
  'Content-Security-Policy': "default-src 'self'; img-src 'self' https: data: blob:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; font-src 'self' data:; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
};

export function createApp(o: AppOptions): App {
  const log = o.log ?? ((l: string) => process.stdout.write(l + '\n'));
  const auth = new Auth(o.db, o.secureCookies);
  const assets = new AssetStore(o.db, o.dataDir, o.publicBaseUrl);
  const config = new ConfigService(o.db, o.defaults, assets, o.allowedImageHosts ?? []);
  const router = new Router();

  // Protección contra abuso
  const publicLimiter = new RateLimiter(120, 60_000);      // 120 peticiones/min por IP en la API pública
  const apiLimiter = new RateLimiter(600, 60_000);         // panel
  const loginIpLimiter = new RateLimiter(20, 15 * 60_000); // intentos de login por IP
  const loginUserLimiter = new RateLimiter(10, 15 * 60_000);

  const audit = (ctx: Ctx, action: string, target?: string | null, detail?: unknown) => {
    o.db.prepare('INSERT INTO audit (at, user_id, user_email, action, target, detail, ip) VALUES (?,?,?,?,?,?,?)')
      .run(Date.now(), ctx.user?.id ?? null, ctx.user?.email ?? null, action, target ?? null, detail === undefined ? null : JSON.stringify(detail).slice(0, 4000), ctx.ip);
  };
  const userJson = (u: { id: number; email: string; name: string; role: Role }) => ({ ...u, permissions: permissionsFor(u.role) });

  // ------------------------------------------------------------ salud
  router.get('/healthz', (ctx) => sendJson(ctx, 200, { ok: true, publishedVersion: config.latestVersion()?.version ?? 0 }));

  // ------------------------------------------------------------ API pública (la app Android)
  router.get('/api/v1/public/config', (ctx) => {
    const app = ctx.url.searchParams.get('appVersion');
    const appVersion = app && /^\d{1,9}$/.test(app) ? Number(app) : null;
    const row = config.latestFor(appVersion);
    if (!row) return sendJson(ctx, 404, { error: 'not_published', message: 'Todavía no hay ninguna configuración publicada' });
    const cache = { 'Cache-Control': 'public, max-age=60, must-revalidate', ETag: row.etag, Vary: 'Accept-Encoding' };
    if (ctx.req.headers['if-none-match'] === row.etag) { ctx.res.writeHead(304, cache); ctx.res.end(); return; }
    ctx.res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', ...cache });
    ctx.res.end(row.config_json);
  });

  router.get('/api/v1/public/assets/:file', (ctx) => {
    const found = assets.read(ctx.params.file ?? '');
    if (!found) return sendJson(ctx, 404, { error: 'not_found' });
    ctx.res.writeHead(200, { 'Content-Type': found.row.mime, 'Content-Length': String(found.data.length),
      'Cache-Control': 'public, max-age=31536000, immutable', 'Cross-Origin-Resource-Policy': 'cross-origin' });
    ctx.res.end(ctx.req.method === 'HEAD' ? undefined : found.data);
  });

  // ------------------------------------------------------------ sesión del panel
  router.post('/api/v1/auth/login', async (ctx) => {
    const body = await readJson<{ email?: string; password?: string }>(ctx, 4096);
    const email = String(body.email ?? '').toLowerCase().trim();
    if (!loginIpLimiter.check(ctx.ip) || !loginUserLimiter.check(email)) throw new HttpError(429, 'rate_limited', 'Demasiados intentos. Espera unos minutos.');
    try {
      const { token, csrf, user } = auth.login(email, String(body.password ?? ''), ctx.ip, String(ctx.req.headers['user-agent'] ?? ''));
      ctx.user = { id: user.id, email: user.email, name: user.name, role: user.role };
      audit(ctx, 'auth.login');
      sendJson(ctx, 200, { user: userJson(ctx.user), csrfToken: csrf }, { 'Set-Cookie': auth.cookie(token, 12 * 3600_000) });
    } catch (e) {
      audit(ctx, 'auth.login_failed', email.slice(0, 120));
      throw e;
    }
  });

  router.post('/api/v1/auth/logout', (ctx) => {
    auth.authenticate(ctx);
    audit(ctx, 'auth.logout');
    auth.logout(ctx);
    sendJson(ctx, 200, { ok: true }, { 'Set-Cookie': auth.clearCookie() });
  });

  router.get('/api/v1/auth/me', (ctx) => {
    auth.authenticate(ctx);
    sendJson(ctx, 200, { user: userJson(ctx.user!), csrfToken: ctx.session!.csrf });
  });

  router.post('/api/v1/auth/password', async (ctx) => {
    auth.authenticate(ctx);
    const b = await readJson<{ current?: string; next?: string }>(ctx, 4096);
    const row = o.db.prepare('SELECT password_hash FROM users WHERE id = ?').get(ctx.user!.id) as { password_hash: string };
    if (!verifyPassword(String(b.current ?? ''), row.password_hash)) throw new HttpError(403, 'invalid_credentials', 'La contraseña actual no es correcta');
    const policy = checkPasswordPolicy(String(b.next ?? ''));
    if (policy) throw new HttpError(422, 'weak_password', policy);
    o.db.prepare('UPDATE users SET password_hash = ?, password_changed_at = ? WHERE id = ?').run(hashPassword(String(b.next)), Date.now(), ctx.user!.id);
    auth.revokeUserSessions(ctx.user!.id, ctx.session!.idHash);
    audit(ctx, 'auth.password_changed');
    sendJson(ctx, 200, { ok: true });
  });

  // ------------------------------------------------------------ configuración visual
  router.get('/api/v1/config/draft', (ctx) => {
    auth.require(ctx, 'config:read');
    const d = config.getDraft();
    sendJson(ctx, 200, { ...d, latestVersion: config.latestVersion()?.version ?? 0, validation: config.validate(d.config), publicBaseUrl: o.publicBaseUrl });
  });

  router.put('/api/v1/config/draft', async (ctx) => {
    auth.require(ctx, 'config:edit');
    const b = await readJson<{ config?: unknown }>(ctx, 300 * 1024);
    const report = config.saveDraft(b.config, ctx.user!.id);
    audit(ctx, 'config.draft_saved', null, { ok: report.ok, errors: report.errors.length });
    sendJson(ctx, 200, { saved: true, validation: report });
  });

  router.delete('/api/v1/config/draft', (ctx) => {
    auth.require(ctx, 'config:edit');
    config.discardDraft();
    audit(ctx, 'config.draft_discarded');
    sendJson(ctx, 200, { ok: true });
  });

  router.post('/api/v1/config/validate', async (ctx) => {
    auth.require(ctx, 'config:read');
    const b = await readJson<{ config?: unknown }>(ctx, 300 * 1024);
    sendJson(ctx, 200, config.validate(b.config));
  });

  router.post('/api/v1/config/publish', async (ctx) => {
    auth.require(ctx, 'config:publish');
    const b = await readJson<{ note?: string; force?: boolean }>(ctx, 8192);
    const { version, report } = config.publish(ctx.user!.id, b.note ? String(b.note) : null, b.force === true);
    audit(ctx, 'config.published', `v${version}`, { note: b.note ?? null, warnings: report.warnings });
    sendJson(ctx, 201, { version, warnings: report.warnings });
  });

  router.get('/api/v1/config/versions', (ctx) => {
    auth.require(ctx, 'config:read');
    sendJson(ctx, 200, { versions: config.listVersions() });
  });

  router.get('/api/v1/config/versions/:v', (ctx) => {
    auth.require(ctx, 'config:read');
    const row = config.getVersion(Number(ctx.params.v));
    sendJson(ctx, 200, { version: row.version, config: JSON.parse(row.config_json), publishedAt: row.published_at, note: row.note });
  });

  router.post('/api/v1/config/versions/:v/restore-draft', (ctx) => {
    auth.require(ctx, 'config:edit');
    const row = config.getVersion(Number(ctx.params.v));
    const report = config.saveDraft(JSON.parse(row.config_json), ctx.user!.id, config.latestVersion()?.version ?? 0);
    audit(ctx, 'config.draft_restored', `v${row.version}`);
    sendJson(ctx, 200, { ok: true, validation: report });
  });

  router.post('/api/v1/config/rollback', async (ctx) => {
    auth.require(ctx, 'config:publish');
    const b = await readJson<{ toVersion?: number; note?: string }>(ctx, 4096);
    if (!Number.isInteger(b.toVersion)) throw new HttpError(422, 'invalid_version', 'Indica toVersion');
    const version = config.rollback(b.toVersion!, ctx.user!.id, b.note ? String(b.note) : null);
    audit(ctx, 'config.rolled_back', `v${version}`, { to: b.toVersion });
    sendJson(ctx, 201, { version });
  });

  // ------------------------------------------------------------ biblioteca de imágenes
  router.get('/api/v1/assets', (ctx) => {
    auth.require(ctx, 'assets:read');
    sendJson(ctx, 200, { assets: assets.list().map((a) => ({ ...a, approved: !!a.approved, url: assets.publicUrl(a), previewUrl: `/api/v1/public/assets/${assets.fileName(a)}` })) });
  });

  router.post('/api/v1/assets', async (ctx) => {
    auth.require(ctx, 'assets:write');
    const kind = ctx.url.searchParams.get('kind') ?? 'image';
    if (!['image', 'banner', 'logo'].includes(kind)) throw new HttpError(422, 'invalid_kind', 'Tipo no válido');
    const name = decodeURIComponent(String(ctx.req.headers['x-filename'] ?? 'imagen'));
    const buf = await readBody(ctx.req, 3 * 1024 * 1024 + 1);
    const row = assets.save(buf, name, kind as 'image' | 'banner' | 'logo', ctx.user!.id);
    audit(ctx, 'asset.uploaded', row.id, { kind, size: row.size, filename: row.filename });
    sendJson(ctx, 201, { asset: { ...row, approved: !!row.approved, url: assets.publicUrl(row), previewUrl: `/api/v1/public/assets/${assets.fileName(row)}` } });
  });

  router.post('/api/v1/assets/:id/approve', async (ctx) => {
    auth.require(ctx, 'assets:approve');
    const b = await readJson<{ approved?: boolean }>(ctx, 1024);
    assets.approve(ctx.params.id ?? '', b.approved !== false);
    audit(ctx, b.approved === false ? 'asset.unapproved' : 'asset.approved', ctx.params.id);
    sendJson(ctx, 200, { ok: true });
  });

  router.delete('/api/v1/assets/:id', (ctx) => {
    auth.require(ctx, 'assets:write');
    const a = assets.get(ctx.params.id ?? '');
    if (!a) throw new HttpError(404, 'not_found', 'Recurso no encontrado');
    if (config.assetInUse(assets.publicUrl(a))) throw new HttpError(409, 'asset_in_use', 'La imagen se usa en el borrador o en la versión publicada');
    assets.delete(a.id);
    audit(ctx, 'asset.deleted', a.id);
    sendJson(ctx, 200, { ok: true });
  });

  // ------------------------------------------------------------ auditoría y usuarios
  router.get('/api/v1/audit', (ctx) => {
    auth.require(ctx, 'audit:read');
    const limit = Math.min(500, Math.max(1, Number(ctx.url.searchParams.get('limit') ?? 100)));
    sendJson(ctx, 200, { entries: o.db.prepare('SELECT id, at, user_email, action, target, detail, ip FROM audit ORDER BY id DESC LIMIT ?').all(limit) });
  });

  router.get('/api/v1/users', (ctx) => {
    auth.require(ctx, 'users:manage');
    sendJson(ctx, 200, { users: o.db.prepare('SELECT id, email, name, role, disabled, created_at, locked_until FROM users ORDER BY id').all() });
  });

  router.post('/api/v1/users', async (ctx) => {
    auth.require(ctx, 'users:manage');
    const b = await readJson<{ email?: string; name?: string; role?: Role; password?: string }>(ctx, 4096);
    const id = auth.createUser(String(b.email ?? ''), String(b.name ?? ''), (b.role ?? 'viewer') as Role, String(b.password ?? ''));
    audit(ctx, 'user.created', String(id), { email: b.email, role: b.role });
    sendJson(ctx, 201, { id });
  });

  const ownerCount = () => (o.db.prepare("SELECT COUNT(*) AS n FROM users WHERE role = 'owner' AND disabled = 0").get() as { n: number }).n;

  router.patch('/api/v1/users/:id', async (ctx) => {
    auth.require(ctx, 'users:manage');
    const id = Number(ctx.params.id);
    const target = o.db.prepare('SELECT id, role, disabled FROM users WHERE id = ?').get(id) as { id: number; role: Role; disabled: number } | undefined;
    if (!target) throw new HttpError(404, 'not_found', 'Usuario no encontrado');
    const b = await readJson<{ name?: string; role?: Role; disabled?: boolean; password?: string }>(ctx, 4096);
    const demoting = target.role === 'owner' && ((b.role && b.role !== 'owner') || b.disabled === true);
    if (demoting && ownerCount() <= 1) throw new HttpError(409, 'last_owner', 'Debe quedar al menos un propietario activo');
    if (b.role && !['owner', 'publisher', 'editor', 'viewer'].includes(b.role)) throw new HttpError(422, 'invalid_role', 'Rol no válido');
    if (b.name !== undefined) o.db.prepare('UPDATE users SET name = ? WHERE id = ?').run(String(b.name).slice(0, 80), id);
    if (b.role) o.db.prepare('UPDATE users SET role = ? WHERE id = ?').run(b.role, id);
    if (b.disabled !== undefined) { o.db.prepare('UPDATE users SET disabled = ? WHERE id = ?').run(b.disabled ? 1 : 0, id); if (b.disabled) auth.revokeUserSessions(id); }
    if (b.password !== undefined) {
      const policy = checkPasswordPolicy(String(b.password));
      if (policy) throw new HttpError(422, 'weak_password', policy);
      o.db.prepare('UPDATE users SET password_hash = ?, password_changed_at = ?, failed_logins = 0, locked_until = NULL WHERE id = ?').run(hashPassword(String(b.password)), Date.now(), id);
      auth.revokeUserSessions(id);
    }
    if (b.role) auth.revokeUserSessions(id); // los permisos nuevos se aplican en la siguiente sesión
    audit(ctx, 'user.updated', String(id), { role: b.role, disabled: b.disabled, passwordReset: b.password !== undefined });
    sendJson(ctx, 200, { ok: true });
  });

  router.delete('/api/v1/users/:id', (ctx) => {
    auth.require(ctx, 'users:manage');
    const id = Number(ctx.params.id);
    if (id === ctx.user!.id) throw new HttpError(409, 'self_delete', 'No puedes eliminar tu propia cuenta');
    const target = o.db.prepare('SELECT role FROM users WHERE id = ?').get(id) as { role: Role } | undefined;
    if (!target) throw new HttpError(404, 'not_found', 'Usuario no encontrado');
    if (target.role === 'owner' && ownerCount() <= 1) throw new HttpError(409, 'last_owner', 'Debe quedar al menos un propietario activo');
    o.db.prepare('DELETE FROM users WHERE id = ?').run(id);
    audit(ctx, 'user.deleted', String(id));
    sendJson(ctx, 200, { ok: true });
  });

  // ------------------------------------------------------------ panel web estático
  const adminDir = o.adminDist && existsSync(o.adminDist) ? resolve(o.adminDist) : null;
  const serveStatic = (ctx: Ctx): boolean => {
    if (!adminDir || !['GET', 'HEAD'].includes(ctx.req.method ?? '')) return false;
    let p = normalize(decodeURIComponent(ctx.url.pathname)).replace(/^(\.\.[/\\])+/, '');
    if (p === '/' || p === '') p = '/index.html';
    let file = join(adminDir, p);
    if (!file.startsWith(adminDir)) return false;
    if (!existsSync(file) || !statSync(file).isFile()) {
      if (extname(p)) return false;
      file = join(adminDir, 'index.html'); // SPA
    }
    const type = STATIC_TYPES[extname(file)] ?? 'application/octet-stream';
    const isHtml = file.endsWith('.html');
    ctx.res.writeHead(200, { 'Content-Type': type, 'Cache-Control': isHtml ? 'no-cache' : 'public, max-age=3600' });
    ctx.res.end(ctx.req.method === 'HEAD' ? undefined : readFileSync(file));
    return true;
  };

  // ------------------------------------------------------------ servidor
  const handle = async (req: IncomingMessage, res: ServerResponse) => {
    const started = Date.now();
    const forwarded = o.trustProxy ? String(req.headers['x-forwarded-for'] ?? '').split(',')[0]?.trim() : '';
    const ip = forwarded || req.socket.remoteAddress || 'unknown';
    const ctx: Ctx = { req, res, url: new URL(req.url ?? '/', 'http://localhost'), params: {}, ip, requestId: randomUUID() };
    for (const [k, v] of Object.entries(SECURITY_HEADERS)) res.setHeader(k, v);
    res.setHeader('X-Request-Id', ctx.requestId);
    const https = o.trustProxy ? req.headers['x-forwarded-proto'] === 'https' : false;
    if (https || o.secureCookies) res.setHeader('Strict-Transport-Security', 'max-age=31536000; includeSubDomains');
    try {
      const path = ctx.url.pathname;
      const isPublic = path.startsWith('/api/v1/public/') || path === '/healthz';
      if (path.startsWith('/api/')) {
        if (!(isPublic ? publicLimiter : apiLimiter).check(ip)) throw new HttpError(429, 'rate_limited', 'Demasiadas peticiones');
      }
      const m = ctx.url.pathname.length > 2048 ? null : router.match(req.method ?? 'GET', path);
      if (m === 'method') throw new HttpError(405, 'method_not_allowed', 'Método no permitido');
      if (m) { ctx.params = m.params; await m.handler(ctx); }
      else if (!path.startsWith('/api/') && serveStatic(ctx)) { /* servido */ }
      else throw new HttpError(404, 'not_found', 'No encontrado');
    } catch (e) {
      if (e instanceof HttpError) {
        if (!res.headersSent) sendJson(ctx, e.status, { error: e.code, message: e.message, details: e.details, requestId: ctx.requestId });
      } else {
        log(JSON.stringify({ level: 'error', requestId: ctx.requestId, error: String((e as Error)?.stack ?? e) }));
        if (!res.headersSent) sendJson(ctx, 500, { error: 'internal', message: 'Error interno', requestId: ctx.requestId });
        else res.end();
      }
    } finally {
      // Registro de acceso SIN query string (no contiene secretos, pero así se evita registrar parámetros).
      log(JSON.stringify({ t: new Date().toISOString(), id: ctx.requestId, ip, m: req.method, p: ctx.url.pathname, s: res.statusCode, ms: Date.now() - started, u: ctx.user?.email }));
    }
  };

  const server = createServer((req, res) => { void handle(req, res); });
  server.requestTimeout = 30_000;
  server.headersTimeout = 15_000;
  return { server, auth, config, assets };
}

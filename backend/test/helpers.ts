import { mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { deflateSync, crc32 } from 'node:zlib';
import type { AddressInfo } from 'node:net';
import { createApp, type App } from '../src/app.js';
import { openDb } from '../src/db.js';
import { backendRoot, loadDefaults } from '../src/env.js';
import type { Role } from '../src/http.js';

export const repoRoot = resolve(backendRoot, '..');
export const fixture = (p: string) => readFileSync(join(repoRoot, 'tests/fixtures', p), 'utf8');
export const defaults = loadDefaults(join(repoRoot, 'shared/default-config.json'));
export const PUBLIC_BASE = 'https://cdn.embyverse.test';

export interface TestServer { base: string; app: App; dir: string; close: () => Promise<void> }

export async function startServer(opts: { allowedImageHosts?: string[]; withAdmin?: boolean } = {}): Promise<TestServer> {
  const dir = mkdtempSync(join(tmpdir(), 'ev-test-'));
  let adminDist: string | null = null;
  if (opts.withAdmin) {
    adminDist = join(dir, 'admin');
    mkdirSync(join(adminDist, 'assets'), { recursive: true });
    writeFileSync(join(adminDist, 'index.html'), '<!doctype html><title>Panel</title>');
    writeFileSync(join(adminDist, 'assets', 'app.js'), 'console.log(1)');
  }
  const db = openDb(join(dir, 'test.db'));
  const app = createApp({ db, dataDir: dir, defaults, publicBaseUrl: PUBLIC_BASE, secureCookies: false, trustProxy: false,
    allowedImageHosts: opts.allowedImageHosts ?? [], adminDist, log: () => {} });
  await new Promise<void>((r) => app.server.listen(0, '127.0.0.1', r));
  const port = (app.server.address() as AddressInfo).port;
  return {
    base: `http://127.0.0.1:${port}`, app, dir,
    close: () => new Promise<void>((r) => app.server.close(() => { db.close(); rmSync(dir, { recursive: true, force: true }); r(); })),
  };
}

/** Cliente HTTP con cookie de sesión y token CSRF, como el panel. */
export class Client {
  cookie = '';
  csrf = '';
  constructor(private base: string) {}

  async req(method: string, path: string, body?: unknown, extra: Record<string, string> = {}, raw?: Buffer) {
    const headers: Record<string, string> = { ...extra };
    if (this.cookie) headers.cookie = this.cookie;
    if (this.csrf && method !== 'GET') headers['x-csrf-token'] = this.csrf;
    if (body !== undefined) headers['content-type'] = 'application/json';
    const res = await fetch(this.base + path, { method, headers, body: raw ? new Uint8Array(raw) : (body !== undefined ? JSON.stringify(body) : undefined) });
    const setCookie = res.headers.get('set-cookie');
    if (setCookie) this.cookie = setCookie.split(';')[0]!;
    const text = await res.text();
    let json: any = null;
    try { json = text ? JSON.parse(text) : null; } catch { json = text; }
    return { status: res.status, json, headers: res.headers };
  }

  async login(email: string, password: string) {
    const r = await this.req('POST', '/api/v1/auth/login', { email, password });
    if (r.status === 200) this.csrf = r.json.csrfToken;
    return r;
  }
}

export const PASSWORD = 'Contraseña-Segura-2026';

export async function userClient(s: TestServer, role: Role, email = `${role}@embyverse.test`): Promise<Client> {
  try { s.app.auth.createUser(email, role, role, PASSWORD); } catch { /* ya existe */ }
  const c = new Client(s.base);
  const r = await c.login(email, PASSWORD);
  if (r.status !== 200) throw new Error(`login ${role}: ${r.status}`);
  return c;
}

/** PNG válido mínimo generado en memoria (sin dependencias). */
export function png(width = 2, height = 1): Buffer {
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4); ihdr[8] = 8; ihdr[9] = 2;
  const rows = Buffer.alloc((width * 3 + 1) * height);
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}

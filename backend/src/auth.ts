import { createHash, randomBytes, scryptSync, timingSafeEqual } from 'node:crypto';
import type { DB } from './db.js';
import { HttpError, type Ctx, type Role } from './http.js';

/** Permisos verificados SIEMPRE en el servidor. El panel sólo oculta botones por comodidad. */
export const PERMISSIONS = {
  'config:read': ['owner', 'publisher', 'editor', 'viewer'],
  'config:edit': ['owner', 'publisher', 'editor'],
  'config:publish': ['owner', 'publisher'],
  'assets:read': ['owner', 'publisher', 'editor', 'viewer'],
  'assets:write': ['owner', 'publisher', 'editor'],
  'assets:approve': ['owner'],
  'audit:read': ['owner', 'publisher'],
  'users:manage': ['owner'],
} as const satisfies Record<string, readonly Role[]>;
export type Permission = keyof typeof PERMISSIONS;

export function can(role: Role, p: Permission): boolean { return (PERMISSIONS[p] as readonly Role[]).includes(role); }
export function permissionsFor(role: Role): Permission[] { return (Object.keys(PERMISSIONS) as Permission[]).filter((p) => can(role, p)); }

// ------------------------------------------------------------------ contraseñas
const N = 16384, R = 8, P = 1, KEYLEN = 64;
export const MIN_PASSWORD = 12;

export function hashPassword(password: string): string {
  const salt = randomBytes(16);
  const hash = scryptSync(password.normalize('NFKC'), salt, KEYLEN, { N, r: R, p: P, maxmem: 64 * 1024 * 1024 });
  return `scrypt$${N}$${R}$${P}$${salt.toString('base64')}$${hash.toString('base64')}`;
}

export function verifyPassword(password: string, stored: string): boolean {
  const [alg, n, r, p, salt, hash] = stored.split('$');
  if (alg !== 'scrypt' || !n || !r || !p || !salt || !hash) return false;
  const expected = Buffer.from(hash, 'base64');
  const actual = scryptSync(password.normalize('NFKC'), Buffer.from(salt, 'base64'), expected.length,
    { N: +n, r: +r, p: +p, maxmem: 64 * 1024 * 1024 });
  return actual.length === expected.length && timingSafeEqual(actual, expected);
}

export function checkPasswordPolicy(pw: string): string | null {
  if (typeof pw !== 'string' || pw.length < MIN_PASSWORD) return `La contraseña debe tener al menos ${MIN_PASSWORD} caracteres`;
  if (pw.length > 256) return 'La contraseña es demasiado larga';
  if (!/[A-Za-z]/.test(pw) || !/[0-9]/.test(pw)) return 'La contraseña debe combinar letras y números';
  return null;
}

// Hash ficticio para igualar el tiempo de respuesta cuando el usuario no existe.
const DUMMY_HASH = hashPassword(randomBytes(12).toString('hex') + 'a1');

// ------------------------------------------------------------------ sesiones
export const SESSION_IDLE_MS = 2 * 60 * 60 * 1000;      // 2 h sin actividad
export const SESSION_MAX_MS = 12 * 60 * 60 * 1000;      // 12 h como máximo
const MAX_FAILED = 5;
const LOCK_MS = 15 * 60 * 1000;

const sha256 = (s: string) => createHash('sha256').update(s).digest('hex');

export interface UserRow {
  id: number; email: string; name: string; role: Role; password_hash: string;
  disabled: number; failed_logins: number; locked_until: number | null; created_at: number; password_changed_at: number;
}

export class Auth {
  constructor(private db: DB, private secureCookies: boolean) {}

  get cookieName() { return this.secureCookies ? '__Host-ev_session' : 'ev_session'; }

  cookie(token: string, maxAgeMs: number): string {
    return `${this.cookieName}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${Math.floor(maxAgeMs / 1000)}${this.secureCookies ? '; Secure' : ''}`;
  }
  clearCookie(): string { return `${this.cookieName}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${this.secureCookies ? '; Secure' : ''}`; }

  /** Comprueba credenciales con bloqueo temporal tras varios fallos. Mensaje genérico para no revelar si el email existe. */
  login(email: string, password: string, ip: string, ua: string): { token: string; csrf: string; user: UserRow } {
    const generic = new HttpError(401, 'invalid_credentials', 'Email o contraseña incorrectos');
    const user = this.db.prepare('SELECT * FROM users WHERE email = ?').get(String(email ?? '').trim()) as UserRow | undefined;
    if (!user) { verifyPassword(String(password ?? ''), DUMMY_HASH); throw generic; }
    const now = Date.now();
    if (user.locked_until && user.locked_until > now) throw new HttpError(429, 'account_locked', 'Demasiados intentos. Inténtalo de nuevo en unos minutos.');
    if (!verifyPassword(String(password ?? ''), user.password_hash) || user.disabled) {
      const failed = user.failed_logins + 1;
      this.db.prepare('UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?')
        .run(failed >= MAX_FAILED ? 0 : failed, failed >= MAX_FAILED ? now + LOCK_MS : null, user.id);
      throw generic;
    }
    this.db.prepare('UPDATE users SET failed_logins = 0, locked_until = NULL WHERE id = ?').run(user.id);
    const token = randomBytes(32).toString('base64url');
    const csrf = randomBytes(24).toString('base64url');
    this.db.prepare('INSERT INTO sessions (id_hash, user_id, csrf, created_at, last_seen, expires_at, ip, user_agent) VALUES (?,?,?,?,?,?,?,?)')
      .run(sha256(token), user.id, csrf, now, now, now + SESSION_MAX_MS, ip, ua.slice(0, 200));
    this.db.prepare('DELETE FROM sessions WHERE expires_at < ? OR last_seen < ?').run(now, now - SESSION_IDLE_MS);
    return { token, csrf, user };
  }

  tokenFrom(ctx: Ctx): string | null {
    const raw = ctx.req.headers.cookie ?? '';
    for (const part of raw.split(';')) {
      const [k, ...v] = part.trim().split('=');
      if (k === this.cookieName) return v.join('=') || null;
    }
    return null;
  }

  /** Autentica la petición; en métodos que modifican exige además la cabecera X-CSRF-Token. */
  authenticate(ctx: Ctx): void {
    const token = this.tokenFrom(ctx);
    if (!token) throw new HttpError(401, 'unauthenticated', 'Inicia sesión');
    const idHash = sha256(token);
    const now = Date.now();
    const row = this.db.prepare(`SELECT s.csrf, s.last_seen, s.expires_at, u.id, u.email, u.name, u.role, u.disabled
      FROM sessions s JOIN users u ON u.id = s.user_id WHERE s.id_hash = ?`).get(idHash) as
      { csrf: string; last_seen: number; expires_at: number; id: number; email: string; name: string; role: Role; disabled: number } | undefined;
    if (!row || row.expires_at < now || row.last_seen < now - SESSION_IDLE_MS || row.disabled) {
      if (row) this.db.prepare('DELETE FROM sessions WHERE id_hash = ?').run(idHash);
      throw new HttpError(401, 'session_expired', 'La sesión ha caducado. Vuelve a iniciar sesión.');
    }
    if (!['GET', 'HEAD', 'OPTIONS'].includes(ctx.req.method ?? 'GET')) {
      const header = String(ctx.req.headers['x-csrf-token'] ?? '');
      const a = Buffer.from(header), b = Buffer.from(row.csrf);
      if (a.length !== b.length || !timingSafeEqual(a, b)) throw new HttpError(403, 'csrf', 'Token CSRF no válido');
    }
    if (now - row.last_seen > 60_000) this.db.prepare('UPDATE sessions SET last_seen = ? WHERE id_hash = ?').run(now, idHash);
    ctx.user = { id: row.id, email: row.email, name: row.name, role: row.role };
    ctx.session = { idHash, csrf: row.csrf };
  }

  require(ctx: Ctx, p: Permission): void {
    if (!ctx.user) this.authenticate(ctx);
    if (!can(ctx.user!.role, p)) throw new HttpError(403, 'forbidden', 'No tienes permiso para esta acción');
  }

  logout(ctx: Ctx) { if (ctx.session) this.db.prepare('DELETE FROM sessions WHERE id_hash = ?').run(ctx.session.idHash); }

  revokeUserSessions(userId: number, exceptIdHash?: string) {
    this.db.prepare('DELETE FROM sessions WHERE user_id = ? AND id_hash != ?').run(userId, exceptIdHash ?? '');
  }

  createUser(email: string, name: string, role: Role, password: string): number {
    const policy = checkPasswordPolicy(password);
    if (policy) throw new HttpError(422, 'weak_password', policy);
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 200) throw new HttpError(422, 'invalid_email', 'Email no válido');
    if (!['owner', 'publisher', 'editor', 'viewer'].includes(role)) throw new HttpError(422, 'invalid_role', 'Rol no válido');
    const now = Date.now();
    try {
      const r = this.db.prepare('INSERT INTO users (email, name, role, password_hash, created_at, password_changed_at) VALUES (?,?,?,?,?,?)')
        .run(email.trim(), (name || email).trim().slice(0, 80), role, hashPassword(password), now, now);
      return Number(r.lastInsertRowid);
    } catch (e) {
      if (String(e).includes('UNIQUE')) throw new HttpError(409, 'email_taken', 'Ya existe un usuario con ese email');
      throw e;
    }
  }
}

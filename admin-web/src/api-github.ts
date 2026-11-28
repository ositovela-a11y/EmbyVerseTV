// Modo GitHub del Design Studio: sin servidor propio. El diseño publicado es un archivo del
// repositorio (web-player/config/app.json) que GitHub Pages sirve a la app y a la web.
// Se entra con un token personal de GitHub con permiso de escritura en el repositorio; el token
// solo se guarda en este navegador y solo se envía a api.github.com.
import { fixImage } from './image-fix';
import { VENDOR_THEMES, applyVendorTheme } from './vendor-themes';
import type { RemoteConfig } from '../../backend/src/config/schema';
import type { Asset, User, Validation, VersionInfo } from './api';
import { ApiError } from './errors';
import type { SealedRegistry } from './registry';
import { validateLocal } from './store';

export const GH = {
  owner: 'ositovela-a11y',
  /** Repositorio del código y los diseños. Si existe el privado «EmbyVerse-codigo», se usa ese (ver resolveRepo). */
  repo: 'EmbyVerseTV',
  private: false,
  branch: 'main',
  configPath: 'web-player/config/app.json',
  assetsDir: 'web-player/config/assets',
  pagesBase: 'https://ositovela-a11y.github.io/EmbyVerseTV/config',
};

const TOKEN_KEY = 'ev.studio.ghToken';
const TARGET_KEY = 'ev.studio.target';

// ---- Apps de vendedores: cada una tiene su propio diseño (web-player/config/vendors/<id>.json)
export interface Vendor { slug: string; name: string; createdAt: string }
export const VENDORS_INDEX = 'web-player/config/vendors/index.json';
/** App que se está editando: '' = la principal, o el identificador de un vendedor. */
export function currentTarget(): string { try { return localStorage.getItem(TARGET_KEY) || ''; } catch { return ''; } }
export function setTarget(slug: string) { try { localStorage.setItem(TARGET_KEY, slug); } catch { /* */ } }
const cfgPath = (t = currentTarget()) => (t ? `web-player/config/vendors/${t}.json` : GH.configPath);
const draftKey = (t = currentTarget()) => `ev.studio.draft:${t || 'principal'}`;
const DRAFT_KEY_LEGACY = 'ev.studio.draft';
/** Enlaces públicos de una app (principal si slug = ''). */
/** Tu dominio propio (servidor site-worker en Cloudflare). */
export const PUBLIC_SITE = 'https://eversetv.com';

export function linksFor(slug: string) {
  // Los enlaces para clientes y vendedores siempre usan tu dominio propio (no muestran GitHub).
  // Solo en pruebas locales se usa la dirección de la página.
  const here = typeof location !== 'undefined' && /^http:\/\/(localhost|127\.0\.0\.1)/.test(location.href) ? location.href.split('/studio')[0].replace(/\/$/, '') : '';
  const site = here || PUBLIC_SITE;
  return {
    apk: `${site}/apk/${slug ? `EmbyVerse-${slug}.apk` : 'EmbyVerse.apk'}`,
    short: `${site.replace('https://', '')}/a/${slug ? `?v=${slug}` : ''}`,
    web: `${site}/${slug ? `?v=${slug}` : ''}`,
    // App de PC (Windows): enlace corto que siempre baja la última versión de esa marca.
    pc: `${site}/pc/${slug ? `?v=${slug}` : ''}`,
    pcExe: `${site}/descargas/pc/${slug || 'EmbyVerse'}-PC-Instalador.exe`,
  };
}
/** El código vive en el repositorio privado EmbyVerse-codigo (si existe y el token tiene acceso); si no, en EmbyVerseTV. */
export const CODE_REPO = 'EmbyVerse-codigo';
let API = `https://api.github.com/repos/${GH.owner}/${GH.repo}`;
async function resolveRepo() {
  try {
    const r = await fetch(`https://api.github.com/repos/${GH.owner}/${CODE_REPO}`, { headers: { authorization: `Bearer ${token()}`, accept: 'application/vnd.github+json' } });
    if (r.ok) { const j = await r.json() as { private?: boolean }; GH.repo = CODE_REPO; GH.private = !!j.private; API = `https://api.github.com/repos/${GH.owner}/${GH.repo}`; }
  } catch { /* sin red: se queda como está */ }
}
const LICENSES = 'web-player/config/licenses.json';
const BLOCKED = 'web-player/config/blocked.json';
const APPROVALS = 'web-player/config/aprobadas.json';
/** Versiones aprobadas por el dueño: Android por archivo de cada marca (versionCode), TV y PC por versión. */
export type Approvals = { android: Record<string, number>; tv?: string; pc?: string; updated?: string };
export type Ver = { code?: number; name: string } | null;
export type VersionStatus = {
  updated: string;
  android: { asset: string; slug: string; name: string; live: Ver; nueva: Ver; pendiente: boolean; aprobada?: number | null }[];
  tv: { live: Ver; nueva: Ver; pendiente: boolean; aprobada?: string | null };
  pc: { live: Ver; nueva: Ver; pendiente: boolean; aprobada?: string | null };
};
/**
 * Equipos y usuarios bloqueados (config/blocked.json). Es público (las apps lo leen cada 2 min), por eso solo
 * guarda huellas: nadie puede ver qué equipo o usuario es.
 *  devices: huella del código de instalación · users: huella de app + servidor + usuario.
 */
export interface Blocked { v: 1; devices: Record<string, { at: string }>; users: Record<string, { at: string }>; updated?: string }
async function h16(text: string) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(d), (b) => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
}
export const blockDeviceKey = (installId: string) => h16('blk-dev:' + installId.trim());
export const blockUserKey = (app: string, host: string, user: string) => h16(`blk-user:${app}|${host.trim().toLowerCase()}|${user.trim().toLowerCase()}`);
/** Código corto del equipo (el mismo que ve el cliente en la pantalla de bloqueo). */
export const deviceShortCode = (installId: string) => installId.replace(/[^a-zA-Z0-9]/g, '').slice(0, 8).toUpperCase().replace(/^(.{4})/, '$1-');
const REGISTRY = 'admin/registro.enc.json';
const DL_CODES = 'web-player/config/downloader-codes.json';
export interface Licenses { v: number; updated?: string; devices: Record<string, { exp: string | null }> }

const token = () => { try { return localStorage.getItem(TOKEN_KEY) || ''; } catch { return ''; } };

async function gh<T>(method: string, path: string, body?: unknown): Promise<T> {
  const t = token();
  if (!t) throw new ApiError(401, 'no_token', 'Inicia sesión con tu token de GitHub');
  const res = await fetch(path.startsWith('http') ? path : API + path, {
    method,
    headers: { Accept: 'application/vnd.github+json', Authorization: `Bearer ${t}`, 'X-GitHub-Api-Version': '2022-11-28', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  const text = await res.text();
  const json = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const msg = res.status === 401 ? 'El token de GitHub no es válido o caducó.'
      : res.status === 403 ? 'El token no tiene permiso de escritura en el repositorio (Contents: Read and write).'
      : res.status === 409 ? 'Alguien publicó a la vez. Recarga e inténtalo de nuevo.'
      : json?.message ?? `GitHub respondió ${res.status}`;
    throw new ApiError(res.status, 'github', msg);
  }
  return json as T;
}

const b64encode = (bytes: Uint8Array) => { let s = ''; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000)); return btoa(s); };
const utf8b64 = (text: string) => b64encode(new TextEncoder().encode(text));
const b64utf8 = (b64: string) => new TextDecoder().decode(Uint8Array.from(atob(b64.replace(/\s/g, '')), (c) => c.charCodeAt(0)));

interface FileInfo { sha: string; content?: string; name: string; path: string; size: number; download_url: string | null }

async function readJsonFile<T>(path: string, ref = GH.branch): Promise<{ data: T; sha: string } | null> {
  try {
    const f = await gh<FileInfo>('GET', `/contents/${path}?ref=${encodeURIComponent(ref)}`);
    return { data: JSON.parse(b64utf8(f.content ?? '')) as T, sha: f.sha };
  } catch (e) { if (e instanceof ApiError && e.status === 404) return null; throw e; }
}

async function readConfig(ref = GH.branch, path = cfgPath()): Promise<{ config: RemoteConfig; sha: string } | null> {
  try {
    const f = await gh<FileInfo>('GET', `/contents/${path}?ref=${encodeURIComponent(ref)}`);
    return { config: JSON.parse(b64utf8(f.content ?? '')) as RemoteConfig, sha: f.sha };
  } catch (e) { if (e instanceof ApiError && e.status === 404) return null; throw e; }
}

const toValidation = (c: RemoteConfig): Validation => {
  const r = validateLocal(c) as unknown as { ok?: boolean; errors?: string[]; warnings?: string[] };
  const errors = r.errors ?? []; return { ok: errors.length === 0, errors, warnings: r.warnings ?? [], assetErrors: [] };
};

const ME: User = { id: 1, email: 'GitHub', name: 'Propietario', role: 'owner',
  permissions: ['config:read', 'config:edit', 'config:publish', 'assets:read', 'assets:write', 'assets:approve'] };

const kindOf = (name: string): Asset['kind'] => (name.startsWith('logo-') ? 'logo' : name.startsWith('banner-') ? 'banner' : 'image');
const mimeOf = (name: string) => (/\.png$/i.test(name) ? 'image/png' : /\.webp$/i.test(name) ? 'image/webp' : /\.jpe?g$/i.test(name) ? 'image/jpeg' : 'application/octet-stream');
const assetOf = (f: FileInfo): Asset => ({
  id: f.sha + ':' + f.name, filename: f.name, mime: mimeOf(f.name), size: f.size, width: 0, height: 0, kind: kindOf(f.name), approved: true,
  url: `${GH.pagesBase}/assets/${f.name}`,
  previewUrl: GH.private ? `${GH.pagesBase}/assets/${f.name}` : `https://raw.githubusercontent.com/${GH.owner}/${GH.repo}/${GH.branch}/${GH.assetsDir}/${encodeURIComponent(f.name)}`,
  created_at: 0,
});

export const githubApi = {
  setToken(t: string) { try { localStorage.setItem(TOKEN_KEY, t.trim()); } catch { /* */ } },
  async me(): Promise<User> {
    await resolveRepo();
    const r = await gh<{ permissions?: { push?: boolean } }>('GET', '');
    if (!r.permissions?.push) throw new ApiError(403, 'github', 'El token no tiene permiso de escritura en el repositorio.');
    return ME;
  },
  async login(_email: string, password: string) { githubApi.setToken(password); try { return await githubApi.me(); } catch (e) { githubApi.setToken(''); throw e; } },
  async logout() { githubApi.setToken(''); },
  async changePassword() { throw new ApiError(400, 'github', 'En el modo GitHub la contraseña es tu token.'); },

  async draft() {
    const pub = await readConfig();
    const latestVersion = pub?.config.version ?? 0;
    let config = pub?.config ?? null;
    let baseVersion = latestVersion;
    try {
      const d = JSON.parse(localStorage.getItem(draftKey()) || 'null') as { config: RemoteConfig; base: number } | null;
      if (d) { config = d.config; baseVersion = d.base; }
    } catch { /* borrador dañado: se ignora */ }
    if (!config) config = (await import('../../shared/default-config.json')).default as unknown as RemoteConfig;
    return { config, baseVersion, latestVersion, updatedAt: Date.now(), updatedBy: null, validation: toValidation(config), publicBaseUrl: GH.pagesBase };
  },
  async saveDraft(config: RemoteConfig) {
    const pub = await readConfig().catch(() => null);
    try { localStorage.setItem(draftKey(), JSON.stringify({ config, base: pub?.config.version ?? 0 })); } catch { /* */ }
    return { validation: toValidation(config) };
  },
  async discardDraft() { try { localStorage.removeItem(draftKey()); } catch { /* */ } },
  async validate(config: RemoteConfig) { return toValidation(config); },

  async publish(note: string, _force = false, override?: RemoteConfig) {
    let d: RemoteConfig | null = override ?? null;
    if (!d) { try { d = (JSON.parse(localStorage.getItem(draftKey()) || 'null') as { config: RemoteConfig } | null)?.config ?? null; } catch { d = null; } }
    if (!d) throw new ApiError(400, 'no_draft', 'No hay cambios guardados para publicar.');
    const v = toValidation(d);
    if (!v.ok) throw new ApiError(422, 'invalid', 'El diseño tiene errores: ' + v.errors.join('; '));
    const pub = await readConfig();
    const version = (pub?.config.version ?? 0) + 1;
    const next = { ...d, version, publishedAt: new Date().toISOString().replace(/\.\d+Z$/, 'Z') } as RemoteConfig;
    await gh('PUT', `/contents/${cfgPath()}`, {
      message: `Diseño v${version}: ${note || 'publicado desde Design Studio'}`,
      content: utf8b64(JSON.stringify(next, null, 2) + '\n'), branch: GH.branch, ...(pub ? { sha: pub.sha } : {}),
    });
    try { localStorage.removeItem(draftKey()); } catch { /* */ }
    return { version, warnings: v.warnings };
  },

  async versions(): Promise<{ versions: VersionInfo[] }> {
    const commits = await gh<{ sha: string; commit: { message: string; author: { date: string; name: string } } }[]>('GET', `/commits?path=${encodeURIComponent(cfgPath())}&per_page=40`);
    const versions = commits.map((c) => {
      const m = /^Diseño v(\d+):\s*(.*)$/s.exec(c.commit.message);
      return m ? { version: Number(m[1]), published_at: Date.parse(c.commit.author.date), published_by: c.commit.author.name, note: m[2].split('\n')[0] || null, rollback_of: null, sha: c.sha } : null;
    }).filter(Boolean) as (VersionInfo & { sha: string })[];
    shaByVersion = new Map(versions.map((v) => [v.version, v.sha]));
    return { versions };
  },
  async version(v: number) {
    const sha = shaByVersion.get(v); if (!sha) throw new ApiError(404, 'github', 'Versión no encontrada');
    const r = await readConfig(sha); if (!r) throw new ApiError(404, 'github', 'Versión no encontrada');
    return { version: v, config: r.config };
  },
  async restoreDraft(v: number) { const r = await githubApi.version(v); await githubApi.saveDraft(r.config); },
  async rollback(toVersion: number) {
    const r = await githubApi.version(toVersion);
    return githubApi.publish(`vuelta a la versión ${toVersion}`, true, r.config);
  },

  async assets(): Promise<{ assets: Asset[] }> {
    try { const l = await gh<FileInfo[]>('GET', `/contents/${GH.assetsDir}?ref=${GH.branch}`); return { assets: l.filter((f) => /\.(png|jpe?g|webp)$/i.test(f.name)).map(assetOf) }; }
    catch (e) { if (e instanceof ApiError && e.status === 404) return { assets: [] }; throw e; }
  },
  async upload(input: File, kind: Asset['kind']) {
    // Cualquier imagen que abra el navegador se ajusta sola (formato, tamaño y peso) antes de subirla.
    let file: File; let note = '';
    try { const r = await fixImage(input, kind === 'logo' ? 'logo' : kind === 'banner' ? 'banner' : 'image'); file = r.file; note = r.note; }
    catch (e) { throw new ApiError(400, 'github', e instanceof Error ? e.message : 'No se pudo abrir la imagen.'); }
    if (!/^image\/(png|jpeg|webp)$/.test(file.type)) throw new ApiError(400, 'github', 'Usa una imagen PNG, JPG o WebP.');
    if (file.size > 1_500_000) throw new ApiError(400, 'github', 'La imagen pesa más de 1,5 MB aun después de ajustarla. Prueba con otra.');
    const ext = file.type === 'image/png' ? 'png' : file.type === 'image/webp' ? 'webp' : 'jpg';
    const base = file.name.replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40) || 'imagen';
    const name = `${kind}-${base}-${Date.now().toString(36)}.${ext}`;
    const bytes = new Uint8Array(await file.arrayBuffer());
    const r = await gh<{ content: FileInfo }>('PUT', `/contents/${GH.assetsDir}/${name}`, { message: `Design Studio: imagen ${name}`, content: b64encode(bytes), branch: GH.branch });
    return { asset: assetOf(r.content), note };
  },
  async approve() { /* en modo GitHub todas las imágenes del repositorio están aprobadas */ },
  async deleteAsset(id: string) {
    const [sha, ...rest] = id.split(':'); const name = rest.join(':');
    await gh('DELETE', `/contents/${GH.assetsDir}/${name}`, { message: `Design Studio: elimina ${name}`, sha, branch: GH.branch });
  },
  // ---- vendedores
  async vendors(): Promise<Vendor[]> { return (await readJsonFile<Vendor[]>(VENDORS_INDEX))?.data ?? []; },
  async createVendor(name: string, opts: { askHost?: boolean; logo?: File | null; colors?: { primary: string; accent: string; background: string }; themeId?: string; welcome?: string } = {}): Promise<Vendor> {
    const clean = name.trim().slice(0, 30);
    if (clean.length < 2) throw new ApiError(400, 'github', 'Escribe el nombre del vendedor o de su marca.');
    let base = clean.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 20);
    if (!/^[a-z]/.test(base)) base = 'v' + base;
    if (base.length < 2) base = 'vendedor';
    const idx = await readJsonFile<Vendor[]>(VENDORS_INDEX);
    const list = idx?.data ?? [];
    let slug = base; let n = 2;
    while (list.some((v) => v.slug === slug)) slug = `${base}${n++}`;
    // El vendedor empieza con una copia del diseño publicado de la app principal, con su nombre.
    const main = await readConfig(GH.branch, GH.configPath);
    const start = structuredClone(main?.config ?? (await import('../../shared/default-config.json')).default as unknown as RemoteConfig);
    // Servidor: el de la app principal (cliente solo pone usuario y contraseña) o sin servidor (escribe URL, usuario y contraseña).
    start.texts = { ...(start.texts ?? {}) };
    if (opts.askHost) { start.texts['provider.askHost'] = '1'; delete start.texts['provider.host']; }
    else delete start.texts['provider.askHost'];
    // Marca propia del vendedor: nada de «EmbyVerse» en textos, banners, secciones ni temas (las URL no se tocan).
    const scrub = (v: unknown): unknown => {
      if (typeof v === 'string') return /^https?:\/\//.test(v) ? v : v.replace(/EmbyVerse/gi, clean);
      if (Array.isArray(v)) return v.map(scrub);
      if (v && typeof v === 'object') { const o: Record<string, unknown> = {}; for (const [k, x] of Object.entries(v)) o[k] = scrub(x); return o; }
      return v;
    };
    Object.assign(start, scrub(start) as RemoteConfig);
    start.brand.tagline = null;
    start.messages = []; // los avisos de tu app principal no pasan al vendedor
    if (opts.logo) {
      const { asset } = await githubApi.upload(opts.logo, 'logo');
      start.brand.logo = { variant: 'asset', url: asset.url };
    }
    // Tema elegido (colores, fondo, letra, bordes, botones y distribución) + los colores ajustados a mano.
    const th = VENDOR_THEMES.find((x) => x.id === opts.themeId);
    if (th) {
      applyVendorTheme(start as unknown as { theme: Record<string, unknown>; texts: Record<string, string> }, th, opts.colors);
      // Los temas guardados de la app principal no pasan: el vendedor queda con el suyo.
      (start as unknown as { themes?: unknown[] }).themes = [{ id: 'vendor', name: th.name, palette: { ...(start.theme.palette as unknown as Record<string, string>) } }];
    } else if (opts.colors) {
      const p = start.theme.palette as unknown as Record<string, string>;
      const on = (hex: string) => { const n = parseInt(hex.slice(1), 16); const l = (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255; return l > 0.6 ? '#0A0F18' : '#FFFFFF'; };
      p.primary = opts.colors.primary; p.buttonBackground = opts.colors.primary; p.onPrimary = on(opts.colors.primary); p.buttonText = on(opts.colors.primary);
      p.accent = opts.colors.accent; p.focusRing = opts.colors.accent;
      p.background = opts.colors.background;
      for (const t2 of (start as unknown as { themes?: { palette?: Record<string, string> }[] }).themes ?? []) if (t2.palette) Object.assign(t2.palette, { primary: p.primary, buttonBackground: p.buttonBackground, onPrimary: p.onPrimary, buttonText: p.buttonText, accent: p.accent, focusRing: p.focusRing, background: p.background });
    }
    // Mensaje de bienvenida propio (al abrir la app); vacío = «Bienvenido a <nombre>».
    if (opts.welcome?.trim()) start.texts['splash.welcome'] = opts.welcome.trim().slice(0, 80); else delete start.texts['splash.welcome'];
    start.brand.appName = clean; start.version = 1; start.publishedAt = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
    await gh('PUT', `/contents/${cfgPath(slug)}`, { message: `Diseño v1: app del vendedor ${clean}`, content: utf8b64(JSON.stringify(start, null, 2) + '\n'), branch: GH.branch });
    const v: Vendor = { slug, name: clean, createdAt: new Date().toISOString() };
    await gh('PUT', `/contents/${VENDORS_INDEX}`, { message: `Design Studio: nuevo vendedor ${clean}`, content: utf8b64(JSON.stringify([...list, v], null, 2) + '\n'), branch: GH.branch, ...(idx ? { sha: idx.sha } : {}) });
    return v;
  },
  /** Cómo se ve hoy una app (para el editor rápido de tema y logotipo). */
  async appLook(slug: string): Promise<{ logo: string | null; style: string; colors: { primary: string; accent: string; background: string }; themeId: string | null; otherServer: boolean; m3u: boolean;
    authOnly: boolean; authHosts: string; disabled: boolean; providerHost: string; expiryOn: boolean; tvPaid: boolean }> {
    const r = await readConfig(GH.branch, cfgPath(slug));
    if (!r) throw new ApiError(404, 'github', 'No se encontró el diseño de esa app.');
    const c = r.config; const p = c.theme.palette as unknown as Record<string, string>;
    const logo = c.brand.logo?.variant === 'asset' ? (c.brand.logo as { url?: string }).url ?? null : null;
    const style = c.texts?.['style.default'] ?? '';
    const themeName = (c as unknown as { themes?: { id?: string; name?: string }[] }).themes?.find((t) => t.id === 'vendor')?.name;
    const th = VENDOR_THEMES.find((t) => t.name === themeName) ?? VENDOR_THEMES.find((t) => t.style === style && t.palette.primary === p.primary) ?? VENDOR_THEMES.find((t) => t.style === style) ?? null;
    return { logo, style, colors: { primary: p.primary!, accent: p.accent!, background: p.background! }, themeId: th?.id ?? null,
      otherServer: c.texts?.['login.otherServer'] === '1', m3u: c.texts?.['login.m3u'] !== '0',
      // Autorización: solo servidores autorizados (apagado salvo "1"), otros servidores permitidos y apagado a distancia.
      authOnly: c.texts?.['auth.only'] === '1', authHosts: (c.texts?.['auth.hosts'] ?? '').split(/[\s,;]+/).filter(Boolean).join('\n'),
      disabled: c.texts?.['app.disabled'] === '1', providerHost: c.texts?.['provider.host'] ?? '',
      expiryOn: c.texts?.['expiry.off'] !== '1', tvPaid: c.texts?.['license.required'] !== '0' };
  },
  /**
   * Cambia el tema (colores, fondo, letra, bordes, botones y distribución), los colores y/o el logotipo de
   * una app ya creada (la principal o la de un vendedor) y lo publica. Si había cambios sin publicar de
   * esa app en el estudio, también se les aplica, para que no regresen el tema anterior.
   */
  async restyleApp(slug: string, opts: { themeId?: string | null; colors?: { primary: string; accent: string; background: string }; logo?: File | null; texts?: Record<string, string> }) {
    const path = cfgPath(slug);
    const pub = await readConfig(GH.branch, path);
    if (!pub) throw new ApiError(404, 'github', 'No se encontró el diseño de esa app.');
    let logoUrl: string | null = null;
    if (opts.logo) logoUrl = (await githubApi.upload(opts.logo, 'logo')).asset.url;
    const th = VENDOR_THEMES.find((x) => x.id === opts.themeId);
    const change = (c: RemoteConfig) => {
      c.texts = { ...(c.texts ?? {}), ...(opts.texts ?? {}) };
      if (logoUrl) c.brand.logo = { variant: 'asset', url: logoUrl };
      if (th) {
        applyVendorTheme(c as unknown as { theme: Record<string, unknown>; texts: Record<string, string> }, th, opts.colors);
        // En las apps de vendedores el tema guardado es el suyo; en la principal se respetan tus temas.
        if (slug) (c as unknown as { themes?: unknown[] }).themes = [{ id: 'vendor', name: th.name, palette: { ...(c.theme.palette as unknown as Record<string, string>) } }];
      }
    };
    const next = structuredClone(pub.config); change(next);
    const version = (pub.config.version ?? 0) + 1;
    Object.assign(next, { version, publishedAt: new Date().toISOString().replace(/\.\d+Z$/, 'Z') });
    const what = [th && `tema ${th.name}`, logoUrl && 'logotipo'].filter(Boolean).join(' y ') || 'colores';
    await gh('PUT', `/contents/${path}`, { message: `Diseño v${version}: ${what} (editor rápido)`, content: utf8b64(JSON.stringify(next, null, 2) + '\n'), branch: GH.branch, sha: pub.sha });
    try {
      const k = draftKey(slug); const dr = JSON.parse(localStorage.getItem(k) || 'null') as { config: RemoteConfig } | null;
      if (dr?.config) { change(dr.config); dr.config.version = version; localStorage.setItem(k, JSON.stringify(dr)); }
    } catch { /* */ }
    return { version };
  },
  async renameVendor(slug: string, name: string) {
    const idx = await readJsonFile<Vendor[]>(VENDORS_INDEX); if (!idx) return;
    const list = idx.data.map((v) => (v.slug === slug ? { ...v, name: name.trim().slice(0, 30) } : v));
    await gh('PUT', `/contents/${VENDORS_INDEX}`, { message: `Design Studio: renombra vendedor ${slug}`, content: utf8b64(JSON.stringify(list, null, 2) + '\n'), branch: GH.branch, sha: idx.sha });
  },
  async deleteVendor(slug: string) {
    const idx = await readJsonFile<Vendor[]>(VENDORS_INDEX); if (!idx) return;
    await gh('PUT', `/contents/${VENDORS_INDEX}`, { message: `Design Studio: elimina vendedor ${slug}`, content: utf8b64(JSON.stringify(idx.data.filter((v) => v.slug !== slug), null, 2) + '\n'), branch: GH.branch, sha: idx.sha });
    const f = await gh<FileInfo>('GET', `/contents/${cfgPath(slug)}?ref=${GH.branch}`).catch(() => null);
    if (f) await gh('DELETE', `/contents/${cfgPath(slug)}`, { message: `Design Studio: elimina el diseño de ${slug}`, sha: f.sha, branch: GH.branch });
    if (currentTarget() === slug) setTarget('');
  },
  /** Activaciones de equipos (edición de tienda): config/licenses.json → { devices: { hash: { exp } } }. */
  async licenses(): Promise<{ data: Licenses; sha: string | null }> {
    const f = await readJsonFile<Licenses>(LICENSES);
    return f ? { data: { v: 1, devices: {}, ...f.data }, sha: f.sha } : { data: { v: 1, devices: {} }, sha: null };
  },
  async saveLicenses(change: (d: Licenses) => void, message: string) {
    // Se relee justo antes de guardar para no pisar un cambio hecho desde otro equipo.
    const cur = await githubApi.licenses();
    change(cur.data); cur.data.updated = new Date().toISOString();
    await gh('PUT', `/contents/${LICENSES}`, { message: `Activaciones: ${message}`, content: utf8b64(JSON.stringify(cur.data, null, 1) + '\n'), branch: GH.branch, ...(cur.sha ? { sha: cur.sha } : {}) });
    return cur.data;
  },
  async blocked(): Promise<{ data: Blocked; sha: string | null }> {
    const f = await readJsonFile<Blocked>(BLOCKED);
    return f ? { data: { v: 1, devices: {}, users: {}, ...f.data }, sha: f.sha } : { data: { v: 1, devices: {}, users: {} }, sha: null };
  },
  async saveBlocked(change: (d: Blocked) => void, message: string): Promise<Blocked> {
    const cur = await githubApi.blocked(); // se relee justo antes para no pisar otro cambio
    change(cur.data); cur.data.updated = new Date().toISOString();
    await gh('PUT', `/contents/${BLOCKED}`, { message: `Bloqueos: ${message}`, content: utf8b64(JSON.stringify(cur.data, null, 1) + '\n'), branch: GH.branch, ...(cur.sha ? { sha: cur.sha } : {}) });
    return cur.data;
  },
  /** Versiones: cuál tienen los clientes y cuál espera aprobación (lo publica el flujo del sitio). */
  async versionStatus(): Promise<VersionStatus | null> {
    try { const r = await fetch(`${GH.pagesBase}/versiones.json?t=${Date.now()}`, { cache: 'no-store' }); return r.ok ? await r.json() as VersionStatus : null; } catch { return null; }
  },
  async approvals(): Promise<{ data: Approvals; sha: string | null }> {
    const f = await readJsonFile<Approvals>(APPROVALS);
    return f ? { data: { android: {}, ...f.data }, sha: f.sha } : { data: { android: {} }, sha: null };
  },
  /** «Actualizar versión»: el sitio publica lo aprobado a los clientes en unos minutos. */
  async approveRelease(change: (d: Approvals) => void, message: string): Promise<Approvals> {
    const cur = await githubApi.approvals(); // se relee justo antes para no pisar otro cambio
    change(cur.data); cur.data.updated = new Date().toISOString();
    await gh('PUT', `/contents/${APPROVALS}`, { message: `Actualizaciones: ${message}`, content: utf8b64(JSON.stringify(cur.data, null, 1) + '\n'), branch: GH.branch, ...(cur.sha ? { sha: cur.sha } : {}) });
    return cur.data;
  },
  /** Códigos numéricos de Downloader (AFTVnews) de cada app: '' = principal, si no el vendedor. Son públicos. */
  async downloaderCodes(): Promise<Record<string, string>> { return (await readJsonFile<Record<string, string>>(DL_CODES))?.data ?? {}; },
  async setDownloaderCode(slug: string, code: string | null) {
    const f = await readJsonFile<Record<string, string>>(DL_CODES);
    const d = { ...(f?.data ?? {}) };
    if (code) d[slug] = code; else delete d[slug];
    await gh('PUT', `/contents/${DL_CODES}`, { message: `Downloader: código de ${slug || 'la app principal'}`, content: utf8b64(JSON.stringify(d, null, 2) + '\n'), branch: GH.branch, ...(f ? { sha: f.sha } : {}) });
  },
  /** Dirección del servidor de estadísticas (la escribe el flujo «Servidor de estadísticas»). */
  async statsUrl(): Promise<string | null> { const f = await readJsonFile<{ url?: string }>('web-player/config/stats.json'); return f?.data.url?.replace(/\/$/, '') || null; },
  /** Registro cifrado de activaciones (fuera del sitio publicado). */
  async registry(): Promise<{ data: SealedRegistry; sha: string } | null> { return readJsonFile<SealedRegistry>(REGISTRY); },
  async saveRegistry(data: SealedRegistry, sha: string | null, message: string) {
    const r = await gh<{ content: { sha: string } }>('PUT', `/contents/${REGISTRY}`, { message: `Registro: ${message}`, content: utf8b64(JSON.stringify(data) + '\n'), branch: GH.branch, ...(sha ? { sha } : {}) });
    return r.content.sha;
  },
  async audit() { return { entries: [] }; },
  async users() { return { users: [] }; },
  async createUser() { throw new ApiError(400, 'github', 'No disponible en el modo GitHub'); },
  async updateUser() { throw new ApiError(400, 'github', 'No disponible en el modo GitHub'); },
  async deleteUser() { throw new ApiError(400, 'github', 'No disponible en el modo GitHub'); },
};

void DRAFT_KEY_LEGACY;
let shaByVersion = new Map<number, string>();

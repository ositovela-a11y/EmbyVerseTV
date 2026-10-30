// Modo GitHub del Design Studio: sin servidor propio. El diseño publicado es un archivo del
// repositorio (web-player/config/app.json) que GitHub Pages sirve a la app y a la web.
// Se entra con un token personal de GitHub con permiso de escritura en el repositorio; el token
// solo se guarda en este navegador y solo se envía a api.github.com.
import type { RemoteConfig } from '../../backend/src/config/schema';
import type { Asset, User, Validation, VersionInfo } from './api';
import { ApiError } from './errors';
import { validateLocal } from './store';

export const GH = {
  owner: 'ositovela-a11y',
  repo: 'EmbyVerseTV',
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
const draftKey = () => `ev.studio.draft:${currentTarget() || 'principal'}`;
const DRAFT_KEY_LEGACY = 'ev.studio.draft';
/** Enlaces públicos de una app (principal si slug = ''). */
export function linksFor(slug: string) {
  const site = 'https://ositovela-a11y.github.io/EmbyVerseTV';
  return {
    apk: `https://github.com/${GH.owner}/${GH.repo}/releases/download/apk-latest/${slug ? `EmbyVerse-${slug}.apk` : 'EmbyVerse.apk'}`,
    short: `${site.replace('https://', '')}/a/${slug ? `?v=${slug}` : ''}`,
    web: `${site}/${slug ? `?v=${slug}` : ''}`,
  };
}
const API = `https://api.github.com/repos/${GH.owner}/${GH.repo}`;

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
  previewUrl: `https://raw.githubusercontent.com/${GH.owner}/${GH.repo}/${GH.branch}/${GH.assetsDir}/${encodeURIComponent(f.name)}`,
  created_at: 0,
});

export const githubApi = {
  setToken(t: string) { try { localStorage.setItem(TOKEN_KEY, t.trim()); } catch { /* */ } },
  async me(): Promise<User> {
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
  async upload(file: File, kind: Asset['kind']) {
    if (!/^image\/(png|jpeg|webp)$/.test(file.type)) throw new ApiError(400, 'github', 'Usa una imagen PNG, JPG o WebP.');
    if (file.size > 1_500_000) throw new ApiError(400, 'github', 'La imagen pesa más de 1,5 MB. Redúcela antes de subirla.');
    const ext = file.type === 'image/png' ? 'png' : file.type === 'image/webp' ? 'webp' : 'jpg';
    const base = file.name.replace(/\.[^.]+$/, '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 40) || 'imagen';
    const name = `${kind}-${base}-${Date.now().toString(36)}.${ext}`;
    const bytes = new Uint8Array(await file.arrayBuffer());
    const r = await gh<{ content: FileInfo }>('PUT', `/contents/${GH.assetsDir}/${name}`, { message: `Design Studio: imagen ${name}`, content: b64encode(bytes), branch: GH.branch });
    return { asset: assetOf(r.content) };
  },
  async approve() { /* en modo GitHub todas las imágenes del repositorio están aprobadas */ },
  async deleteAsset(id: string) {
    const [sha, ...rest] = id.split(':'); const name = rest.join(':');
    await gh('DELETE', `/contents/${GH.assetsDir}/${name}`, { message: `Design Studio: elimina ${name}`, sha, branch: GH.branch });
  },
  // ---- vendedores
  async vendors(): Promise<Vendor[]> { return (await readJsonFile<Vendor[]>(VENDORS_INDEX))?.data ?? []; },
  async createVendor(name: string): Promise<Vendor> {
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
    start.brand.appName = clean; start.version = 1; start.publishedAt = new Date().toISOString().replace(/\.\d+Z$/, 'Z');
    await gh('PUT', `/contents/${cfgPath(slug)}`, { message: `Diseño v1: app del vendedor ${clean}`, content: utf8b64(JSON.stringify(start, null, 2) + '\n'), branch: GH.branch });
    const v: Vendor = { slug, name: clean, createdAt: new Date().toISOString() };
    await gh('PUT', `/contents/${VENDORS_INDEX}`, { message: `Design Studio: nuevo vendedor ${clean}`, content: utf8b64(JSON.stringify([...list, v], null, 2) + '\n'), branch: GH.branch, ...(idx ? { sha: idx.sha } : {}) });
    return v;
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
  async audit() { return { entries: [] }; },
  async users() { return { users: [] }; },
  async createUser() { throw new ApiError(400, 'github', 'No disponible en el modo GitHub'); },
  async updateUser() { throw new ApiError(400, 'github', 'No disponible en el modo GitHub'); },
  async deleteUser() { throw new ApiError(400, 'github', 'No disponible en el modo GitHub'); },
};

void DRAFT_KEY_LEGACY;
let shaByVersion = new Map<number, string>();

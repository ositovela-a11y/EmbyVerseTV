// Estadísticas del dueño de la app: aviso de que este TV/PC tiene la app instalada y la usa
// (código al azar de la instalación, app principal o vendedor, tipo de equipo, versión y el usuario
// Xtream; nunca la contraseña). Máximo un aviso cada 6 h, o al cambiar de cuenta. Sin servidor configurado
// (config/stats.json), no hace nada.
import { EV } from './config';
import { isTizen, isWebOS } from './platform';
import type { Account } from './store';

declare const __CONFIG_URL__: string;
declare const __VERSION__: string;
const K = 'ev.stats';

const read = (): Record<string, any> => { try { return JSON.parse(localStorage.getItem(K) || '{}'); } catch { return {}; } };
const write = (v: Record<string, any>) => { try { localStorage.setItem(K, JSON.stringify(v)); } catch { /* */ } };

/** Vendedor de esta instalación: el de su diseño (config/vendors/<slug>.json) o '' (app principal). */
export const appSlug = () => /\/vendors\/([a-z][a-z0-9]{1,30})\.json/.exec(EV.config || '')?.[1] ?? '';
export const statsPlatform = () => ((window as any).evDesktop ? 'pc' : isTizen ? 'samsung' : isWebOS ? 'lg' : 'web');

function newId() {
  const a = new Uint8Array(16); (crypto?.getRandomValues ? crypto.getRandomValues(a) : a.forEach((_, i) => { a[i] = Math.floor(Math.random() * 256); }));
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('').replace(/^(.{8})(.{4})(.{4})(.{4})/, '$1-$2-$3-$4-');
}

/** Dirección del servidor: siempre en el sitio principal (también para apps de vendedor). */
async function endpoint(st: Record<string, any>): Promise<string | null> {
  const now = Date.now();
  if (typeof st.url === 'string' && now - (st.urlAt || 0) < 86_400_000) return st.url || null;
  try {
    const base = __CONFIG_URL__.replace(/[^/]*$/, '');
    const r = await fetch(`${base}stats.json?t=${Math.floor(now / 3_600_000)}`);
    const url = r.ok ? String((await r.json()).url || '').trim().replace(/\/$/, '') : '';
    st.url = /^https:\/\//.test(url) || /^http:\/\/(localhost|127\.0\.0\.1)(:|\/|$)/.test(url) ? url : ''; st.urlAt = now; write(st);
    return st.url || null;
  } catch { return typeof st.url === 'string' ? st.url || null : null; }
}

/** Código al azar de esta instalación (el mismo que se manda a Estadísticas). */
export function installId(): string {
  const st = read();
  if (!st.id) { st.id = newId(); write(st); }
  return st.id;
}

export async function reportStats(acc: Account | null, force = false): Promise<void> {
  try {
    installId();
    const st = read();
    let host = '';
    try { host = acc?.host ? new URL(acc.host).host : acc?.m3u ? new URL(acc.m3u).host : ''; } catch { /* */ }
    const user = acc?.username || '';
    const sig = `${__VERSION__}|${user}|${host}`;
    const now = Date.now();
    if (!force && st.sig === sig && now - (st.at || 0) < 6 * 3_600_000) return;
    const url = await endpoint(st); if (!url) return;
    const model = (window as any).evDesktop ? 'Windows' : isTizen ? 'Samsung Smart TV' : isWebOS ? 'LG Smart TV' : navigator.platform || 'Navegador';
    const r = await fetch(`${url}/v1/ping`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: st.id, app: appSlug(), platform: statsPlatform(), model, version: __VERSION__, user, host }) });
    if (r.ok) { st.sig = sig; st.at = now; write(st); }
  } catch { /* sin aviso */ }
}

/** Dirección del servidor (la usa también la sincronización de perfiles). */
export async function serviceUrl(): Promise<string | null> { return endpoint(read()); }

/**
 * Reporte de fallos al dueño (Estadísticas → Fallos): errores de la app y videos que no se pueden reproducir
 * por el formato. Sin usuario, contraseña ni dirección del canal. El mismo fallo, máximo una vez al día.
 */
export async function reportProblem(kind: 'js' | 'play', message: string, detail = ''): Promise<void> {
  try {
    const st = read(); installId();
    const msg = message.replace(/https?:\/\/\S+/g, '[dirección]').slice(0, 300);
    const key = 'p:' + kind + '|' + msg; const now = Date.now();
    st.p = st.p || {}; if (now - (st.p[key] || 0) < 86_400_000) return;
    const url = await endpoint(st); if (!url) return;
    const os = (window as any).evDesktop ? 'Windows' : isTizen ? 'Tizen' : isWebOS ? 'webOS' : (navigator.userAgent || '').slice(0, 40);
    const model = (window as any).evDesktop ? 'Windows' : isTizen ? 'Samsung Smart TV' : isWebOS ? 'LG Smart TV' : navigator.platform || 'Navegador';
    const r = await fetch(`${url}/v1/crash`, { method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ id: read().id, app: appSlug(), platform: statsPlatform(), version: __VERSION__, kind, message: msg,
        detail: detail.replace(/https?:\/\/[^\s)]+/g, '[dirección]').slice(0, 4000), model, os }) });
    if (r.ok) { const s2 = read(); s2.p = { ...(s2.p || {}), [key]: now }; for (const k of Object.keys(s2.p)) if (now - s2.p[k] > 86_400_000) delete s2.p[k]; write(s2); }
  } catch { /* sin reporte */ }
}

/** Errores inesperados de la app (TV/PC). */
export function installCrashReport(): void {
  const seen = new Set<string>();
  const send = (m: string, d: string) => { if (seen.has(m) || seen.size > 20) return; seen.add(m); void reportProblem('js', m, d); };
  window.addEventListener('error', (e) => {
    const where = e.filename ? ` @ ${String(e.filename).split('/').pop()}:${e.lineno}` : '';
    send(`${e.message || 'Error'}${where}`, (e.error && e.error.stack) || '');
  });
  window.addEventListener('unhandledrejection', (e: any) => {
    const r = e?.reason; send(`Promesa sin atender: ${(r && (r.message || String(r))) || 'error'}`.slice(0, 200), (r && r.stack) || '');
  });
}

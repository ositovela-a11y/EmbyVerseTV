// Activación por equipo (edición para tiendas): cada TV/PC muestra su código; el administrador lo
// activa desde su panel (no hay pagos dentro de la app). Lista pública de activaciones:
// …/config/licenses.json → { devices: { "<sha256('lic:'+CÓDIGO) 16 hex>": { exp: "AAAA-MM-DD" | null } } }
// Se guarda el resumen (hash), no el código: nadie puede copiar un código activo de ese archivo.
import { EV, hash16 } from './config';
import { isTizen, isWebOS } from './platform';

declare const __EDITION__: string;
declare const __LICENSE_URL__: string;
export const EDITION = EV.edition || __EDITION__;
const LICENSE_URL = EV.license || __LICENSE_URL__;

const K_CODE = 'ev.devcode', K_CACHE = 'ev.lic.cache', K_FIRST = 'ev.lic.first', K_SEEN = 'ev.lic.seen', K_WARN = 'ev.lic.warned';
const ALPHA = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

/** Código del equipo (8 letras/números, sin O/0/I/1 para que no se confundan). Se crea una vez. */
export function deviceCode(): string {
  try {
    const old = localStorage.getItem(K_CODE); if (old && /^[A-Z0-9]{4}-[A-Z0-9]{4}$/.test(old)) return old;
    const rnd = new Uint8Array(8); (crypto?.getRandomValues ? crypto.getRandomValues(rnd) : rnd.forEach((_, i) => { rnd[i] = Math.floor(Math.random() * 256); }));
    const c = Array.from(rnd, (b) => ALPHA[b % ALPHA.length]).join('');
    const code = `${c.slice(0, 4)}-${c.slice(4)}`;
    localStorage.setItem(K_CODE, code); return code;
  } catch { return 'SIN-CODIGO'; }
}

export type LicState = { state: 'active'; exp: string | null } | { state: 'trial'; daysLeft: number } | { state: 'expired'; exp: string } | { state: 'none' };

/**
 * ¿Se exige activación? Edición de tienda y PC siempre. Samsung y LG también (de cobro, igual que la PC), salvo que el
 * panel la apague para esa app (texto «license.required» = 0). En otros equipos, solo si el panel la pide (= 1).
 */
export const licenseRequired = (texts: Record<string, string>) => EDITION === 'store' || EDITION === 'pc' || texts['license.required'] === '1'
  || ((isTizen || isWebOS) && texts['license.required'] !== '0');

const realToday = () => new Date().toISOString().slice(0, 10);
/** Fecha de hoy que no retrocede: si alguien atrasa el reloj de la PC, se usa el día más reciente ya visto. */
export function today(): string {
  const t = realToday();
  try {
    const seen = localStorage.getItem(K_SEEN) || '';
    if (/^\d{4}-\d{2}-\d{2}$/.test(seen) && seen > t) return seen;
    localStorage.setItem(K_SEEN, t);
  } catch { /* */ }
  return t;
}
/** Días que faltan para vencer (0 = vence hoy). */
export const daysLeft = (exp: string) => Math.round((Date.parse(exp + 'T12:00:00') - Date.parse(today() + 'T12:00:00')) / 86_400_000);

/** Aviso «tu activación está por vencer»: los últimos días (texto «license.warnDays», 7 por defecto), una vez al día. */
export function expiryWarning(lic: LicState, texts: Record<string, string>): { days: number; exp: string } | null {
  if (lic.state !== 'active' || !lic.exp) return null;
  const warn = Math.max(0, Number(texts['license.warnDays'] ?? 7) || 0);
  const d = daysLeft(lic.exp);
  if (d < 0 || d > warn) return null;
  try { if (localStorage.getItem(K_WARN) === today()) return null; } catch { /* */ }
  return { days: d, exp: lic.exp };
}
export function markWarned() { try { localStorage.setItem(K_WARN, today()); } catch { /* */ } }

export async function checkLicense(texts: Record<string, string>): Promise<LicState> {
  if (!licenseRequired(texts)) return { state: 'active', exp: null };
  const code = deviceCode().replace('-', '');
  const h = await hash16('lic:' + code);
  let entry: { exp: string | null } | undefined | null = null;
  try {
    const r = await fetch(`${LICENSE_URL}?t=${Date.now()}`, { cache: 'no-store' });
    if (!r.ok) throw new Error(String(r.status));
    const j = await r.json();
    entry = j?.devices?.[h];
    localStorage.setItem(K_CACHE, JSON.stringify({ at: Date.now(), entry: entry ?? null }));
  } catch {
    // Sin internet o sitio caído: se usa lo último que se supo (hasta 7 días).
    try { const c = JSON.parse(localStorage.getItem(K_CACHE) ?? 'null'); if (c && Date.now() - c.at < 7 * 86_400_000) entry = c.entry; } catch { /* */ }
  }
  if (entry) {
    if (!entry.exp || entry.exp >= today()) return { state: 'active', exp: entry.exp ?? null };
    return { state: 'expired', exp: entry.exp };
  }
  // Prueba gratis: 7 días en PC (texto «pc.trialDays») y en TV (texto «license.trialDays»).
  const trialDays = Math.max(0, Number(EDITION === 'pc' ? texts['pc.trialDays'] ?? 7 : texts['license.trialDays'] ?? 7) || 0);
  let first = Number(localStorage.getItem(K_FIRST) || 0);
  if (!first) { first = Date.now(); try { localStorage.setItem(K_FIRST, String(first)); } catch { /* */ } }
  const left = Math.ceil(trialDays - (Date.now() - first) / 86_400_000);
  return left > 0 ? { state: 'trial', daysLeft: left } : { state: 'none' };
}

// Activación por equipo (edición para tiendas): cada TV/PC muestra su código; el administrador lo
// activa desde su panel (no hay pagos dentro de la app). Lista pública de activaciones:
// …/config/licenses.json → { devices: { "<sha256('lic:'+CÓDIGO) 16 hex>": { exp: "AAAA-MM-DD" | null } } }
// Se guarda el resumen (hash), no el código: nadie puede copiar un código activo de ese archivo.
import { hash16 } from './config';

declare const __EDITION__: string;
declare const __LICENSE_URL__: string;
export const EDITION = __EDITION__;

const K_CODE = 'ev.devcode', K_CACHE = 'ev.lic.cache', K_FIRST = 'ev.lic.first';
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

/** ¿Se exige activación? Edición de tienda, o el panel lo pide (texto «license.required» = 1). */
export const licenseRequired = (texts: Record<string, string>) => EDITION === 'store' || texts['license.required'] === '1';

const today = () => new Date().toISOString().slice(0, 10);

export async function checkLicense(texts: Record<string, string>): Promise<LicState> {
  if (!licenseRequired(texts)) return { state: 'active', exp: null };
  const code = deviceCode().replace('-', '');
  const h = await hash16('lic:' + code);
  let entry: { exp: string | null } | undefined | null = null;
  try {
    const r = await fetch(`${__LICENSE_URL__}?t=${Date.now()}`, { cache: 'no-store' });
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
  const trialDays = Math.max(0, Number(texts['license.trialDays'] ?? 7) || 0);
  let first = Number(localStorage.getItem(K_FIRST) || 0);
  if (!first) { first = Date.now(); try { localStorage.setItem(K_FIRST, String(first)); } catch { /* */ } }
  const left = Math.ceil(trialDays - (Date.now() - first) / 86_400_000);
  return left > 0 ? { state: 'trial', daysLeft: left } : { state: 'none' };
}

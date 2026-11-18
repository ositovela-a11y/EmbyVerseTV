// Bloqueo de equipos desde el panel (Estadísticas): config/blocked.json trae huellas de equipos y de usuarios
// bloqueados. Se revisa al abrir, al volver a la app y cada 2 min. El último resultado se guarda para que
// un equipo bloqueado siga bloqueado aunque abra la app sin internet. No borra nada del cliente.
import { EV, hash16 } from './config';
import { appSlug, installId } from './stats';
import type { Account } from './store';

declare const __CONFIG_URL__: string;
declare const __LICENSE_URL__: string;
/** Misma carpeta que las activaciones (config/): también en las apps de vendedor. */
const listUrl = () => (EV.license || __LICENSE_URL__ || __CONFIG_URL__).replace(/[^/]*$/, 'blocked.json');
const K = 'ev.devblock';

export const deviceKey = (id: string) => hash16('blk-dev:' + id.trim());
export const userKey = (app: string, host: string, user: string) => hash16(`blk-user:${app}|${host.trim().toLowerCase()}|${user.trim().toLowerCase()}`);
/** Código corto del equipo (el mismo que ve el dueño en el panel). */
export const shortCode = (id: string) => id.replace(/[^a-zA-Z0-9]/g, '').slice(0, 8).toUpperCase().replace(/^(.{4})/, '$1-');

export function storedBlocked(): boolean { try { return localStorage.getItem(K) === '1'; } catch { return false; } }

/** true = bloqueado · false = no · null = no se pudo revisar (se queda como estaba). */
export async function checkDeviceBlock(acc: Account | null): Promise<boolean | null> {
  try {
    const r = await fetch(`${listUrl()}?t=${Math.floor(Date.now() / 30_000)}`, { cache: 'no-store' });
    if (r.status === 404) { try { localStorage.removeItem(K); } catch { /* */ } return false; }
    if (!r.ok) return null;
    const b = await r.json() as { devices?: Record<string, unknown>; users?: Record<string, unknown> };
    let host = '';
    try { host = acc?.host ? new URL(acc.host).host : acc?.m3u ? new URL(acc.m3u).host : ''; } catch { /* */ }
    const dev = !!b.devices?.[await deviceKey(installId())];
    const usr = !!(acc?.username && b.users?.[await userKey(appSlug(), host, acc.username)]);
    const blocked = dev || usr;
    try { if (blocked) localStorage.setItem(K, '1'); else localStorage.removeItem(K); } catch { /* */ }
    return blocked;
  } catch { return null; }
}

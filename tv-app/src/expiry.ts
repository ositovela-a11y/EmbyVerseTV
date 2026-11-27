// Aviso de vencimiento de la cuenta del proveedor (fecha que da el servidor Xtream). Se muestra al entrar,
// una vez al día, cuando faltan 2 días o menos. Se apaga por app en el panel («expiry.off»).
import type { Account } from './store';

const K_WARN = 'ev.expwarn';
const K_CHECK = 'ev.expcheck';
const today = () => new Date().toISOString().slice(0, 10);

export function expiryDays(texts: Record<string, string>): number {
  const n = parseInt(texts['expiry.days'] ?? '', 10);
  return Number.isFinite(n) && n >= 0 && n <= 60 ? n : 2;
}

export function accountExpiry(acc: Account | null, texts: Record<string, string>, now = Date.now()): { days: number; date: string } | null {
  if (!acc?.expiresAt || texts['expiry.off'] === '1') return null;
  const days = Math.floor((acc.expiresAt - now) / 86_400_000);
  if (days > expiryDays(texts)) return null;
  try { if (localStorage.getItem(K_WARN) === today()) return null; } catch { /* */ }
  return { days, date: new Date(acc.expiresAt).toLocaleDateString('es-MX', { day: 'numeric', month: 'long', year: 'numeric' }) };
}
export function markExpiryWarned() { try { localStorage.setItem(K_WARN, today()); } catch { /* */ } }

/** ¿Toca volver a preguntarle la fecha al servidor? (cada 12 h, por si el cliente ya renovó). */
export function expiryCheckDue(now = Date.now()): boolean {
  try { const t = Number(localStorage.getItem(K_CHECK) || 0); if (now - t < 12 * 3_600_000) return false; localStorage.setItem(K_CHECK, String(now)); } catch { /* */ }
  return true;
}

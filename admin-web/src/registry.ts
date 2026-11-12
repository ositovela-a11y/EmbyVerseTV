// Registro de activaciones (quién es cada equipo, de qué app, cuándo se activó, cuándo vence y cuánto
// pagó). Se guarda CIFRADO en el repositorio (admin/registro.enc.json, fuera del sitio publicado) para
// verlo igual desde cualquier equipo; solo se abre con tu clave del registro. Nombres y teléfonos de
// clientes nunca quedan legibles en GitHub.

export type Device = 'PC' | 'TV' | 'Celular' | 'Otro';
export interface RegEvent { at: string; months: number; exp: string | null; price?: string }
export interface RegEntry {
  hash: string; code: string; name: string; phone?: string;
  /** '' = app principal; si no, el identificador del vendedor. */
  app: string; device: Device; note?: string;
  first: string; history: RegEvent[];
}
export interface Registry { v: 1; entries: Record<string, RegEntry> }
export interface SealedRegistry { v: 1; alg: 'AES-GCM'; kdf: 'PBKDF2-SHA256'; iter: number; salt: string; iv: string; data: string }

const ITER = 250_000;
const b64 = (u: Uint8Array) => { let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode(...u.subarray(i, i + 0x8000)); return btoa(s); };
const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

async function key(pass: string, salt: Uint8Array, iter: number) {
  const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(pass), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

export async function seal(reg: Registry, pass: string): Promise<SealedRegistry> {
  const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12));
  const k = await key(pass, salt, ITER);
  const data = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, k, new TextEncoder().encode(JSON.stringify(reg))));
  return { v: 1, alg: 'AES-GCM', kdf: 'PBKDF2-SHA256', iter: ITER, salt: b64(salt), iv: b64(iv), data: b64(data) };
}

export async function open(s: SealedRegistry, pass: string): Promise<Registry> {
  try {
    const k = await key(pass, unb64(s.salt), s.iter || ITER);
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(s.iv) }, k, unb64(s.data));
    const r = JSON.parse(new TextDecoder().decode(plain)) as Registry;
    return { v: 1, entries: r.entries ?? {} };
  } catch { throw new Error('Clave del registro incorrecta.'); }
}

export const today = () => new Date().toISOString().slice(0, 10);
export const daysUntil = (exp: string) => Math.round((Date.parse(exp + 'T12:00:00') - Date.parse(today() + 'T12:00:00')) / 86_400_000);
export type Status = 'life' | 'active' | 'soon' | 'expired';
export const statusOf = (exp: string | null): Status => !exp ? 'life' : exp < today() ? 'expired' : daysUntil(exp) <= 7 ? 'soon' : 'active';

/** CSV para Excel (separado por comas, con BOM para que respete acentos). */
export function toCsv(rows: string[][]): string {
  const q = (v: string) => (/[",\n;]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  return '﻿' + rows.map((r) => r.map((c) => q(c ?? '')).join(',')).join('\r\n');
}

// Perfiles, favoritos y «seguir viendo» de la cuenta, iguales en TODOS los equipos donde se entra con
// la misma cuenta Xtream (celular, Android TV, Samsung, LG y PC). Se guardan en el servidor de
// estadísticas bajo una llave que se calcula aquí con servidor + usuario + contraseña (nunca se envían).
// Combina en 3 vías (lo último sincronizado, lo de este equipo y lo del servidor): lo agregado o borrado
// en cualquier equipo llega a los demás.
import { serviceUrl } from './stats';
import { quietly, setOnChange, store, type Account, type Fav, type Profile, type Progress } from './store';

export interface SyncProfile { uid: string; name: string; color?: string; avatar?: string; kids?: boolean; blockAdult?: boolean; kidsMinutes?: number; audio?: string; subs?: string }
export interface SyncFav { kind: 'live' | 'movie' | 'series'; id: string; title: string; image?: string | null; at: number }
export interface SyncProg { kind: 'movie' | 'episode'; id: string; seriesId?: string; title: string; image?: string | null; pos: number; dur: number; at: number; season?: number; episode?: number; ext?: string; done?: boolean }
export interface SyncDoc { v: 1; profiles: SyncProfile[]; favs: Record<string, SyncFav[]>; prog: Record<string, SyncProg[]> }
export const EMPTY: SyncDoc = { v: 1, profiles: [], favs: {}, prog: {} };
const MAX_FAVS = 300, MAX_PROG = 60;

const J = (x: unknown) => JSON.stringify(x);
/** Combina listas en 3 vías por llave. */
export function merge3<T>(base: T[] | undefined, local: T[], remote: T[], key: (x: T) => string, pick: (l: T, r: T, b: T | undefined) => T): T[] {
  const B = new Map((base ?? []).map((x) => [key(x), x])), L = new Map(local.map((x) => [key(x), x])), R = new Map(remote.map((x) => [key(x), x]));
  const out: T[] = [];
  const keys = [...R.keys(), ...[...L.keys()].filter((k) => !R.has(k))];
  for (const k of keys) {
    const l = L.get(k), r = R.get(k), b = B.get(k);
    if (l && r) out.push(pick(l, r, b));
    else if (l) { if (!base || !b) out.push(l); }        // nuevo aquí (si estaba antes, lo borraron en otro equipo)
    else if (r) { if (!base || !b) out.push(r); }        // nuevo en otro equipo (si estaba antes, lo borraron aquí)
  }
  return out;
}
// Gana el más reciente; los datos que solo trae el otro (p. ej. «ext» de la TV) se conservan.
const newer = <T extends { at: number }>(l: T, r: T): T => (l.at >= r.at ? { ...r, ...l } : { ...l, ...r });
function pickProfile(l: SyncProfile, r: SyncProfile, b: SyncProfile | undefined): SyncProfile {
  const out: Record<string, unknown> = {};
  for (const f of new Set([...Object.keys(l), ...Object.keys(r)])) {
    const lv = (l as any)[f], rv = (r as any)[f];
    out[f] = b && J(lv) !== J((b as any)[f]) ? lv : rv; // cambiado aquí → gana lo de aquí; si no, lo del servidor
    if (out[f] === undefined) delete out[f];
  }
  return out as unknown as SyncProfile;
}
function dedupeProg(l: SyncProg[]): SyncProg[] {
  const seen = new Set<string>(); const out: SyncProg[] = [];
  for (const p of l.slice().sort((a, b) => b.at - a.at)) { const k = p.seriesId ? 's:' + p.seriesId : p.kind + ':' + p.id; if (!seen.has(k)) { seen.add(k); out.push(p); } }
  return out.slice(0, MAX_PROG);
}
export function mergeDoc(base: SyncDoc | null | undefined, local: SyncDoc, remote: SyncDoc): SyncDoc {
  const profiles = merge3(base?.profiles, local.profiles, remote.profiles, (p) => p.uid, pickProfile);
  const favs: SyncDoc['favs'] = {}, prog: SyncDoc['prog'] = {};
  for (const p of profiles) {
    favs[p.uid] = merge3(base?.favs?.[p.uid], local.favs[p.uid] ?? [], remote.favs?.[p.uid] ?? [], (f) => f.kind + ':' + f.id, newer).sort((a, b) => b.at - a.at).slice(0, MAX_FAVS);
    prog[p.uid] = dedupeProg(merge3(base?.prog?.[p.uid], local.prog[p.uid] ?? [], remote.prog?.[p.uid] ?? [], (g) => g.kind + ':' + g.id, newer));
  }
  return { v: 1, profiles, favs, prog };
}
/** Primera vez en un equipo: sus perfiles toman el código del perfil con el MISMO nombre en la cuenta (no se duplican). */
export function adoptUids<P extends { name: string; uid?: string }>(local: P[], remote: SyncProfile[], gen: () => string): P[] {
  const used = new Set(local.map((p) => p.uid).filter(Boolean));
  return local.map((p) => {
    if (p.uid) return p;
    const m = remote.find((r) => !used.has(r.uid) && r.name.trim().toLowerCase() === p.name.trim().toLowerCase());
    const uid = m?.uid ?? gen(); used.add(uid); return { ...p, uid };
  });
}

// ---------------------------------------------------------------- equipo (TV / PC)
type LocalProfile = Profile & { uid?: string };
const SYNC_FIELDS = ['avatar', 'kids', 'blockAdult', 'kidsMinutes', 'audio', 'subs'] as const; // el PIN se queda en cada equipo
const gen = () => { const a = new Uint8Array(9); crypto.getRandomValues(a); return 'p' + Array.from(a, (b) => b.toString(16).padStart(2, '0')).join(''); };
// Se parte de lo ya sincronizado: así los datos que este equipo no maneja (p. ej. el color del celular) no se pierden.
const toSyncProfile = (p: LocalProfile, base?: SyncProfile): SyncProfile => {
  const o: any = { ...(base ?? {}), uid: p.uid!, name: p.name };
  for (const f of SYNC_FIELDS) { if ((p as any)[f] !== undefined) o[f] = (p as any)[f]; else delete o[f]; }
  return o;
};
const toSyncProg = (p: Progress): SyncProg => ({ ...p });

export function localDoc(profiles: LocalProfile[], base?: SyncDoc | null): SyncDoc {
  const doc: SyncDoc = { v: 1, profiles: profiles.map((p) => toSyncProfile(p, base?.profiles.find((b) => b.uid === p.uid))), favs: {}, prog: {} };
  for (const p of profiles) {
    doc.favs[p.uid!] = store.favs(p.id).map((f) => ({ kind: f.kind, id: f.id, title: f.title, image: f.image ?? null, at: f.at }));
    doc.prog[p.uid!] = store.progress(p.id).map(toSyncProg);
  }
  return doc;
}
export function applyDoc(doc: SyncDoc, profiles: LocalProfile[]) {
  const next: LocalProfile[] = doc.profiles.map((sp) => {
    const cur = profiles.find((p) => p.uid === sp.uid);
    const base: any = cur ? { id: cur.id, pin: cur.pin, uid: sp.uid } : { id: sp.uid, uid: sp.uid, avatar: '🙂' };
    const o: any = { ...base, name: sp.name };
    for (const f of SYNC_FIELDS) if ((sp as any)[f] !== undefined) o[f] = (sp as any)[f]; else if (cur) delete o[f];
    if (!o.avatar) o.avatar = cur?.avatar ?? '🙂';
    return o as LocalProfile;
  });
  if (!next.length) return; // nunca dejar el equipo sin perfiles
  store.setProfiles(next);
  for (const p of next) {
    store.setFavs(p.id, (doc.favs[p.uid!] ?? []) as Fav[]);
    try { localStorage.setItem(`ev.prog.${p.id}`, JSON.stringify((doc.prog[p.uid!] ?? []) as Progress[])); } catch { /* */ }
  }
  if (!next.some((p) => p.id === store.activeProfileId())) store.setActiveProfile(next[0]!.id);
}

/** Llave de la cuenta (sha256 en hex) a partir de servidor + usuario + contraseña. */
export async function accountKey(acc: Account): Promise<string | null> {
  if (!acc.username || !acc.password || acc.m3u) return null;
  let host = ''; try { host = new URL(/^https?:\/\//i.test(acc.host) ? acc.host : 'http://' + acc.host).hostname.toLowerCase(); } catch { return null; }
  return sha256Hex(`ev-sync-v1|${host}|${acc.username}|${acc.password}`);
}

const BASE_KEY = 'ev.sync.base';
let running: Promise<string> | null = null;
let timer: ReturnType<typeof setTimeout> | undefined;
/** Avisa que algo cambió aquí: se sincroniza en unos segundos (agrupando varios cambios). */
setOnChange(() => syncSoon());
export function syncSoon(ms = 4000) { clearTimeout(timer); timer = setTimeout(() => { void syncAccount(); }, ms); }

export function syncAccount(): Promise<string> {
  if (running) return running;
  running = (async () => {
    try {
      const acc = store.account(); if (!acc) return 'sin cuenta';
      const key = await accountKey(acc); if (!key) return 'sin cuenta Xtream';
      const url = await serviceUrl(); if (!url) return 'sin servidor';
      const endpoint = `${url}/v1/sync/${key}`;
      let saved: { k: string; d: SyncDoc } | null = null; try { saved = JSON.parse(localStorage.getItem(BASE_KEY) || 'null'); } catch { /* */ }
      const base = saved?.k === key.slice(0, 16) ? saved.d : null;
      let r = await (await fetch(endpoint, { cache: 'no-store' })).json();
      for (let i = 0; i < 3; i++) {
        const remote: SyncDoc = r.data && Array.isArray(r.data.profiles) ? r.data : EMPTY;
        const locals = adoptUids(store.profiles() as LocalProfile[], remote.profiles, gen);
        const merged = mergeDoc(base, localDoc(locals, base), remote);
        quietly(() => applyDoc(merged, locals));
        try { window.dispatchEvent(new Event('ev-synced')); } catch { /* */ }
        try { localStorage.setItem(BASE_KEY, JSON.stringify({ k: key.slice(0, 16), d: merged })); } catch { /* */ }
        if (J(merged) === J(r.data)) return 'ok';
        const put = await fetch(endpoint, { method: 'PUT', headers: { 'content-type': 'application/json' }, body: J({ rev: r.rev ?? 0, data: merged }) });
        if (put.ok) return 'ok';
        if (put.status !== 409) return 'error ' + put.status;
        r = await put.json(); // otro equipo guardó antes: se combina otra vez
      }
      return 'conflicto';
    } catch { return 'sin conexión'; } finally { running = null; }
  })();
  return running;
}

// SHA-256 en JavaScript (algunos TV no traen crypto.subtle en las apps instaladas).
export async function sha256Hex(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  try { if (globalThis.crypto?.subtle) { const d = await crypto.subtle.digest('SHA-256', data); return Array.from(new Uint8Array(d), (b) => b.toString(16).padStart(2, '0')).join(''); } } catch { /* sigue abajo */ }
  const K = [0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
  const H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const l = data.length, n = ((l + 9 + 63) >> 6) << 6, m = new Uint8Array(n); m.set(data); m[l] = 0x80;
  const bits = l * 8; for (let i = 0; i < 8; i++) m[n - 1 - i] = Math.floor(bits / 2 ** (8 * i)) & 0xff;
  const w = new Uint32Array(64), rot = (x: number, s: number) => (x >>> s) | (x << (32 - s));
  for (let o = 0; o < n; o += 64) {
    for (let i = 0; i < 16; i++) w[i] = (m[o + 4 * i]! << 24) | (m[o + 4 * i + 1]! << 16) | (m[o + 4 * i + 2]! << 8) | m[o + 4 * i + 3]!;
    for (let i = 16; i < 64; i++) { const a = w[i - 15]!, b = w[i - 2]!; w[i] = (w[i - 16]! + (rot(a, 7) ^ rot(a, 18) ^ (a >>> 3)) + w[i - 7]! + (rot(b, 17) ^ rot(b, 19) ^ (b >>> 10))) >>> 0; }
    let [a, b, c, d, e, f, g, h] = H as [number, number, number, number, number, number, number, number];
    for (let i = 0; i < 64; i++) {
      const t1 = (h + (rot(e, 6) ^ rot(e, 11) ^ rot(e, 25)) + ((e & f) ^ (~e & g)) + K[i]! + w[i]!) >>> 0;
      const t2 = ((rot(a, 2) ^ rot(a, 13) ^ rot(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) >>> 0;
      h = g; g = f; f = e; e = (d + t1) >>> 0; d = c; c = b; b = a; a = (t1 + t2) >>> 0;
    }
    H[0] = (H[0]! + a) >>> 0; H[1] = (H[1]! + b) >>> 0; H[2] = (H[2]! + c) >>> 0; H[3] = (H[3]! + d) >>> 0;
    H[4] = (H[4]! + e) >>> 0; H[5] = (H[5]! + f) >>> 0; H[6] = (H[6]! + g) >>> 0; H[7] = (H[7]! + h) >>> 0;
  }
  return H.map((x) => x.toString(16).padStart(8, '0')).join('');
}

// Catálogo en memoria (por sesión) con filtros del perfil: infantil, adultos y carpetas bloqueadas.
import { Xtream, type Category, type Live, type Vod, type Series, isAdult, isViolent, isKidsCat, norm } from './xtream';
import { store, type Profile } from './store';

type Kind = 'live' | 'vod' | 'series';
const cats: Partial<Record<Kind, Promise<Category[]>>> = {};
const lists = new Map<string, Promise<any[]>>();
let allVod: Promise<Vod[]> | null = null;
let allSeries: Promise<Series[]> | null = null;
let allLive: Promise<Live[]> | null = null;
let x: Xtream | null = null;

export function setClient(c: Xtream | null) { x = c; clearCache(); }
export function client() { if (!x) throw new Error('sin cuenta'); return x; }
export function clearCache() { (Object.keys(cats) as Kind[]).forEach((k) => delete cats[k]); lists.clear(); allVod = allSeries = allLive = null; }

/** ¿El perfil puede ver esta carpeta? */
export function allowed(p: Profile, catName: string, kidsCatsExist: boolean): boolean {
  if (p.kids) return kidsCatsExist ? isKidsCat(catName) : !(isAdult(catName) || isViolent(catName));
  if (p.blockAdult && isAdult(catName)) return false;
  return true;
}

export async function categories(kind: Kind): Promise<Category[]> {
  const p = store.activeProfile();
  const l = await (cats[kind] ??= client().categories(kind).catch((e) => { delete cats[kind]; throw e; }));
  const kidsExist = l.some((c) => isKidsCat(c.name));
  return l.filter((c) => allowed(p, c.name, kidsExist));
}

async function allowedCatIds(kind: Kind) { return new Set((await categories(kind)).map((c) => c.id)); }

export async function liveIn(cat?: string): Promise<Live[]> {
  const ok = await allowedCatIds('live');
  const key = `live|${cat ?? ''}`;
  const l = await (lists.get(key) ?? (lists.set(key, cat ? client().live(cat) : (allLive ??= client().live())), lists.get(key)!));
  return (l as Live[]).filter((c) => ok.has(c.cat));
}

export async function vodIn(cat?: string): Promise<Vod[]> {
  const ok = await allowedCatIds('vod');
  if (!cat) return (await (allVod ??= client().vod())).filter((m) => ok.has(m.cat));
  const key = `vod|${cat}`;
  const l = await (lists.get(key) ?? (lists.set(key, client().vod(cat)), lists.get(key)!));
  return (l as Vod[]).filter((m) => ok.has(m.cat) && !(store.activeProfile().kids && m.genre && isViolent(m.genre)));
}

export async function seriesIn(cat?: string): Promise<Series[]> {
  const ok = await allowedCatIds('series');
  if (!cat) return (await (allSeries ??= client().series())).filter((m) => ok.has(m.cat));
  const key = `series|${cat}`;
  const l = await (lists.get(key) ?? (lists.set(key, client().series(cat)), lists.get(key)!));
  return (l as Series[]).filter((m) => ok.has(m.cat));
}

export async function allMovies() { return vodIn(); }
export async function allShows() { return seriesIn(); }
export async function allChannels() { return liveIn(); }

/** Lo más nuevo (por fecha en que el proveedor lo agregó). */
export async function newestMovies(n = 40) { return (await allMovies()).slice().sort((a, b) => (b.added ?? 0) - (a.added ?? 0)).slice(0, n); }

// ---------------- búsqueda inteligente ----------------
function editDistance(a: string, b: string) {
  if (Math.abs(a.length - b.length) > 2) return 3;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    for (let j = 1; j <= b.length; j++) cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    prev = cur;
  }
  return prev[b.length];
}

export function matcher(query: string) {
  const q = norm(query); const words = q.split(' ').filter((w) => w.length >= 2); const compact = q.replace(/ /g, '');
  const exact = (name: string, genre?: string) => { const k = norm(name); return k.replace(/ /g, '').includes(compact) || (words.length > 0 && words.every((w) => k.includes(w))) || (!!genre && norm(genre).includes(q)); };
  const fuzzy = (name: string) => { const kw = norm(name).split(' '); return words.length > 0 && words.every((w) => kw.some((k) => k.length >= 2 && editDistance(k, w) <= (w.length >= 7 ? 2 : 1))); };
  const rank = (name: string) => { const k = norm(name); return k === q ? 0 : k.startsWith(q) ? 1 : k.includes(' ' + q) ? 2 : 3; };
  return { exact, fuzzy, rank, q };
}

export async function search(query: string) {
  const m = matcher(query);
  if (m.q.length < 2) return { live: [] as Live[], movies: [] as Vod[], series: [] as Series[] };
  const [lv, mv, se] = await Promise.all([allChannels().catch(() => []), allMovies().catch(() => []), allShows().catch(() => [])]);
  const pick = <T extends { name: string; genre?: string }>(l: T[]) => {
    let r = l.filter((i) => m.exact(i.name, i.genre));
    if (!r.length && m.q.length >= 4) r = l.filter((i) => m.fuzzy(i.name));
    return r.sort((a, b) => m.rank(a.name) - m.rank(b.name)).slice(0, 60);
  };
  return { live: pick(lv as Live[]), movies: pick(mv as Vod[]), series: pick(se as Series[]) };
}

// ---------------- sagas ----------------
export function sagaKey(title: string) {
  let t = title.split(':')[0].split(' - ')[0];
  t = norm(t).replace(/\b(19|20)\d{2}\b/g, ' ').replace(/\b(parte|part|capitulo|chapter|vol|volumen)\s*\w*\s*$/, ' ').replace(/\s+(\d{1,2}|ii|iii|iv|v|vi|vii|viii|ix|x)\s*$/, ' ');
  return t.trim().replace(/\s+/g, ' ');
}

export async function sagas(limit = 20) {
  const movies = await allMovies();
  const groups = new Map<string, Vod[]>();
  movies.forEach((m) => { const k = sagaKey(m.name); if (k.length < 4) return; (groups.get(k) ?? groups.set(k, []).get(k)!).push(m); });
  const out: { key: string; name: string; poster?: string; items: Vod[] }[] = [];
  groups.forEach((l, k) => {
    const uniq = l.filter((m, i) => l.findIndex((o) => norm(o.name) === norm(m.name)) === i);
    if (uniq.length >= 2 && uniq.length <= 12 && uniq.some((m) => norm(m.name) !== k)) {
      uniq.sort((a, b) => (a.year ?? 9999) - (b.year ?? 9999));
      out.push({ key: k, name: uniq[0].name.split(':')[0].replace(/\s*\(?(19|20)\d{2}\)?/g, '').replace(/\s+(\d{1,2}|II|III|IV)\s*$/, '').trim(), poster: uniq[0].poster, items: uniq });
    }
  });
  return out.sort((a, b) => b.items.length - a.items.length).slice(0, limit);
}

// ---------------- partidos del día (por nombre de canal) ----------------
const MATCH = /(\bvs\.?\b|\bv\b|\s-\s|\s–\s).*(\d{1,2}[:.]\d{2})|(\d{1,2}[:.]\d{2}).*(\bvs\.?\b)/i;
export async function todayEvents(): Promise<Live[]> {
  const l = await allChannels().catch(() => [] as Live[]);
  return l.filter((c) => MATCH.test(c.name) || /\b(evento|event|ppv|en vivo|live)\b.*\d{1,2}[:.]\d{2}/i.test(c.name)).slice(0, 30);
}

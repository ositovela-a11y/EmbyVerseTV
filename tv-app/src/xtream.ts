// Cliente Xtream Codes (player_api.php): el mismo proveedor que usa la app Android.
import type { Account } from './store';

export interface Category { id: string; name: string }
export interface Live { id: string; name: string; logo?: string; cat: string; num?: number; epgId?: string }
export interface Vod { id: string; name: string; poster?: string; cat: string; rating?: number; year?: number; added?: number; ext?: string; genre?: string }
export interface Series { id: string; name: string; poster?: string; cat: string; rating?: number; year?: number; plot?: string; genre?: string; backdrop?: string }
export interface Episode { id: string; title: string; season: number; episode: number; ext?: string; image?: string; plot?: string; duration?: number }
export interface MovieInfo { plot?: string; genre?: string; backdrop?: string; duration?: number; age?: string; cast?: string; director?: string; trailer?: string }
export interface EpgItem { title: string; start: number; end: number; desc?: string }

const base = (h: string) => h.replace(/\/+$/, '').replace(/\/player_api\.php.*$/, '');
const enc = encodeURIComponent;
const str = (v: unknown) => (v == null ? undefined : String(v));
const num = (v: unknown) => { const n = Number(v); return Number.isFinite(n) ? n : undefined; };
const img = (v: unknown) => { const s = str(v)?.trim(); return s && /^https?:\/\//i.test(s) ? s.replace(/ /g, '%20') : undefined; };

export class Xtream {
  constructor(public acc: Account) {}

  private api(action = '', extra = '') {
    return `${base(this.acc.host)}/player_api.php?username=${enc(this.acc.username)}&password=${enc(this.acc.password)}${action ? `&action=${action}` : ''}${extra}`;
  }

  private async json(url: string, timeoutMs = 25000): Promise<any> {
    const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), timeoutMs);
    try {
      const r = await fetch(url, { signal: ctl.signal as any });
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return await r.json();
    } finally { clearTimeout(t); }
  }

  /** Inicia sesión: devuelve la fecha de vencimiento (o null) o lanza un error entendible. */
  async login(): Promise<{ expiresAt: number | null }> {
    let j: any;
    try { j = await this.json(this.api(), 15000); } catch (e: any) { throw new Error(e?.name === 'AbortError' ? 'El servidor no responde.' : 'No se pudo conectar con el servidor. Revisa la dirección e internet.'); }
    const u = j?.user_info;
    if (!u || u.auth === 0 || u.auth === '0') throw new Error('Usuario o contraseña incorrectos.');
    if (u.status && String(u.status).toLowerCase() !== 'active') throw new Error(`Tu cuenta no está activa (${u.status}).`);
    const exp = num(u.exp_date);
    return { expiresAt: exp ? exp * 1000 : null };
  }

  async categories(kind: 'live' | 'vod' | 'series'): Promise<Category[]> {
    const a = kind === 'live' ? 'get_live_categories' : kind === 'vod' ? 'get_vod_categories' : 'get_series_categories';
    const l = await this.json(this.api(a));
    return (Array.isArray(l) ? l : []).map((c: any) => ({ id: String(c.category_id), name: String(c.category_name ?? '') }));
  }

  async live(cat?: string): Promise<Live[]> {
    const l = await this.json(this.api('get_live_streams', cat ? `&category_id=${enc(cat)}` : ''), 60000);
    return (Array.isArray(l) ? l : []).map((s: any) => ({ id: String(s.stream_id), name: String(s.name ?? ''), logo: img(s.stream_icon), cat: String(s.category_id ?? ''), num: num(s.num), epgId: str(s.epg_channel_id) }));
  }

  async vod(cat?: string): Promise<Vod[]> {
    const l = await this.json(this.api('get_vod_streams', cat ? `&category_id=${enc(cat)}` : ''), 90000);
    return (Array.isArray(l) ? l : []).map((s: any) => ({ id: String(s.stream_id), name: String(s.name ?? ''), poster: img(s.stream_icon), cat: String(s.category_id ?? ''),
      rating: num(s.rating), year: num(s.year) ?? num(String(s.releaseDate ?? s.release_date ?? '').slice(0, 4)), added: num(s.added), ext: str(s.container_extension), genre: str(s.genre) }));
  }

  async series(cat?: string): Promise<Series[]> {
    const l = await this.json(this.api('get_series', cat ? `&category_id=${enc(cat)}` : ''), 90000);
    return (Array.isArray(l) ? l : []).map((s: any) => ({ id: String(s.series_id), name: String(s.name ?? ''), poster: img(s.cover), cat: String(s.category_id ?? ''),
      rating: num(s.rating), year: num(String(s.releaseDate ?? s.release_date ?? '').slice(0, 4)), plot: str(s.plot), genre: str(s.genre), backdrop: img(Array.isArray(s.backdrop_path) ? s.backdrop_path[0] : s.backdrop_path) }));
  }

  async movieInfo(id: string): Promise<MovieInfo> {
    const j = await this.json(this.api('get_vod_info', `&vod_id=${enc(id)}`));
    const i = j?.info ?? {};
    const yt = str(i.youtube_trailer ?? i.trailer);
    return { plot: str(i.plot ?? i.description), genre: str(i.genre), backdrop: img(Array.isArray(i.backdrop_path) ? i.backdrop_path[0] : i.backdrop_path),
      duration: num(i.duration_secs), age: mexicanAge(str(i.mpaa_rating ?? i.mpaa ?? i.certification ?? i.age)), cast: str(i.cast ?? i.actors), director: str(i.director),
      trailer: yt && /^[A-Za-z0-9_-]{6,20}$/.test(yt) ? `https://www.youtube.com/watch?v=${yt}` : undefined };
  }

  async episodes(seriesId: string): Promise<{ episodes: Episode[]; plot?: string; backdrop?: string }> {
    const j = await this.json(this.api('get_series_info', `&series_id=${enc(seriesId)}`));
    const eps: Episode[] = [];
    const src = j?.episodes ?? {};
    const lists: any[] = Array.isArray(src) ? src : Object.values(src);
    lists.forEach((l: any) => (Array.isArray(l) ? l : [l]).forEach((e: any) => {
      if (!e?.id) return;
      eps.push({ id: String(e.id), title: String(e.title ?? `Episodio ${e.episode_num}`), season: num(e.season) ?? 1, episode: num(e.episode_num) ?? 0,
        ext: str(e.container_extension), image: img(e.info?.movie_image), plot: str(e.info?.plot), duration: num(e.info?.duration_secs) });
    }));
    eps.sort((a, b) => a.season - b.season || a.episode - b.episode);
    return { episodes: eps, plot: str(j?.info?.plot), backdrop: img(Array.isArray(j?.info?.backdrop_path) ? j.info.backdrop_path[0] : j?.info?.backdrop_path) };
  }

  /** Guía corta (ahora y lo que sigue). Los títulos vienen en Base64. */
  async shortEpg(streamId: string, limit = 6): Promise<EpgItem[]> {
    try {
      const j = await this.json(this.api('get_short_epg', `&stream_id=${enc(streamId)}&limit=${limit}`), 10000);
      return (j?.epg_listings ?? []).map((e: any) => ({ title: b64(e.title), desc: b64(e.description), start: (num(e.start_timestamp) ?? Date.parse(e.start) / 1000) * 1000,
        end: (num(e.stop_timestamp) ?? Date.parse(e.end ?? e.stop) / 1000) * 1000 })).filter((e: EpgItem) => e.title);
    } catch { return []; }
  }

  liveUrl(id: string, fmt: 'ts' | 'm3u8' = 'ts') { return `${base(this.acc.host)}/live/${enc(this.acc.username)}/${enc(this.acc.password)}/${id}.${fmt}`; }
  movieUrl(id: string, ext?: string) { return `${base(this.acc.host)}/movie/${enc(this.acc.username)}/${enc(this.acc.password)}/${id}.${ext || 'mp4'}`; }
  episodeUrl(id: string, ext?: string) { return `${base(this.acc.host)}/series/${enc(this.acc.username)}/${enc(this.acc.password)}/${id}.${ext || 'mp4'}`; }
}

function b64(v: unknown): string {
  const s = str(v) ?? '';
  try { return decodeURIComponent(escape(atob(s))); } catch { return s; }
}

/** Escala de México (AA, A, B, B15, C, D) desde MPAA, TV o edad. */
export function mexicanAge(raw?: string): string | undefined {
  const v = raw?.trim().toUpperCase().replace(/\s/g, '');
  if (!v || v === '0' || v === 'N/A' || v === 'NR') return undefined;
  if (['AA', 'A', 'B', 'B15', 'C', 'D'].includes(v)) return v;
  const map: Record<string, string> = { G: 'AA', 'TV-Y': 'AA', 'TV-G': 'AA', 'TV-Y7': 'A', PG: 'A', 'TV-PG': 'A', 'PG-13': 'B', 'TV-14': 'B15', R: 'C', 'TV-MA': 'C', 'NC-17': 'D', X: 'D' };
  if (map[v]) return map[v];
  const n = parseInt(v.replace(/\D/g, ''), 10);
  if (!Number.isFinite(n)) return undefined;
  return n <= 6 ? 'AA' : n <= 11 ? 'A' : n <= 14 ? 'B' : n <= 17 ? 'B15' : n <= 18 ? 'C' : 'D';
}

// --- Filtros (iguales a Android) ---
export const isAdult = (s: string) => /(adult|adulto|xxx|\+\s?18|18\s?\+|porn|erotic|erótic|hentai|\bsexy?\b|for adults)/i.test(s);
export const isViolent = (s: string) => /(terror|horror|miedo|gore|slasher|zombi|crimen|crime|criminal|narco|mafia|guerra|\bwar\b|b[eé]lic|thriller|suspenso|violen|asesin|policiac|policíac|acci[oó]n\s*\+?\s*18|ufc|mma|lucha libre extrema)/i.test(s);
export const isKidsCat = (s: string) => /(kids|infantil|niñ[oa]s|ninos|children|cartoon|caricatura|dibujos|animad|disney|nick|junior|baby|pequeñ|familia|family)/i.test(s) && !isAdult(s) && !isViolent(s);

/** Texto para buscar: minúsculas, sin acentos ni signos. */
export const norm = (s: string) => s.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9ñ]+/g, ' ').trim();

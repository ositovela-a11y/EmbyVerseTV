// Listas M3U / M3U8 (enlace o archivo): se leen una vez y se presentan con la misma forma que una
// cuenta Xtream (TV en vivo por carpetas, películas y series), así el resto de la app no cambia.
import type { Account } from './store';
import { Xtream, type Category, type Live, type Vod, type Series, type Episode, type MovieInfo, type EpgItem } from './xtream';

export interface M3uItem { title: string; url: string; logo?: string; group: string; epgId?: string; ua?: string; ref?: string; num?: number }
interface Parsed { live: M3uItem[]; movies: M3uItem[]; episodes: (M3uItem & { show: string; season: number; episode: number })[] }

const VIDEO_EXT = /\.(mp4|mkv|avi|mov|m4v|webm|wmv|flv|mpg|mpeg)(\?|$)/i;
const SXE = /^(.*?)[\s._\-–|:]*\bS(\d{1,2})\s*E(\d{1,3})\b/i;

/** Lector tolerante: #EXTINF con atributos, #EXTGRP, #EXTVLCOPT, encabezados estilo Kodi («url|User-Agent=…») y #EXTHTTP. */
export function parseM3u(text: string, baseUrl?: string): M3uItem[] {
  const out: M3uItem[] = [];
  let info: { title: string; attrs: Record<string, string> } | null = null;
  let grp: string | undefined; let ua: string | undefined; let ref: string | undefined;
  const seen = new Set<string>();
  for (let raw of text.split(/\r?\n/)) {
    const line = raw.replace(/^\uFEFF/, '').trim();
    if (!line) continue;
    if (/^#EXTINF:/i.test(line)) {
      const rest = line.slice(8);
      const attrs: Record<string, string> = {};
      rest.replace(/([\w-]+)=("([^"]*)"|'([^']*)'|([^\s,]+))/g, (_m, k, _v, a, b, c) => { attrs[String(k).toLowerCase()] = (a ?? b ?? c ?? '').trim(); return ''; });
      // El título va después de la última coma que no está dentro de comillas.
      let q = false, cut = -1;
      for (let i = 0; i < rest.length; i++) { const ch = rest[i]; if (ch === '"') q = !q; else if (ch === ',' && !q) cut = i; }
      info = { title: (cut >= 0 ? rest.slice(cut + 1) : '').trim(), attrs };
      continue;
    }
    if (/^#EXTGRP:/i.test(line)) { grp = line.slice(8).trim() || undefined; continue; }
    if (/^#EXTVLCOPT:/i.test(line)) {
      const m = /^#EXTVLCOPT:\s*([\w-]+)\s*=\s*(.+)$/i.exec(line);
      if (m) { const k = m[1].toLowerCase(); if (k === 'http-user-agent') ua = m[2].trim(); if (k === 'http-referrer' || k === 'http-referer') ref = m[2].trim(); }
      continue;
    }
    if (/^#EXTHTTP:/i.test(line)) {
      try { const h = JSON.parse(line.slice(9)); for (const [k, v] of Object.entries(h)) { if (/^user-agent$/i.test(k)) ua = String(v); if (/^refer+er$/i.test(k)) ref = String(v); } } catch { /* */ }
      continue;
    }
    if (line.startsWith('#')) continue;
    if (!info) continue;
    let url = line;
    // Kodi: «dirección|User-Agent=...&Referer=...»
    const pipe = url.indexOf('|');
    if (pipe > 0) {
      const opts = url.slice(pipe + 1); url = url.slice(0, pipe);
      for (const kv of opts.split('&')) { const [k, ...v] = kv.split('='); const val = decodeURIComponent(v.join('=') || ''); if (/^user-agent$/i.test(k)) ua = val; if (/^refer+er$/i.test(k)) ref = val; }
    }
    if (!/^https?:\/\//i.test(url)) {
      if (/^[a-z][a-z0-9+.-]*:/i.test(url) || !baseUrl) { info = null; continue; } // rtmp/udp/archivos locales: no se pueden reproducir aquí
      try { url = new URL(url, baseUrl).toString(); } catch { info = null; continue; }
    }
    if (!seen.has(url)) {
      seen.add(url);
      const a = info.attrs;
      const title = info.title || a['tvg-name'] || url.split('/').pop() || 'Canal';
      out.push({ title, url, logo: /^https?:\/\//i.test(a['tvg-logo'] ?? '') ? a['tvg-logo'] : undefined, group: (a['group-title'] || grp || 'Sin categoría').trim(),
        epgId: a['tvg-id'] || undefined, ua: ua ?? a['user-agent'] ?? a['http-user-agent'], ref: ref ?? a['http-referrer'] ?? a['referer'], num: Number(a['tvg-chno']) || undefined });
    }
    info = null; grp = undefined; ua = undefined; ref = undefined;
  }
  return out;
}

/** Una lista «get.php?username=…&password=…» es en realidad una cuenta Xtream: se usa como tal (fichas, guía, temporadas). */
export function xtreamFromM3uUrl(url: string): Account | null {
  try {
    const u = new URL(url);
    const user = u.searchParams.get('username'), pass = u.searchParams.get('password');
    if (!/\/get\.php$/i.test(u.pathname) || !user || !pass) return null;
    return { host: `${u.protocol}//${u.host}`, username: user, password: pass };
  } catch { return null; }
}

function classify(items: M3uItem[]): Parsed {
  const p: Parsed = { live: [], movies: [], episodes: [] };
  for (const it of items) {
    const path = (() => { try { return new URL(it.url).pathname; } catch { return it.url; } })();
    const sxe = SXE.exec(it.title);
    if (/\/series\//i.test(path) || (sxe && VIDEO_EXT.test(path))) {
      const m = sxe ?? /^(.*)$/.exec(it.title)!;
      p.episodes.push({ ...it, show: (m[1] || it.group).replace(/[\s._\-–|:]+$/, '').trim() || it.title, season: Number(sxe?.[2] ?? 1), episode: Number(sxe?.[3] ?? 0) });
    } else if (/\/movie\//i.test(path) || VIDEO_EXT.test(path)) p.movies.push(it);
    else p.live.push(it);
  }
  return p;
}

/** Lista M3U con la misma forma que el cliente Xtream. */
export class M3uSource extends Xtream {
  private data: Parsed | null = null;
  private byId = new Map<string, M3uItem>();

  private async load(): Promise<Parsed> {
    if (this.data) return this.data;
    const src = this.acc.m3u ?? '';
    let text: string;
    const desk = (window as any).evDesktop;
    if (src.startsWith('file:') && desk?.readListFile) text = await desk.readListFile(src.slice(5));
    else {
      const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 60_000);
      try {
        const r = await fetch(src, { signal: ctl.signal as any });
        if (!r.ok) throw new Error(`La lista respondió con un error (HTTP ${r.status}). Revisa el enlace.`);
        text = await r.text();
      } catch (e: any) {
        if (e?.name === 'AbortError') throw new Error('La lista tarda demasiado en descargarse.');
        if (e?.message?.startsWith('La lista')) throw e;
        throw new Error('No se pudo descargar la lista. Revisa el enlace e internet.');
      } finally { clearTimeout(t); }
    }
    if (/^\s*</.test(text)) throw new Error('El enlace no es una lista M3U (devolvió una página web).');
    if (/#EXT-X-(TARGETDURATION|STREAM-INF)/i.test(text))
      throw new Error('Ese enlace es un solo canal (HLS), no una lista de canales.');
    const items = parseM3u(text, src.startsWith('http') ? src : undefined);
    if (!items.length) throw new Error('La lista no tiene canales que se puedan reproducir.');
    this.data = classify(items);
    const reg = (prefix: string, l: M3uItem[]) => l.forEach((it, i) => this.byId.set(`${prefix}${i}`, it));
    reg('l', this.data.live); reg('m', this.data.movies); reg('e', this.data.episodes);
    return this.data;
  }

  async login(): Promise<{ expiresAt: number | null }> { await this.load(); return { expiresAt: null }; }

  private cats(l: M3uItem[]): Category[] {
    const names: string[] = [];
    for (const it of l) if (!names.includes(it.group)) names.push(it.group);
    return names.map((n) => ({ id: n, name: n }));
  }

  async categories(kind: 'live' | 'vod' | 'series'): Promise<Category[]> {
    const d = await this.load();
    return this.cats(kind === 'live' ? d.live : kind === 'vod' ? d.movies : d.episodes);
  }

  async live(cat?: string): Promise<Live[]> {
    const d = await this.load();
    return d.live.map((it, i) => ({ id: `l${i}`, name: it.title, logo: it.logo, cat: it.group, num: it.num ?? i + 1, epgId: it.epgId })).filter((x) => !cat || x.cat === cat);
  }

  async vod(cat?: string): Promise<Vod[]> {
    const d = await this.load();
    return d.movies.map((it, i) => ({ id: `m${i}`, name: it.title, poster: it.logo, cat: it.group, ext: undefined,
      year: Number(/\((19|20)\d{2}\)/.exec(it.title)?.[0].slice(1, 5)) || undefined })).filter((x) => !cat || x.cat === cat);
  }

  async series(cat?: string): Promise<Series[]> {
    const d = await this.load();
    const shows = new Map<string, Series>();
    for (const e of d.episodes) if (!shows.has(e.show)) shows.set(e.show, { id: `s:${e.show}`, name: e.show, poster: e.logo, cat: e.group });
    return [...shows.values()].filter((x) => !cat || x.cat === cat);
  }

  async movieInfo(_id: string): Promise<MovieInfo> { return {}; }

  async episodes(seriesId: string): Promise<{ episodes: Episode[]; plot?: string; backdrop?: string }> {
    const d = await this.load();
    const show = seriesId.replace(/^s:/, '');
    const eps: Episode[] = [];
    d.episodes.forEach((e, i) => { if (e.show === show) eps.push({ id: `e${i}`, title: e.title, season: e.season, episode: e.episode, image: e.logo }); });
    eps.sort((a, b) => a.season - b.season || a.episode - b.episode);
    return { episodes: eps };
  }

  async shortEpg(_id: string): Promise<EpgItem[]> { return []; }

  /** Dirección del canal; «fmt» indica cómo intentarlo cuando la dirección no dice el formato (HLS o .ts). */
  // Sin extensión casi siempre es .ts (listas tipo Xtream): primero .ts y, si falla, HLS (una transmisión .ts no termina
  // nunca y abrirla como HLS se quedaría esperando).
  liveUrl(id: string, fmt: 'ts' | 'm3u8' = 'ts') { return withHint(this.byId.get(id)?.url ?? '', fmt === 'm3u8' ? 'ts' : 'm3u8'); }
  movieUrl(id: string) { return this.byId.get(id)?.url ?? ''; }
  episodeUrl(id: string) { return this.byId.get(id)?.url ?? ''; }
  /** User-Agent y Referer que pide la lista para ese canal (los aplica la app de PC). */
  headersFor(id: string): { ua?: string; ref?: string } { const it = this.byId.get(id); return { ua: it?.ua, ref: it?.ref }; }
}

/** Si la dirección no tiene extensión, se marca (#ev=hls / #ev=ts) para que el reproductor sepa cómo abrirla. */
function withHint(url: string, fmt: 'ts' | 'm3u8') {
  if (!url || /\.(m3u8|ts|mp4|mkv|mpd)(\?|$)/i.test(url.split('#')[0])) return url;
  return `${url.split('#')[0]}#ev=${fmt === 'ts' ? 'ts' : 'hls'}`;
}

/** Cliente correcto según la cuenta guardada. */
export function clientFor(acc: Account): Xtream { return acc.m3u ? new M3uSource(acc) : new Xtream(acc); }

/* EmbyVerse Web — reproductor de listas M3U/M3U8 y servidores Xtream en el navegador.
 * Sin servidor propio: todo se guarda en este navegador (IndexedDB + localStorage).
 * Navegable con ratón, táctil y mando a distancia (flechas + OK + Atrás). */
'use strict';

// ------------------------------------------------------------------ almacenamiento
const store = {
  get(k, d) { try { const v = localStorage.getItem('ev.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('ev.' + k, JSON.stringify(v)); } catch { /* sin almacenamiento */ } },
};
const idb = (() => {
  let dbp = null;
  const open = () => dbp || (dbp = new Promise((res, rej) => {
    try {
      const r = indexedDB.open('embyverse', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('catalogs');
      r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error);
    } catch (e) { rej(e); }
  }));
  const tx = async (mode, fn) => { const db = await open(); return new Promise((res, rej) => { const t = db.transaction('catalogs', mode); const r = fn(t.objectStore('catalogs')); t.oncomplete = () => res(r && r.result); t.onerror = () => rej(t.error); }); };
  return {
    get: (k) => tx('readonly', (s) => s.get(k)).catch(() => null),
    put: (k, v) => tx('readwrite', (s) => s.put(v, k)).catch(() => null),
    del: (k) => tx('readwrite', (s) => s.delete(k)).catch(() => null),
  };
})();

// ------------------------------------------------------------------ utilidades
const $ = (s, r = document) => r.querySelector(s);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const norm = (s) => String(s || '').normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
const uid = () => Math.random().toString(36).slice(2, 10);
const isHttp = (u) => /^https?:\/\/[^\s/]+/i.test(String(u || '').trim());
const pageIsHttps = location.protocol === 'https:';
const insecureOnHttps = (u) => pageIsHttps && /^http:\/\//i.test(u);
const safeImg = (u) => (u && /^https?:\/\//i.test(u) && !insecureOnHttps(u) ? u : '');

class UserError extends Error {}
const MIXED_MSG = 'Esta dirección usa http:// (sin cifrar) y el navegador la bloquea en una página https. Usa una lista o servidor https://, o la app Android de EmbyVerse.';

async function fetchText(url, { timeout = 30000, json = false } = {}) {
  if (!isHttp(url)) throw new UserError('La dirección no es válida. Debe empezar por http:// o https://');
  if (insecureOnHttps(url)) throw new UserError(MIXED_MSG);
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeout);
  let r;
  try { r = await fetch(url, { signal: ctl.signal, credentials: 'omit' }); }
  catch (e) {
    if (e.name === 'AbortError') throw new UserError('El servidor tardó demasiado en responder.');
    throw new UserError('No se pudo conectar. El servidor no está disponible o no permite el acceso desde navegadores (CORS). En ese caso usa la app Android.');
  } finally { clearTimeout(timer); }
  if (r.status === 401 || r.status === 403) throw new UserError(`El servidor rechazó el acceso (HTTP ${r.status}). Revisa usuario y contraseña.`);
  if (r.status === 404) throw new UserError('No se encontró nada en esa dirección (HTTP 404).');
  if (!r.ok) throw new UserError(`El servidor respondió con un error (HTTP ${r.status}).`);
  const text = (await r.text()).replace(/^﻿/, '');
  if (!json) return text;
  try { return JSON.parse(text); } catch { throw new UserError('El servidor no respondió como una API compatible con Xtream Codes.'); }
}

// ------------------------------------------------------------------ análisis M3U (misma lógica que la app Android)
function parseAttrs(s) {
  const out = {}; const re = /([\w:-]+)=(?:"([^"]*)"|'([^']*)'|([^\s"']+))/g; let m;
  while ((m = re.exec(s))) out[m[1].toLowerCase()] = (m[2] ?? m[3] ?? m[4] ?? '').trim();
  return out;
}
function splitInfo(rest) {
  let q = null;
  for (let i = 0; i < rest.length; i++) {
    const c = rest[i];
    if (q) { if (c === q) q = null; continue; }
    if (c === '"' || (c === "'" && rest[i - 1] === '=')) { q = c; continue; }
    if (c === ',') return [parseAttrs(rest.slice(0, i)), rest.slice(i + 1).trim()];
  }
  return [parseAttrs(rest), ''];
}
const VOD_EXT = new Set(['mp4', 'mkv', 'avi', 'mov', 'm4v', 'wmv', 'webm', 'mpg', 'mpeg', 'flv']);
const LIVE_EXT = new Set(['ts', 'm3u8', 'm3u']);
const EP_RE = [
  /^(.*?)[\s._\-:|]*[Ss](\d{1,2})[\s._-]*[EeXx](\d{1,4})\b[\s._\-:|]*(.*)$/,
  /^(.*?)[\s._\-:|]+(\d{1,2})[xX](\d{1,3})\b[\s._\-:|]*(.*)$/,
  /^(.*?)[\s._\-:|]*(?:Temporada|Season|Temp\.?)\s*(\d{1,2})[\s,._\-:|]*(?:Episodio|Episode|Cap(?:í|i)tulo|Cap\.?|Ep\.?)\s*(\d{1,4})\b[\s._\-:|]*(.*)$/i,
];
const MOVIE_G = /\b(vod|movies?|pel[ií]culas?|films?|cine|cinema|estrenos)\b/i;
const SERIES_G = /\b(series?|tv\s*shows?|shows|novelas?|anime)\b/i;
const LIVE_G = /\b(live|en\s*vivo|directo|24\/7|canales|channels?|sports?|deportes|news|noticias)\b/i;
function extOf(url) { try { const p = new URL(url).pathname; const last = p.split('/').pop(); const d = last.lastIndexOf('.'); return d > 0 ? last.slice(d + 1).toLowerCase() : null; } catch { return null; } }
function episodeOf(title) {
  for (const re of EP_RE) { const m = re.exec(title.trim()); if (m) return { series: m[1].replace(/[\s\-|:._]+$/, '').trim(), season: +m[2], episode: +m[3], title: m[4].trim() || null }; }
  return null;
}
function classify(e) {
  let path = ''; try { path = new URL(e.url).pathname.toLowerCase(); } catch { /* */ }
  if (path.includes('/movie/') || path.includes('/movies/')) return 'movie';
  if (path.includes('/series/')) return 'episode';
  if (path.includes('/live/')) return 'live';
  const t = (e.attrs['tvg-type'] || e.attrs.type || '').toLowerCase();
  if (['movie', 'movies', 'vod'].includes(t)) return 'movie';
  if (['series', 'serie', 'episode'].includes(t)) return 'episode';
  if (['live', 'channel', 'tv'].includes(t)) return 'live';
  const ext = extOf(e.url), ep = !!episodeOf(e.title), g = e.group || '';
  if (ext && VOD_EXT.has(ext)) return ep || SERIES_G.test(g) ? 'episode' : 'movie';
  if (ep && !LIVE_G.test(g) && !LIVE_EXT.has(ext)) return 'episode';
  if (!LIVE_EXT.has(ext) && MOVIE_G.test(g) && !LIVE_G.test(g)) return 'movie';
  return 'live';
}
function resolveUrl(raw, base) {
  if (/^(https?|rtmp|rtsp|udp|rtp):\/\//i.test(raw)) return raw;
  if (/^[a-z]+:/i.test(raw) || !base || !/^https?:/i.test(base)) return null;
  try { return new URL(raw, base).href; } catch { return null; }
}
function parseM3U(text, sourceId, baseUrl) {
  const lines = text.split(/\r?\n/);
  if (/^\s*<(!doctype|html|\?xml)/i.test(lines.slice(0, 3).join('\n'))) throw new UserError('El contenido descargado es una página web, no una lista M3U.');
  let header = {}, info = null, group = null, ua = null, ref = null, sawInfo = false, sawHeader = false, hls = 0, skipped = 0;
  const seen = new Set(), entries = [];
  for (let line of lines) {
    line = line.trim(); if (!line) continue;
    if (/^#EXTM3U/i.test(line)) { sawHeader = true; header = parseAttrs(line.slice(7)); }
    else if (/^#EXTINF:/i.test(line)) { sawInfo = true; const rest = line.slice(8); const i = rest.search(/[ ,\t]/); info = { dur: parseFloat(rest.slice(0, i < 0 ? undefined : i)) || -1, rest: i < 0 ? '' : rest.slice(i) }; }
    else if (/^#EXTGRP:/i.test(line)) group = line.slice(8).trim() || null;
    else if (/^#EXTVLCOPT:/i.test(line)) { const [k, ...v] = line.slice(11).split('='); const key = k.trim().toLowerCase(); if (key === 'http-user-agent') ua = v.join('='); if (key === 'http-referrer' || key === 'http-referer') ref = v.join('='); }
    else if (/^#EXT-X-(TARGETDURATION|MEDIA-SEQUENCE|STREAM-INF)/i.test(line)) hls++;
    else if (line.startsWith('#')) { /* otras directivas */ }
    else {
      if (!info) { skipped++; continue; }
      const url = resolveUrl(line, baseUrl);
      if (url && !seen.has(url)) {
        seen.add(url);
        const [attrs, title] = splitInfo(info.rest);
        entries.push({ title: title || attrs['tvg-name'] || url.split('/').pop(), url, dur: info.dur, attrs, group: (attrs['group-title'] || group || '').trim() || null, ua, ref });
      } else if (!url) skipped++;
      info = null; group = null; ua = null; ref = null;
      if (entries.length >= 200000) break;
    }
  }
  if (hls > 0) throw new UserError('Esta dirección es un único stream HLS, no una lista de canales. Añádela dentro de una lista M3U.');
  if (!sawInfo && !sawHeader) throw new UserError('El contenido no es una lista M3U/M3U8 válida.');

  const live = [], movies = [], seriesMap = new Map();
  for (const e of entries) {
    const kind = classify(e);
    const logo = e.attrs['tvg-logo'] || e.attrs.logo || null;
    if (kind === 'live') live.push({ id: e.url, name: e.title, url: e.url, logo, group: e.group, epg: e.attrs['tvg-id'] || null, num: parseInt(e.attrs['tvg-chno'], 10) || null });
    else if (kind === 'movie') {
      const m = /^(.*?)[\s._-]*[([]?((?:19|20)\d{2})[)\]]?\s*$/.exec(e.title);
      movies.push({ id: e.url, title: m && m[1] ? m[1].trim() : e.title, year: m && m[1] ? +m[2] : null, url: e.url, poster: logo, group: e.group, ext: extOf(e.url) });
    } else {
      const p = episodeOf(e.title);
      const sTitle = (p && p.series) || e.group || e.title;
      const key = norm(sTitle);
      if (!seriesMap.has(key)) seriesMap.set(key, { id: 'series:' + key, title: sTitle, poster: logo, group: e.group, episodes: [] });
      const s = seriesMap.get(key);
      const season = p ? p.season : 1;
      s.episodes.push({ id: e.url, season, episode: p ? p.episode : s.episodes.filter((x) => x.season === season).length + 1, title: (p && p.title) || e.title, url: e.url });
    }
  }
  const series = [...seriesMap.values()].map((s) => ({ ...s, episodes: dedupe(s.episodes, (x) => x.season + ':' + x.episode).sort((a, b) => a.season - b.season || a.episode - b.episode) }));
  return { sourceId, live, movies, series, epgUrl: header['x-tvg-url'] || header['url-tvg'] || null, skipped };
}
const dedupe = (arr, key) => { const s = new Set(); return arr.filter((x) => { const k = key(x); if (s.has(k)) return false; s.add(k); return true; }); };

// ------------------------------------------------------------------ Xtream Codes
const xBase = (u) => { let s = String(u).trim(); if (!/:\/\//.test(s)) s = 'http://' + s; s = s.split('?')[0].replace(/\/(player_api|get|xmltv|panel_api)\.php$/i, ''); return s.replace(/\/+$/, ''); };
const enc = encodeURIComponent;
const xApi = (src, action, extra = '') => `${xBase(src.location)}/player_api.php?username=${enc(src.username)}&password=${enc(src.password)}${action ? '&action=' + action + extra : ''}`;
function parseCredentialUrl(text) {
  try {
    const u = new URL(text.trim());
    const user = u.searchParams.get('username'), pass = u.searchParams.get('password');
    if (user && pass) return { location: xBase(u.href), username: user, password: pass };
  } catch { /* */ }
  return null;
}
async function xtreamAuth(src) {
  if (!src.username || !src.password) throw new UserError('Indica usuario y contraseña.');
  const r = await fetchText(xApi(src), { json: true });
  const ui = r && r.user_info;
  if (!ui) throw new UserError(r && r.server_info ? 'Usuario o contraseña incorrectos.' : 'El servidor no respondió como una API compatible con Xtream Codes.');
  if (String(ui.auth) === '0') throw new UserError('Usuario o contraseña incorrectos.');
  const status = ui.status || 'Active';
  if (!/^active$/i.test(status)) throw new UserError(`La cuenta del proveedor no está activa (estado: ${status}).`);
  return { status, exp: ui.exp_date ? +ui.exp_date : null, max: ui.max_connections ? +ui.max_connections : null, formats: ui.allowed_output_formats || [] };
}
async function xtreamCatalog(src, progress = () => {}) {
  // Autenticación, categorías y contenido se piden TODOS A LA VEZ (antes eran 3 rondas seguidas).
  const warnings = [];
  const get = async (action, label) => {
    try { const r = await fetchText(xApi(src, action), { json: true, timeout: 90000 }); if (label) progress(label); return Array.isArray(r) ? r : Object.values(r || {}); }
    catch (e) { if (e instanceof UserError && /usa http:\/\//.test(e.message)) throw e; warnings.push(`${action}: ${e.message}`); return []; }
  };
  const catsOf = (arr) => Object.fromEntries(arr.map((c) => [String(c.category_id), c.category_name]));
  const authP = xtreamAuth(src);
  const lcP = get('get_live_categories'), vcP = get('get_vod_categories'), scP = get('get_series_categories');
  const lsP = get('get_live_streams', 'canales'), vsP = get('get_vod_streams', 'películas'), ssP = get('get_series', 'series');
  const acc = await authP; // si las credenciales fallan, se informa en cuanto responde la autenticación
  progress('autenticado');
  const base = xBase(src.location), u = enc(src.username), p = enc(src.password);
  const fmt = acc.formats.includes('m3u8') || !acc.formats.length ? 'm3u8' : 'ts';
  const lc = catsOf(await lcP), ls = await lsP;
  const live = dedupe(ls.filter((x) => x.stream_id != null), (x) => x.stream_id).map((x) => ({ id: String(x.stream_id), name: x.name || 'Canal', url: `${base}/live/${u}/${p}/${x.stream_id}.${fmt}`, logo: x.stream_icon || null, group: lc[String(x.category_id)] || null, epg: x.epg_channel_id || null, num: +x.num || null }));
  // Los canales ya se pueden ver mientras siguen llegando películas y series (suelen ser las listas más pesadas).
  if (live.length) progress('parcial', { sourceId: src.id, live, movies: [], series: [], warnings: [], partial: true });
  const [vc, vs, sc, ss] = [catsOf(await vcP), await vsP, catsOf(await scP), await ssP];
  const movies = dedupe(vs.filter((x) => x.stream_id != null), (x) => x.stream_id).map((x) => ({ id: String(x.stream_id), title: x.name || 'Película', url: `${base}/movie/${u}/${p}/${x.stream_id}.${x.container_extension || 'mp4'}`, poster: x.stream_icon || null, group: vc[String(x.category_id)] || null, year: +x.year || null, rating: +x.rating || null, ext: x.container_extension || 'mp4', added: +x.added || null }));
  const series = dedupe(ss.filter((x) => x.series_id != null), (x) => x.series_id).map((x) => ({ id: String(x.series_id), title: x.name || 'Serie', poster: x.cover || null, backdrop: Array.isArray(x.backdrop_path) ? x.backdrop_path[0] || null : null, group: sc[String(x.category_id)] || null, plot: x.plot || null, year: parseInt(x.releaseDate || x.release_date, 10) || null, rating: +x.rating || null, episodes: null, added: +x.last_modified || null }));
  if (!live.length && !movies.length && !series.length) throw new UserError('La cuenta es válida pero el servidor no devolvió contenido. ' + warnings.join(' '));
  return { sourceId: src.id, live, movies, series, warnings, account: acc };
}
async function xtreamMovieInfo(src, id) {
  const r = await fetchText(xApi(src, 'get_vod_info', '&vod_id=' + enc(id)), { json: true });
  const i = (r && !Array.isArray(r.info) && r.info) || {};
  return { plot: i.plot || i.description || null, genre: i.genre || null, duration: i.duration || null, backdrop: Array.isArray(i.backdrop_path) ? i.backdrop_path[0] : null, cast: i.cast || null, director: i.director || null };
}
async function xtreamEpisodes(src, seriesId) {
  const r = await fetchText(xApi(src, 'get_series_info', '&series_id=' + enc(seriesId)), { json: true });
  const eps = r && r.episodes; const base = xBase(src.location), u = enc(src.username), p = enc(src.password);
  const list = Array.isArray(eps) ? eps.flat() : Object.entries(eps || {}).flatMap(([s, arr]) => (arr || []).map((e) => ({ ...e, season: e.season ?? +s })));
  return list.filter((e) => e && e.id != null).map((e) => ({ id: String(e.id), season: +e.season || 1, episode: +e.episode_num || 0, title: e.title || 'Episodio', url: `${base}/series/${u}/${p}/${e.id}.${e.container_extension || 'mp4'}`, plot: e.info && e.info.plot, thumb: e.info && e.info.movie_image }))
    .sort((a, b) => a.season - b.season || a.episode - b.episode);
}


// ------------------------------------------------------------------ estado
const state = {
  sources: store.get('sources', []),
  catalogs: {},
  tab: 'home',
  category: null,
  query: '',
  limit: 0,
  favorites: new Set(store.get('favorites', [])),
  progress: store.get('progress', {}),
  history: store.get('history', []),
  detail: null, // { kind: 'movie' | 'series', sid, id, season? }
};
const saveSources = () => store.set('sources', state.sources);
const favKey = (kind, sid, id) => `${kind}|${sid}|${id}`;
const PAGE = 48; // primera tanda pequeña = pestaña instantánea; el resto se añade al bajar

// Índice unificado: se construye UNA vez al cargar o sincronizar (antes se copiaba todo el
// catálogo en cada pintado de pantalla, lo que hacía lenta la web con listas grandes).
const index = { live: [], movies: [], series: [], cats: {}, byKey: new Map() };
function rebuildIndex() {
  for (const k of ['live', 'movies', 'series']) index[k] = [];
  index.byKey.clear();
  for (const s of state.sources) {
    const c = state.catalogs[s.id]; if (!c) continue;
    for (const k of ['live', 'movies', 'series']) for (const x of c[k] || []) {
      x.sid = s.id; x._n = norm(x.name || x.title); index[k].push(x);
      index.byKey.set(`${k}|${s.id}|${x.id}`, x);
    }
  }
  for (const k of ['live', 'movies', 'series']) {
    const m = new Map();
    for (const x of index[k]) { const g = x.group || 'Sin categoría'; m.set(g, (m.get(g) || 0) + 1); }
    index.cats[k] = [...m.entries()];
  }
  index.recentMovies = [...index.movies].sort((a, b) => (b.added || 0) - (a.added || 0));
  index.recentSeries = [...index.series].sort((a, b) => (b.added || 0) - (a.added || 0));
}
const kindOfTab = { live: 'live', movies: 'movie', series: 'series' };
const listOfKind = { live: 'live', movie: 'movies', series: 'series' };

// ------------------------------------------------------------------ fuentes
async function syncSource(src) {
  src.lastError = null; src.syncing = true; src.step = 'Conectando…'; render();
  const progress = (what, partial) => {
    if (partial) {
      if (!state.catalogs[src.id]) { state.catalogs[src.id] = partial; rebuildIndex(); if (!player.item && ['home', 'live'].includes(state.tab) && !state.detail) render(); }
      src.step = 'Canales listos. Cargando películas y series…'; const el0 = document.querySelector(`[data-step="${src.id}"]`); if (el0) el0.textContent = src.step;
      return;
    }
    src.step = what === 'autenticado' ? 'Cuenta verificada. Descargando catálogo…' : `Recibido: ${what}…`; const el = document.querySelector(`[data-step="${src.id}"]`); if (el) el.textContent = src.step; };
  try {
    let cat;
    if (src.type === 'xtream') cat = await xtreamCatalog(src, progress);
    else {
      const text = src.type === 'file' ? await idb.get('file:' + src.id) : await fetchText(src.location, { timeout: 120000 });
      if (!text) throw new UserError('No se encontró el archivo guardado. Vuelve a elegirlo.');
      progress('lista');
      cat = parseM3U(text, src.id, src.type === 'url' ? src.location : null);
      if (!cat.live.length && !cat.movies.length && !cat.series.length) throw new UserError('La lista es válida pero está vacía.');
    }
    state.catalogs[src.id] = cat;
    rebuildIndex();
    idb.put('cat:' + src.id, cat); // en segundo plano: no hace esperar a la interfaz
    Object.assign(src, { lastSync: Date.now(), counts: [cat.live.length, cat.movies.length, cat.series.length], warnings: cat.warnings || [] });
  } catch (e) {
    src.lastError = e instanceof UserError ? e.message : 'No se pudo cargar la fuente.';
  } finally { src.syncing = false; src.step = null; saveSources(); render(); }
  return !src.lastError;
}

async function loadCatalogs() {
  await Promise.all(state.sources.map(async (s) => { const c = await idb.get('cat:' + s.id); if (c) state.catalogs[s.id] = c; }));
  rebuildIndex();
}

// ------------------------------------------------------------------ componentes
const view = $('#view');
const hue = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) % 360; return h; };
const titleOf = (x) => x.name || x.title;

function img(src, cls = '') {
  const u = safeImg(src);
  return u ? `<img class="${cls}" loading="lazy" decoding="async" fetchpriority="low" src="${esc(u)}" alt="" onerror="this.remove()">` : '';
}

function card(kind, x, opts = {}) {
  const k = favKey(kind, x.sid, x.id);
  const prog = state.progress[k];
  const pct = prog && prog.d ? Math.min(100, (prog.t / prog.d) * 100) : 0;
  const title = titleOf(x);
  const live = kind === 'live';
  const sub = live ? (x.num ? `Canal ${x.num}` : x.group || '') : [x.year, x.rating ? `★ ${x.rating.toFixed(1)}` : null].filter(Boolean).join('  ·  ');
  return `<button class="card ${live ? 'wide' : ''}" data-act="open" data-kind="${kind}" data-sid="${esc(x.sid)}" data-id="${esc(x.id)}" data-focus>
    <span class="art" style="--h:${hue(title)}">${img(live ? x.logo : x.poster)}<span class="art-title">${esc(title)}</span>
      ${state.favorites.has(k) ? '<span class="fav" aria-label="En mi lista">♥</span>' : ''}${pct > 2 ? `<span class="bar"><i style="width:${pct}%"></i></span>` : ''}</span>
    <span class="meta"><b>${esc(opts.title || title)}</b>${sub ? `<small>${esc(sub)}</small>` : ''}</span>
  </button>`;
}

function row(title, kind, items, more) {
  if (!items.length) return '';
  return `<section class="row">
    <div class="row-head"><h2>${esc(title)}</h2>${more ? `<button class="link" data-act="goto" data-tab="${more.tab}" data-cat="${esc(more.cat || '')}" data-focus>Ver todo</button>` : ''}</div>
    <div class="strip ${kind === 'live' ? 'strip-wide' : ''}">${items.map((x) => card(kind, x)).join('')}</div>
  </section>`;
}

// ------------------------------------------------------------------ vistas
function render() {
  document.querySelectorAll('.tab').forEach((b) => b.setAttribute('aria-current', b.dataset.tab === state.tab && !state.detail ? 'page' : 'false'));
  const noSources = !state.sources.length;
  document.body.classList.toggle('signed-out', noSources);
  $('#search').hidden = noSources || !['live', 'movies', 'series'].includes(state.tab) || !!state.detail;
  if (noSources && state.tab !== 'sources') return renderWelcome();
  if (state.detail) return renderDetail(state.detail);
  if (state.tab === 'sources') return renderSources();
  if (state.tab === 'favorites') return renderFavorites();
  if (state.tab === 'home') return renderHome();
  return renderLibrary(state.tab);
}

function renderWelcome() {
  view.innerHTML = `
    <section class="welcome">
      <div class="welcome-copy">
        <img class="welcome-logo" src="${$('.logo').src}" alt="EmbyVerse">
        <h1>Tu universo de entretenimiento</h1>
        <p>Entra con los datos de tu proveedor para ver canales en vivo, películas y series.</p>
      </div>
      ${addForm('Iniciar sesión', 'Entrar')}
      <button class="link" data-act="demo" data-focus>Probar con la lista de demostración</button>
    </section>`;
  wireForm();
  focusFirst();
}

function renderHome() {
  const anyContent = index.live.length + index.movies.length + index.series.length;
  if (!anyContent) {
    const syncing = state.sources.find((s) => s.syncing);
    view.innerHTML = syncing ? `<div class="loading-state"><span class="spinner"></span><p data-step="${esc(syncing.id)}">${esc(syncing.step || 'Cargando tu catálogo…')}</p></div>`
      : `<p class="empty">Tus fuentes aún no tienen contenido. <button class="link" data-act="goto" data-tab="sources" data-focus>Revisar fuentes</button></p>`;
    return;
  }
  // Destacado: título reciente con imagen, cambia cada día
  const pool = [...index.recentMovies.slice(0, 30), ...index.recentSeries.slice(0, 20)].filter((x) => safeImg(x.backdrop || x.poster));
  const hero = pool.length ? pool[new Date().getDate() % pool.length] : null;
  const heroKind = hero && (index.byKey.has(`series|${hero.sid}|${hero.id}`) ? 'series' : 'movie');
  const cont = continueItems();
  const topCats = (k, n) => index.cats[k].slice().sort((a, b) => b[1] - a[1]).slice(0, n).map(([c]) => c);
  const byCat = (k, c) => index[k].filter((x) => (x.group || 'Sin categoría') === c).slice(0, 24);
  view.innerHTML = `
    ${hero ? `<section class="hero" style="--h:${hue(titleOf(hero))}">
      <div class="hero-bg">${img(hero.backdrop || hero.poster)}</div>
      <div class="hero-body">
        <span class="eyebrow">${heroKind === 'series' ? 'Serie destacada' : 'Película destacada'}</span>
        <h1>${esc(hero.title)}</h1>
        <p class="hero-meta">${[hero.year, hero.rating ? `★ ${hero.rating.toFixed(1)}` : null, hero.group].filter(Boolean).map(esc).join('<i></i>')}</p>
        ${hero.plot ? `<p class="hero-plot">${esc(hero.plot)}</p>` : ''}
        <div class="hero-actions">
          <button class="btn primary" data-act="${heroKind === 'series' ? 'open' : 'play'}" data-kind="${heroKind}" data-sid="${esc(hero.sid)}" data-id="${esc(hero.id)}" data-focus>▶ ${heroKind === 'series' ? 'Ver episodios' : 'Reproducir'}</button>
          <button class="btn glass" data-act="open" data-kind="${heroKind}" data-sid="${esc(hero.sid)}" data-id="${esc(hero.id)}" data-focus>Más información</button>
        </div>
      </div>
      ${safeImg(hero.poster) ? `<div class="hero-poster">${img(hero.poster)}</div>` : ''}
    </section>` : ''}
    <div class="rows">
      ${state.sources.filter((x) => x.syncing).map((x) => `<p class="sync-note"><span class="spinner tiny"></span><span data-step="${esc(x.id)}">${esc(x.step || 'Actualizando…')}</span></p>`).join('')}
      ${cont.length ? `<section class="row"><div class="row-head"><h2>Continuar viendo</h2></div><div class="strip strip-wide">${cont.join('')}</div></section>` : ''}
      ${row('TV en vivo', 'live', index.live.slice(0, 24), { tab: 'live' })}
      ${row('Películas recién añadidas', 'movie', index.recentMovies.slice(0, 24), { tab: 'movies' })}
      ${row('Series recién añadidas', 'series', index.recentSeries.slice(0, 24), { tab: 'series' })}
      ${topCats('movies', 4).map((c) => row(c, 'movie', byCat('movies', c), { tab: 'movies', cat: c })).join('')}
      ${topCats('series', 3).map((c) => row(c, 'series', byCat('series', c), { tab: 'series', cat: c })).join('')}
    </div>`;
}

function continueItems() {
  const out = [];
  for (const h of state.history) {
    if (out.length >= 12) break;
    const [k, sid, ...rest] = h.k.split('|'); const id = rest.join('|');
    if (k !== 'movie' && k !== 'episode') continue;
    const p = state.progress[h.k];
    if (!p || !p.d || p.t < 15 || p.t / p.d > 0.95) continue;
    out.push(`<button class="card wide" data-act="resume" data-kind="${k}" data-sid="${esc(sid)}" data-id="${esc(id)}" data-focus>
      <span class="art" style="--h:${hue(h.title)}">${img(h.img)}<span class="art-title">${esc(h.title)}</span><span class="bar"><i style="width:${(p.t / p.d) * 100}%"></i></span><span class="play-badge">▶</span></span>
      <span class="meta"><b>${esc(h.title)}</b><small>${esc(h.sub || '')}${h.sub ? '  ·  ' : ''}quedan ${fmtTime(p.d - p.t)}</small></span></button>`);
  }
  return out;
}

function renderLibrary(tab) {
  const kind = kindOfTab[tab];
  const all = index[tab];
  const q = norm(state.query);
  const items = all.filter((x) => (!state.category || (x.group || 'Sin categoría') === state.category) && (!q || x._n.includes(q)));
  const limit = state.limit || PAGE;
  const label = { live: 'TV en vivo', movies: 'Películas', series: 'Series' }[tab];
  view.innerHTML = `
    <div class="lib-head">
      <h1>${label}<span class="count">${items.length.toLocaleString('es')}</span></h1>
      <nav class="chips" aria-label="Categorías">
        <button class="chip" data-act="cat" data-cat="" aria-pressed="${!state.category}" data-focus>Todas</button>
        ${index.cats[tab].map(([c, n]) => `<button class="chip" data-act="cat" data-cat="${esc(c)}" aria-pressed="${state.category === c}" data-focus>${esc(c)} <small>${n}</small></button>`).join('')}
      </nav>
    </div>
    ${items.length ? `<div class="grid ${kind === 'live' ? 'grid-wide' : ''}" id="lib-grid">${items.slice(0, limit).map((x) => card(kind, x)).join('')}</div>`
      : `<p class="empty">${all.length ? 'Ningún resultado con este filtro.' : 'Tus fuentes no tienen contenido en esta sección.'}</p>`}
    <div id="lib-more" class="lib-more"></div>`;
  // Carga continua: al acercarse al final se añaden más tarjetas sin repintar la página.
  let shown = Math.min(limit, items.length);
  const sentinel = $('#lib-more');
  if (!sentinel || shown >= items.length) return;
  const obs = new IntersectionObserver((entries) => {
    if (!entries[0].isIntersecting || !document.body.contains(sentinel)) return;
    const next = items.slice(shown, shown + PAGE * 2);
    $('#lib-grid').insertAdjacentHTML('beforeend', next.map((x) => card(kind, x)).join(''));
    shown += next.length; state.limit = shown;
    if (shown >= items.length) obs.disconnect();
  }, { rootMargin: '1200px 0px' });
  obs.observe(sentinel);
}

function renderFavorites() {
  const groups = [['live', 'Canales'], ['movie', 'Películas'], ['series', 'Series']].map(([kind, label]) => {
    const items = index[listOfKind[kind]].filter((x) => state.favorites.has(favKey(kind, x.sid, x.id)));
    return items.length ? `<section class="row"><div class="row-head"><h2>${label}</h2></div><div class="grid ${kind === 'live' ? 'grid-wide' : ''}">${items.map((x) => card(kind, x)).join('')}</div></section>` : '';
  }).join('');
  view.innerHTML = `<div class="lib-head"><h1>Mi lista</h1></div>${groups || '<p class="empty">Aún no tienes nada en tu lista. Abre un canal, película o serie y pulsa «♡ Mi lista».</p>'}`;
}

async function renderDetail(d) {
  const x = index.byKey.get(`${listOfKind[d.kind]}|${d.sid}|${d.id}`);
  if (!x) { state.detail = null; return render(); }
  const src = state.sources.find((s) => s.id === d.sid);
  const k = favKey(d.kind, d.sid, x.id);
  const prog = state.progress[k];
  const resumable = d.kind === 'movie' && prog && prog.d && prog.t > 15 && prog.t / prog.d < 0.95;
  view.innerHTML = `<section class="detail" style="--h:${hue(x.title)}">
    <div class="detail-bg">${img(x.backdrop || x.poster)}</div>
    <button class="btn glass back" data-act="back" data-focus>← Volver</button>
    <div class="detail-main">
      <div class="detail-poster art" style="--h:${hue(x.title)}">${img(x.poster)}<span class="art-title">${esc(x.title)}</span></div>
      <div class="detail-info">
        <span class="eyebrow">${d.kind === 'series' ? 'Serie' : 'Película'}</span>
        <h1>${esc(x.title)}</h1>
        <p class="hero-meta" id="d-meta">${[x.year, x.rating ? `★ ${x.rating.toFixed(1)}` : null, x.group].filter(Boolean).map(esc).join('<i></i>')}</p>
        <p class="detail-plot" id="d-plot">${x.plot ? esc(x.plot) : ''}</p>
        <div class="hero-actions">
          ${d.kind === 'movie' ? `<button class="btn primary" data-act="play" data-kind="movie" data-sid="${esc(d.sid)}" data-id="${esc(x.id)}" data-focus>▶ ${resumable ? `Continuar (${fmtTime(prog.t)})` : 'Reproducir'}</button>
            ${resumable ? `<button class="btn glass" data-act="play" data-restart="1" data-kind="movie" data-sid="${esc(d.sid)}" data-id="${esc(x.id)}" data-focus>Desde el principio</button>` : ''}` : ''}
          <button class="btn glass" data-act="fav" data-kind="${d.kind}" data-sid="${esc(d.sid)}" data-id="${esc(x.id)}" data-focus>${state.favorites.has(k) ? '♥ En mi lista' : '♡ Mi lista'}</button>
        </div>
        ${resumable ? `<div class="bar inline"><i style="width:${(prog.t / prog.d) * 100}%"></i></div>` : ''}
      </div>
    </div>
    ${d.kind === 'series' ? '<div id="eps" class="eps"><div class="loading-state small"><span class="spinner"></span><p>Cargando episodios…</p></div></div>' : ''}
  </section>`;
  focusFirst();
  if (d.kind === 'movie') {
    if (src && src.type === 'xtream' && !x.infoLoaded) {
      try {
        const info = await xtreamMovieInfo(src, x.id);
        Object.assign(x, { plot: x.plot || info.plot, backdrop: x.backdrop || info.backdrop, genre: info.genre, duration: info.duration, infoLoaded: true });
        if (state.detail !== d) return;
        $('#d-plot').textContent = x.plot || '';
        $('#d-meta').innerHTML = [x.year, x.duration, x.rating ? `★ ${x.rating.toFixed(1)}` : null, x.genre || x.group].filter(Boolean).map(esc).join('<i></i>');
        if (safeImg(x.backdrop) && !$('.detail-bg img')) $('.detail-bg').innerHTML = img(x.backdrop);
      } catch { /* la ficha sigue funcionando sin la información ampliada */ }
    }
    return;
  }
  try {
    if (!x.episodes) { x.episodes = await xtreamEpisodes(src, x.id); idb.put('cat:' + d.sid, state.catalogs[d.sid]); }
    if (state.detail !== d) return;
    const seasons = [...new Set(x.episodes.map((e) => e.season))];
    const cur = d.season ?? seasons[0];
    $('#eps').innerHTML = !x.episodes.length ? '<p class="empty">El proveedor no tiene episodios para esta serie.</p>' : `
      ${seasons.length > 1 ? `<nav class="chips">${seasons.map((n) => `<button class="chip" data-act="season" data-n="${n}" aria-pressed="${n === cur}" data-focus>Temporada ${n}</button>`).join('')}</nav>` : `<h2>Temporada ${cur}</h2>`}
      <ol class="episodes">${x.episodes.filter((e) => e.season === cur).map((e) => {
        const p = state.progress[favKey('episode', d.sid, e.id)]; const pct = p && p.d ? (p.t / p.d) * 100 : 0;
        return `<li><button class="ep" data-act="play-ep" data-eid="${esc(e.id)}" data-focus>
          <span class="ep-thumb art" style="--h:${hue(x.title + e.episode)}">${img(e.thumb || x.poster)}<span class="play-badge">▶</span>${pct > 2 ? `<span class="bar"><i style="width:${pct}%"></i></span>` : ''}</span>
          <span class="ep-text"><b>${e.episode}. ${esc(e.title)}</b>${e.plot ? `<small>${esc(e.plot)}</small>` : ''}</span></button></li>`;
      }).join('')}</ol>`;
  } catch (e) { $('#eps').innerHTML = `<p class="err">${esc(e.message || 'No se pudieron cargar los episodios.')}</p>`; }
}

function addForm(title, button) {
  const t = state.formType || 'xtream';
  return `
      <form id="add" class="add" autocomplete="off" data-type="${t}">
        <h2>${title}</h2>
        <div class="seg" role="radiogroup" aria-label="Tipo de acceso">
          ${[['xtream', 'Xtream Codes'], ['url', 'Lista M3U (URL)'], ['file', 'Archivo M3U']].map(([v, l]) => `<label><input type="radio" name="type" value="${v}" ${t === v ? 'checked' : ''} data-focus><span>${l}</span></label>`).join('')}
        </div>
        <div class="t-url t-xtream">
          <label class="field"><span class="t-url">URL de la lista M3U</span><span class="t-xtream">Servidor (o pega el enlace get.php completo)</span><input id="f-loc" name="location" inputmode="url" placeholder="https://" data-focus></label>
        </div>
        <label class="field t-file">Archivo .m3u / .m3u8<input id="f-file" name="file" type="file" accept=".m3u,.m3u8,audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain" data-focus></label>
        <div class="two t-xtream">
          <label class="field">Usuario<input id="f-user" name="username" autocapitalize="off" data-focus></label>
          <label class="field">Contraseña<input id="f-pass" name="password" type="password" data-focus></label>
        </div>
        <label class="field">Nombre (opcional)<input id="f-name" name="name" maxlength="40" placeholder="Mi proveedor" data-focus></label>
        <p class="err" id="f-err" hidden></p>
        <button class="btn primary wide-btn" data-focus>${button}</button>
        <p class="fine">${pageIsHttps ? 'En el navegador solo funcionan servidores y listas <b>https://</b>. Si tu proveedor usa http://, usa la app Android. ' : ''}Usa solo servicios a los que tengas derecho de acceso. Tus datos se guardan solo en este navegador.</p>
      </form>`;
}

function wireForm() {
  const form = $('#add');
  const sync = () => { const t = form.type.value; form.dataset.type = t; state.formType = t; };
  form.addEventListener('change', sync); sync();
  $('#f-loc').addEventListener('input', (e) => {
    const c = parseCredentialUrl(e.target.value);
    if (c && form.type.value === 'xtream') { e.target.value = c.location; $('#f-user').value = c.username; $('#f-pass').value = c.password; }
  });
  form.addEventListener('submit', onAddSource);
}

function renderSources() {
  view.innerHTML = `
    <section class="sources">
      <div class="lib-head"><h1>Fuentes</h1></div>
      ${state.sources.map((s) => `
        <article class="source">
          <div><b>${esc(s.name)}</b><small>${s.type === 'xtream' ? 'Xtream Codes' : s.type === 'file' ? 'Archivo M3U' : 'Lista M3U'}${s.counts ? `  ·  ${s.counts[0].toLocaleString('es')} canales  ·  ${s.counts[1].toLocaleString('es')} películas  ·  ${s.counts[2].toLocaleString('es')} series` : ''}</small>
            ${s.lastSync ? `<small>Actualizada: ${new Date(s.lastSync).toLocaleString('es')}</small>` : ''}
            ${s.syncing ? `<small class="ok" data-step="${esc(s.id)}">${esc(s.step || 'Cargando…')}</small>` : ''}
            ${s.lastError ? `<p class="err">${esc(s.lastError)}</p>` : ''}
          </div>
          <div class="source-actions">
            <button class="btn" data-act="sync" data-sid="${esc(s.id)}" data-focus ${s.syncing ? 'disabled' : ''}>Actualizar</button>
            <button class="btn danger" data-act="delete" data-sid="${esc(s.id)}" data-focus>Eliminar</button>
          </div>
        </article>`).join('')}
      ${addForm('Añadir otra fuente', 'Añadir')}
    </section>`;
  wireForm();
}

async function onAddSource(e) {
  e.preventDefault();
  const f = e.target, err = $('#f-err'), btn = f.querySelector('button.primary');
  const type = f.type.value;
  const show = (m) => { err.textContent = m; err.hidden = !m; };
  const src = { id: uid(), type, name: f.name.value.trim(), location: f.location.value.trim(), username: f.username.value.trim(), password: f.password.value };
  try {
    if (type === 'file') {
      const file = f.file.files[0]; if (!file) throw new UserError('Elige un archivo .m3u o .m3u8.');
      if (file.size > 80 * 1024 * 1024) throw new UserError('El archivo es demasiado grande (máx. 80 MB).');
      await idb.put('file:' + src.id, await file.text());
      src.location = file.name; src.name ||= file.name.replace(/\.m3u8?$/i, '');
    } else {
      if (!src.location) throw new UserError(type === 'xtream' ? 'Indica la dirección del servidor.' : 'Indica la URL de la lista.');
      if (type === 'xtream') { src.location = xBase(src.location); if (!src.username || !src.password) throw new UserError('Indica usuario y contraseña.'); }
      if (!isHttp(src.location)) throw new UserError('La dirección debe empezar por http:// o https://');
      if (insecureOnHttps(src.location)) throw new UserError(MIXED_MSG);
      src.name ||= (() => { try { return new URL(src.location).hostname; } catch { return 'Fuente'; } })();
    }
    btn.disabled = true; btn.dataset.label = btn.textContent; btn.textContent = 'Conectando…'; show('');
    const firstSource = !state.sources.length;
    state.sources.push(src); saveSources();
    if (firstSource) { state.tab = 'home'; }
    const ok = await syncSource(src);
    if (!ok) {
      const m = src.lastError; state.sources = state.sources.filter((s) => s !== src); saveSources(); idb.del('file:' + src.id);
      state.tab = state.sources.length ? 'sources' : 'home'; render();
      $('#f-err').textContent = m; $('#f-err').hidden = false; return;
    }
    toast(`Conectado: ${src.counts[0].toLocaleString('es')} canales, ${src.counts[1].toLocaleString('es')} películas, ${src.counts[2].toLocaleString('es')} series`);
    state.tab = 'home'; state.category = null; render(); focusFirst();
  } catch (ex) {
    show(ex instanceof UserError ? ex.message : 'No se pudo añadir la fuente.');
    const b = $('#add button.primary'); if (b) { b.disabled = false; b.textContent = b.dataset.label || 'Entrar'; }
  }
}

// ------------------------------------------------------------------ reproductor
const player = { el: $('#player'), video: $('#video'), hls: null, ts: null, item: null, list: null, retries: 0, saveTimer: null };
const fmtTime = (s) => { s = Math.max(0, Math.floor(s || 0)); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60; return h ? `${h} h ${String(m).padStart(2, '0')} min` : `${m}:${String(x).padStart(2, '0')}`; };

// Los motores HLS y MPEG-TS se descargan solo al reproducir algo que los necesite (antes
// retrasaban la apertura de la web).
const LIBS = { Hls: 'https://cdn.jsdelivr.net/npm/hls.js@1.5.17/dist/hls.min.js', mpegts: 'https://cdn.jsdelivr.net/npm/mpegts.js@1.7.3/dist/mpegts.js' };
const libPromises = {};
function loadLib(name) {
  if (window[name]) return Promise.resolve(window[name]);
  return libPromises[name] ||= new Promise((res) => {
    const s = document.createElement('script'); s.src = LIBS[name]; s.async = true;
    s.onload = () => res(window[name] || null); s.onerror = () => { delete libPromises[name]; res(null); };
    document.head.appendChild(s);
  });
}
// Precarga silenciosa tras abrir la web, para que el primer canal arranque rápido.
setTimeout(() => { loadLib('Hls'); }, 2500);

function destroyEngines() {
  if (player.hls) { player.hls.destroy(); player.hls = null; }
  if (player.ts) { try { player.ts.destroy(); } catch { /* */ } player.ts = null; }
  if (player.video.hasAttribute('src')) { player.video.removeAttribute('src'); player.video.load(); }
}

function playerError(msg, detail) {
  clearTimeout(player.watchdog);
  $('#p-err-msg').textContent = msg;
  $('#p-err-detail').textContent = detail || '';
  $('#p-err').hidden = false; $('#p-loading').hidden = true;
  setTimeout(() => $('#p-retry').focus(), 50);
}

function openPlayer(item, list = null) {
  player.item = item; player.list = list; player.retries = 0;
  $('#p-title').textContent = item.title; $('#p-sub').textContent = item.sub || '';
  $('#p-err').hidden = true; $('#p-loading').hidden = false; $('#p-tap').hidden = true;
  $('#p-fav').textContent = state.favorites.has(favKey(item.kind === 'episode' ? 'series' : item.kind, item.sid, item.favId || item.id)) ? '♥' : '♡';
  $('#p-prev').hidden = $('#p-next').hidden = !(list && list.length > 1);
  player.el.hidden = false; document.body.classList.add('playing');
  const k = favKey(item.kind, item.sid, item.id);
  state.history = [{ k, title: item.title, sub: item.kind === 'episode' ? item.sub.split(' — ')[0] : null, img: item.img || null, at: Date.now() }, ...state.history.filter((h) => h.k !== k)].slice(0, 100);
  store.set('history', state.history);
  start(item);
  showOverlay();
  $('#p-close').focus();
}

/** Variantes a probar: en canales Xtream se intenta HLS (.m3u8) y, si no arranca, MPEG-TS (.ts), o al revés. */
// Reproductor HLS integrado del navegador (Chrome reciente, Safari, Android/WebView): a diferencia de
// hls.js, NO necesita que el proveedor permita CORS, así que abre canales que de otro modo fallan.
const nativeHls = (() => { try { return !!document.createElement('video').canPlayType('application/vnd.apple.mpegurl'); } catch { return false; } })();

/** Intentos en orden: {url, mode}. mode: 'native' (sin CORS), 'hls' (hls.js), 'ts' (mpegts.js), 'file'. */
function candidatesFor(item) {
  const m = /^(.*\/live\/[^/]+\/[^/]+\/[^/.]+)\.(m3u8|ts)$/i.exec(item.url);
  const ext = extOf(item.url);
  if (m) {
    const hlsUrl = `${m[1]}.m3u8`, tsUrl = `${m[1]}.ts`;
    return [...(nativeHls ? [{ url: hlsUrl, mode: 'native' }] : []), { url: hlsUrl, mode: 'hls' }, { url: tsUrl, mode: 'ts' }];
  }
  if (ext === 'm3u8' || ext === 'm3u') return [...(nativeHls ? [{ url: item.url, mode: 'native' }] : []), { url: item.url, mode: 'hls' }];
  if (ext === 'ts' || (item.kind === 'live' && !ext)) return [{ url: item.url, mode: 'ts' }];
  return [{ url: item.url, mode: 'file' }];
}

const MIXED_STREAM_MSG = 'Tu proveedor entrega el video por http:// (sin cifrar) y el navegador lo bloquea en esta página segura. Este canal funciona en la app Android de EmbyVerse.';

async function start(item, attempt = 0) {
  destroyEngines();
  clearTimeout(player.watchdog);
  const urls = candidatesFor(item);
  const { url, mode } = urls[attempt], v = player.video;
  player.expectSrc = null;
  const token = player.token = (player.token || 0) + 1; // invalida intentos anteriores
  const alive = () => player.item === item && player.token === token;
  if (insecureOnHttps(url)) return playerError(MIXED_STREAM_MSG);
  const ext = extOf(url);
  if (ext === 'mkv' || ext === 'avi' || ext === 'wmv') return playerError(`El formato .${ext} no lo pueden reproducir los navegadores. Ábrelo en la app Android de EmbyVerse.`);

  // Si no hay imagen en pocos segundos, se prueba el otro formato o se explica el problema
  // (antes el círculo de carga podía quedarse girando indefinidamente).
  let lastProblem = '';
  const fail = player.fail = (msg, detail) => {
    if (!alive()) return;
    if (attempt + 1 < urls.length) { $('#p-sub').textContent = `${item.sub || ''}  ·  probando otro formato…`; start(item, attempt + 1); return; }
    playerError(msg, detail);
  };
  const timeoutMs = item.kind === 'live' ? 12000 : 25000;
  player.watchdog = setTimeout(() => fail(lastProblem || 'El canal no envió video a tiempo. El servidor puede estar saturado, el canal caído o tu proveedor no permite reproducir desde navegadores.',
    `Sin imagen tras ${timeoutMs / 1000} s (${ext || 'stream'})`), timeoutMs);

  const resume = item.kind !== 'live' ? state.progress[favKey(item.kind, item.sid, item.id)] : null;
  const startAt = item.restart ? 0 : resume && resume.t > 15 && resume.t / resume.d < 0.95 ? resume.t : 0;
  const onReady = () => { if (!alive()) return; if (startAt) v.currentTime = startAt; v.play().catch(() => { $('#p-loading').hidden = true; $('#p-tap').hidden = false; }); };
  if (mode === 'native') {
    // 8 s para el intento nativo; si no arranca se pasa a hls.js y después a MPEG-TS.
    clearTimeout(player.watchdog);
    player.watchdog = setTimeout(() => fail(lastProblem || 'El canal no envió video a tiempo.', 'nativo sin imagen tras 8 s'), 8000);
    player.expectSrc = url; v.src = url; v.addEventListener('loadedmetadata', onReady, { once: true });
    return;
  }
  if (mode === 'hls') {
    const Hls = await loadLib('Hls');
    if (!alive()) return;
    if (!Hls || !Hls.isSupported()) { if (!Hls) return fail("No se pudo descargar el reproductor HLS. Revisa tu conexión y pulsa Reintentar.", "hls.js no cargó"); player.expectSrc = url; v.src = url; v.addEventListener('loadedmetadata', onReady, { once: true }); return; }
    const h = new Hls({
      enableWorker: true, maxBufferLength: 20, startFragPrefetch: true,
      manifestLoadingTimeOut: 8000, manifestLoadingMaxRetry: 1, levelLoadingTimeOut: 8000, levelLoadingMaxRetry: 1,
      fragLoadingTimeOut: 12000, fragLoadingMaxRetry: 2,
    });
    player.hls = h;
    h.on(Hls.Events.MANIFEST_PARSED, onReady);
    h.on(Hls.Events.ERROR, (_, d) => {
      const finalUrl = (d.networkDetails && d.networkDetails.responseURL) || (d.response && d.response.url) || '';
      const code = d.response && d.response.code;
      if (/^http:\/\//i.test(finalUrl) && pageIsHttps) lastProblem = MIXED_STREAM_MSG;
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && player.retries < 1) { player.retries++; h.recoverMediaError(); return; }
      fail(code === 401 || code === 403 ? 'El proveedor denegó el acceso (credenciales o límite de conexiones alcanzado).'
        : code === 404 ? 'El proveedor dice que este canal no existe o está fuera de servicio (404).'
        : lastProblem || (d.type === Hls.ErrorTypes.NETWORK_ERROR ? 'No se pudo cargar el canal: el servidor no responde o no permite reproducir desde navegadores (CORS). Prueba en la app Android.' : 'Este canal usa un formato que el navegador no puede reproducir.'),
        `${d.type} · ${d.details}${code ? ' · HTTP ' + code : ''}`);
    });
    h.loadSource(url); h.attachMedia(v);
    return;
  }
  if (mode === 'ts') {
    const mpegts = await loadLib('mpegts');
    if (!alive()) return;
    if (!mpegts) return fail('No se pudo descargar el reproductor de canales. Revisa tu conexión a Internet y pulsa Reintentar.', 'mpegts.js no cargó');
    if (!mpegts.getFeatureList().mseLivePlayback) return fail('Este navegador no puede reproducir canales MPEG-TS. Usa la app Android.', 'MSE no disponible');
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: true, url }, { enableWorker: true, lazyLoad: false, liveBufferLatencyChasing: true });
    player.ts = p; p.attachMediaElement(v); p.load();
    p.on(mpegts.Events.ERROR, (type, detail, info) => fail(
      type === mpegts.ErrorTypes.NETWORK_ERROR ? 'Tu proveedor no permite reproducir este canal desde un navegador web. Se probaron los 3 métodos disponibles. Este canal sí funciona en la app Android de EmbyVerse.' : 'Este canal usa un formato que el navegador no puede reproducir.',
      `${type} · ${detail}${info && info.code ? ' · HTTP ' + info.code : ''}`));
    v.addEventListener('loadedmetadata', onReady, { once: true });
    return;
  }
  player.expectSrc = url; v.src = url; v.addEventListener('loadedmetadata', onReady, { once: true });
}
player.video.addEventListener('playing', () => { clearTimeout(player.watchdog); $('#p-loading').hidden = true; $('#p-tap').hidden = true; player.retries = 0; });
player.video.addEventListener('waiting', () => { if ($('#p-err').hidden) $('#p-loading').hidden = false; });
player.video.addEventListener('error', () => {
  // Solo errores del video que se pidió (no los del vaciado del intento anterior).
  if (player.hls || player.ts || !player.item || !player.fail || !player.expectSrc || player.video.currentSrc !== player.expectSrc) return;
  const code = player.video.error && player.video.error.code;
  player.fail('No se pudo reproducir: el formato no es compatible con este navegador o el servidor no responde.', `video error ${code || ''}`);
});
player.video.addEventListener('timeupdate', () => {
  const it = player.item; if (!it || it.kind === 'live') return;
  const d = player.video.duration; if (!isFinite(d) || d <= 0) return;
  clearTimeout(player.saveTimer);
  player.saveTimer = setTimeout(() => { state.progress[favKey(it.kind, it.sid, it.id)] = { t: player.video.currentTime, d }; store.set('progress', state.progress); }, 1500);
});
player.video.addEventListener('ended', () => { if (player.list && player.item.kind === 'episode') zap(1, false); });

function closePlayer() {
  const it = player.item;
  if (it && it.kind !== 'live' && isFinite(player.video.duration)) { state.progress[favKey(it.kind, it.sid, it.id)] = { t: player.video.currentTime, d: player.video.duration }; store.set('progress', state.progress); }
  player.item = null; clearTimeout(player.watchdog);
  destroyEngines(); player.el.hidden = true; document.body.classList.remove('playing');
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  render(); focusFirst();
}

function zap(delta, wrap = true) {
  const list = player.list; if (!list || !list.length) return;
  const i = list.findIndex((x) => x.id === player.item.id);
  let j = i + delta; if (j < 0 || j >= list.length) { if (!wrap) return; j = (j + list.length) % list.length; }
  openPlayer(list[j], list);
}

let overlayTimer;
function showOverlay() {
  player.el.classList.add('show');
  clearTimeout(overlayTimer); overlayTimer = setTimeout(() => { if (!player.video.paused) player.el.classList.remove('show'); }, 4000);
}
['mousemove', 'touchstart'].forEach((ev) => player.el.addEventListener(ev, showOverlay, { passive: true }));

// ------------------------------------------------------------------ acciones
function itemFor(kind, sid, id) {
  if (kind === 'live') { const x = index.byKey.get(`live|${sid}|${id}`); return x && { kind, sid, id, title: x.name, sub: x.group, url: x.url, img: x.logo }; }
  if (kind === 'movie') { const x = index.byKey.get(`movies|${sid}|${id}`); return x && { kind, sid, id, title: x.title, sub: [x.year, x.group].filter(Boolean).join('  ·  '), url: x.url, img: x.poster }; }
  if (kind === 'episode') {
    for (const s of index.series) {
      if (s.sid !== sid || !s.episodes) continue;
      const e = s.episodes.find((y) => y.id === id);
      if (e) return { kind, sid, id, title: s.title, sub: `T${e.season} · E${e.episode} — ${e.title}`, url: e.url, favId: s.id, seriesId: s.id, img: e.thumb || s.poster };
    }
  }
  return null;
}

function liveList() {
  const q = norm(state.query);
  return index.live.filter((x) => (!state.category || (x.group || 'Sin categoría') === state.category) && (!q || x._n.includes(q))).slice(0, 3000).map((x) => itemFor('live', x.sid, x.id));
}

document.addEventListener('click', async (e) => {
  const b = e.target.closest('[data-act]'); if (!b) return;
  const { act, kind, sid, id } = b.dataset;
  if (act === 'open') {
    if (kind === 'series' || kind === 'movie') { state.detail = { kind, sid, id }; window.scrollTo(0, 0); render(); return; }
    const it = itemFor(kind, sid, id); if (it) openPlayer(it, kind === 'live' ? liveList() : null);
  } else if (act === 'play') {
    const it = itemFor(kind, sid, id); if (!it) return;
    if (b.dataset.restart) it.restart = true;
    openPlayer(it);
  } else if (act === 'resume') {
    const it = itemFor(kind, sid, id); if (!it) return toast('Ese contenido ya no está en tus fuentes.');
    let list = null;
    if (kind === 'episode') { const s = index.byKey.get(`series|${sid}|${it.seriesId}`); list = s.episodes.map((ep) => itemFor('episode', sid, ep.id)); }
    openPlayer(it, list);
  } else if (act === 'play-ep') {
    const d = state.detail; const s = index.byKey.get(`series|${d.sid}|${d.id}`);
    openPlayer(itemFor('episode', d.sid, b.dataset.eid), s.episodes.map((ep) => itemFor('episode', d.sid, ep.id)));
  } else if (act === 'season') { state.detail.season = +b.dataset.n; renderDetail(state.detail); }
  else if (act === 'back') { state.detail = null; render(); focusFirst(); }
  else if (act === 'fav') { const on = toggleFav(kind, sid, id); b.textContent = on ? '♥ En mi lista' : '♡ Mi lista'; }
  else if (act === 'cat') { state.category = b.dataset.cat || null; state.limit = PAGE; render(); document.querySelector(`.chip[data-cat="${CSS.escape(b.dataset.cat)}"]`)?.focus(); }
  else if (act === 'goto') { state.tab = b.dataset.tab; state.category = b.dataset.cat || null; state.detail = null; state.limit = PAGE; window.scrollTo(0, 0); render(); focusFirst(); }
  else if (act === 'more') { state.limit = (state.limit || PAGE) + PAGE * 2; render(); }
  else if (act === 'demo') {
    const src = { id: uid(), type: 'url', name: 'Demostración', location: new URL('demo.m3u', location.href).href };
    state.sources.push(src); saveSources(); state.tab = 'home';
    if (!(await syncSource(src))) { const m = src.lastError; state.sources = state.sources.filter((s) => s !== src); saveSources(); toast(m); render(); }
    else focusFirst();
  } else if (act === 'sync') { const s = state.sources.find((x) => x.id === sid); if (s) syncSource(s); }
  else if (act === 'delete') {
    if (b.dataset.confirm !== '1') { b.dataset.confirm = '1'; b.textContent = '¿Seguro? Pulsa otra vez'; setTimeout(() => { if (b.isConnected) { b.dataset.confirm = ''; b.textContent = 'Eliminar'; } }, 4000); return; }
    state.sources = state.sources.filter((x) => x.id !== sid); delete state.catalogs[sid]; rebuildIndex(); saveSources(); idb.del('cat:' + sid); idb.del('file:' + sid); render(); focusFirst();
  }
});

function toggleFav(kind, sid, id) {
  const k = favKey(kind, sid, id);
  if (state.favorites.has(k)) state.favorites.delete(k); else state.favorites.add(k);
  store.set('favorites', [...state.favorites]);
  return state.favorites.has(k);
}

document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => { state.tab = t.dataset.tab; state.category = null; state.detail = null; state.limit = PAGE; window.scrollTo(0, 0); render(); }));
let qTimer;
$('#search').addEventListener('input', (e) => { clearTimeout(qTimer); qTimer = setTimeout(() => { state.query = e.target.value; state.limit = PAGE; render(); }, 250); });
$('#p-close').addEventListener('click', closePlayer);
$('#p-retry').addEventListener('click', () => { $('#p-err').hidden = true; $('#p-loading').hidden = false; player.retries = 0; start(player.item); });
$('#p-err-close').addEventListener('click', closePlayer);
$('#p-prev').addEventListener('click', () => zap(-1));
$('#p-next').addEventListener('click', () => zap(1));
$('#p-tap').addEventListener('click', () => { player.video.play(); $('#p-tap').hidden = true; });
$('#p-fav').addEventListener('click', () => { const it = player.item; $('#p-fav').textContent = toggleFav(it.kind === 'episode' ? 'series' : it.kind, it.sid, it.favId || it.id) ? '♥' : '♡'; });
$('#p-full').addEventListener('click', () => { (document.fullscreenElement ? document.exitFullscreen() : player.el.requestFullscreen()).catch(() => toast('La pantalla completa no está disponible aquí.')); });

let toastTimer;
function toast(msg) { const t = $('#toast'); t.textContent = msg; t.hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.hidden = true), 4500); }

// ------------------------------------------------------------------ navegación con mando (TV)
function focusables() {
  const root = player.el.hidden ? document : player.el;
  return [...root.querySelectorAll('[data-focus], .tab, #search, .player button')].filter((el) => !el.disabled && el.offsetParent !== null && !el.closest('[hidden]'));
}
// Con mando a distancia siempre debe haber un elemento enfocado; se evita enfocar campos de texto para no abrir el teclado.
function focusFirst() { setTimeout(() => { const f = [...view.querySelectorAll('[data-focus]:not([disabled])')].find((el) => el.tagName !== 'INPUT' || el.type === 'radio'); if (f) f.focus({ preventScroll: true }); }, 30); }
function moveFocus(dir) {
  const cur = document.activeElement; const els = focusables();
  if (!cur || !els.includes(cur)) { els[0]?.focus(); return; }
  const r = cur.getBoundingClientRect(); const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
  let best = null, bestScore = Infinity;
  for (const el of els) {
    if (el === cur) continue;
    const b = el.getBoundingClientRect(); const x = b.left + b.width / 2, y = b.top + b.height / 2;
    const dx = x - cx, dy = y - cy;
    const ok = dir === 'left' ? dx < -4 : dir === 'right' ? dx > 4 : dir === 'up' ? dy < -4 : dy > 4;
    if (!ok) continue;
    const main = dir === 'left' || dir === 'right' ? Math.abs(dx) : Math.abs(dy);
    const cross = dir === 'left' || dir === 'right' ? Math.abs(dy) : Math.abs(dx);
    const score = main + cross * 2.5;
    if (score < bestScore) { bestScore = score; best = el; }
  }
  if (best) { best.focus(); best.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
}
document.addEventListener('keydown', (e) => {
  const tag = document.activeElement?.tagName;
  const typing = (tag === 'INPUT' && !['radio', 'checkbox', 'file', 'button'].includes(document.activeElement.type)) || tag === 'TEXTAREA';
  const playing = !player.el.hidden;
  const k = e.key;
  if (playing) showOverlay();
  if (k === 'Escape' || k === 'GoBack' || k === 'BrowserBack' || (k === 'Backspace' && !typing)) {
    if (playing) { e.preventDefault(); closePlayer(); return; }
    if (state.detail) { e.preventDefault(); state.detail = null; render(); focusFirst(); return; }
  }
  if (playing && player.item?.kind === 'live') {
    if (k === 'ChannelUp' || k === 'PageUp') { e.preventDefault(); zap(-1); return; }
    if (k === 'ChannelDown' || k === 'PageDown') { e.preventDefault(); zap(1); return; }
  }
  if (playing && (k === 'MediaPlayPause' || (k === ' ' && !typing))) { e.preventDefault(); player.video.paused ? player.video.play() : player.video.pause(); return; }
  const dir = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' }[k];
  if (dir && !(typing && (dir === 'left' || dir === 'right'))) { e.preventDefault(); moveFocus(dir); }
});

// ------------------------------------------------------------------ inicio
(async () => {
  render(); // pinta al instante (pantalla de entrada o estado de carga)
  await loadCatalogs();
  render();
  // Sin catálogo guardado: se descarga. Con catálogo de más de 12 h: se actualiza en segundo plano.
  for (const s of state.sources) if (!state.catalogs[s.id] || Date.now() - (s.lastSync || 0) > 12 * 3600 * 1000) syncSource(s);
  focusFirst();
})();

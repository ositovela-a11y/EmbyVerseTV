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
async function xtreamCatalog(src) {
  const acc = await xtreamAuth(src);
  const warnings = [];
  const get = async (action) => { try { const r = await fetchText(xApi(src, action), { json: true, timeout: 60000 }); return Array.isArray(r) ? r : Object.values(r || {}); } catch (e) { warnings.push(`${action}: ${e.message}`); return []; } };
  const cats = async (a) => Object.fromEntries((await get(a)).map((c) => [String(c.category_id), c.category_name]));
  const [lc, vc, sc] = await Promise.all([cats('get_live_categories'), cats('get_vod_categories'), cats('get_series_categories')]);
  const [ls, vs, ss] = await Promise.all([get('get_live_streams'), get('get_vod_streams'), get('get_series')]);
  const base = xBase(src.location), u = enc(src.username), p = enc(src.password);
  const fmt = acc.formats.includes('m3u8') || !acc.formats.length ? 'm3u8' : 'ts';
  const live = dedupe(ls.filter((x) => x.stream_id != null), (x) => x.stream_id).map((x) => ({ id: String(x.stream_id), name: x.name || 'Canal', url: `${base}/live/${u}/${p}/${x.stream_id}.${fmt}`, logo: x.stream_icon || null, group: lc[String(x.category_id)] || null, epg: x.epg_channel_id || null, num: +x.num || null }));
  const movies = dedupe(vs.filter((x) => x.stream_id != null), (x) => x.stream_id).map((x) => ({ id: String(x.stream_id), title: x.name || 'Película', url: `${base}/movie/${u}/${p}/${x.stream_id}.${x.container_extension || 'mp4'}`, poster: x.stream_icon || null, group: vc[String(x.category_id)] || null, year: +x.year || null, rating: +x.rating || null, ext: x.container_extension || 'mp4', added: +x.added || null }));
  const series = dedupe(ss.filter((x) => x.series_id != null), (x) => x.series_id).map((x) => ({ id: String(x.series_id), title: x.name || 'Serie', poster: x.cover || null, group: sc[String(x.category_id)] || null, plot: x.plot || null, year: parseInt(x.releaseDate || x.release_date, 10) || null, rating: +x.rating || null, episodes: null, added: +x.last_modified || null }));
  if (!live.length && !movies.length && !series.length) throw new UserError('La cuenta es válida pero el servidor no devolvió contenido. ' + warnings.join(' '));
  return { sourceId: src.id, live, movies, series, warnings, account: acc };
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
  tab: 'live',
  category: null,
  query: '',
  favorites: new Set(store.get('favorites', [])),
  progress: store.get('progress', {}),
  history: store.get('history', []),
  detail: null,
};
const saveSources = () => store.set('sources', state.sources);
const favKey = (kind, sid, id) => `${kind}|${sid}|${id}`;

function allOf(kind) {
  const out = [];
  for (const s of state.sources) {
    const c = state.catalogs[s.id]; if (!c) continue;
    for (const x of c[kind] || []) out.push({ ...x, sid: s.id });
  }
  return out;
}

// ------------------------------------------------------------------ fuentes
async function syncSource(src) {
  src.lastError = null; src.syncing = true; render();
  try {
    let cat;
    if (src.type === 'xtream') cat = await xtreamCatalog(src);
    else {
      const text = src.type === 'file' ? await idb.get('file:' + src.id) : await fetchText(src.location, { timeout: 90000 });
      if (!text) throw new UserError('No se encontró el archivo guardado. Vuelve a elegirlo.');
      cat = parseM3U(text, src.id, src.type === 'url' ? src.location : null);
      if (!cat.live.length && !cat.movies.length && !cat.series.length) throw new UserError('La lista es válida pero está vacía.');
    }
    state.catalogs[src.id] = cat;
    await idb.put('cat:' + src.id, cat);
    Object.assign(src, { lastSync: Date.now(), counts: [cat.live.length, cat.movies.length, cat.series.length], warnings: cat.warnings || [] });
  } catch (e) {
    src.lastError = e instanceof UserError ? e.message : 'No se pudo cargar la fuente.';
  } finally { src.syncing = false; saveSources(); render(); }
  return !src.lastError;
}

async function loadCatalogs() {
  await Promise.all(state.sources.map(async (s) => { const c = await idb.get('cat:' + s.id); if (c) state.catalogs[s.id] = c; }));
}

// ------------------------------------------------------------------ vistas
const TABS = [['live', 'TV en vivo'], ['movies', 'Películas'], ['series', 'Series'], ['favorites', 'Favoritos'], ['sources', 'Fuentes']];
const view = $('#view');

function render() {
  document.querySelectorAll('.tab').forEach((b) => b.setAttribute('aria-current', b.dataset.tab === state.tab ? 'page' : 'false'));
  $('#search').hidden = state.tab === 'sources';
  if (state.detail) return renderSeries(state.detail);
  if (!state.sources.length && state.tab !== 'sources') return renderWelcome();
  if (state.tab === 'sources') return renderSources();
  if (state.tab === 'favorites') return renderFavorites();
  return renderLibrary(state.tab);
}

function renderWelcome() {
  view.innerHTML = `
    <section class="welcome">
      <h1>Tu universo de entretenimiento, en el navegador</h1>
      <p>Añade tu lista M3U o tu proveedor Xtream. Todo se guarda solo en este navegador.</p>
      <button class="btn primary" data-act="goto-sources" data-focus>Añadir mi primera fuente</button>
      <button class="btn" data-act="demo" data-focus>Probar con la lista de demostración</button>
      <p class="fine">La lista de demostración usa películas abiertas de la Blender Foundation (licencia CC BY) y streams de prueba públicos.</p>
    </section>`;
  focusFirst();
}

function categoriesFor(items) {
  const counts = new Map();
  for (const x of items) { const g = x.group || 'Sin categoría'; counts.set(g, (counts.get(g) || 0) + 1); }
  return [...counts.entries()];
}

function cardFor(kind, x) {
  const fav = state.favorites.has(favKey(kind, x.sid, x.id));
  const prog = state.progress[favKey(kind, x.sid, x.id)];
  const pct = prog && prog.d ? Math.min(100, (prog.t / prog.d) * 100) : 0;
  const img = safeImg(kind === 'live' ? x.logo : x.poster);
  const title = kind === 'live' ? x.name : x.title;
  const sub = kind === 'live' ? (x.num ? `${x.num} · ` : '') + (x.group || '') : [x.year, x.rating ? '★ ' + x.rating.toFixed(1) : null].filter(Boolean).join(' · ');
  return `<button class="card ${kind === 'live' ? 'wide' : ''}" data-act="open" data-kind="${kind}" data-sid="${esc(x.sid)}" data-id="${esc(x.id)}" data-focus>
    <span class="art" style="--h:${hue(title)}">${img ? `<img loading="lazy" src="${esc(img)}" alt="" onerror="this.remove()">` : ''}<span class="art-title">${esc(title)}</span>
      ${fav ? '<span class="fav" aria-label="Favorito">♥</span>' : ''}${pct > 2 ? `<span class="bar"><i style="width:${pct}%"></i></span>` : ''}</span>
    <span class="meta"><b>${esc(title)}</b><small>${esc(sub)}</small></span>
  </button>`;
}
const hue = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) % 360; return h; };

const PAGE = 300;
function renderLibrary(tab) {
  const kind = tab === 'live' ? 'live' : tab === 'movies' ? 'movie' : 'series';
  const all = allOf(tab === 'live' ? 'live' : tab);
  const cats = categoriesFor(all);
  const q = norm(state.query);
  let items = all.filter((x) => (!state.category || (x.group || 'Sin categoría') === state.category) && (!q || norm(x.name || x.title).includes(q)));
  const total = items.length;
  const limit = state.limit || PAGE;
  items = items.slice(0, limit);
  const cont = tab !== 'live' ? continueRow(kind) : '';
  view.innerHTML = `
    ${cont}
    <div class="library">
      <nav class="cats" aria-label="Categorías">
        <button class="cat" data-act="cat" data-cat="" aria-pressed="${!state.category}" data-focus>Todas <small>${all.length}</small></button>
        ${cats.map(([c, n]) => `<button class="cat" data-act="cat" data-cat="${esc(c)}" aria-pressed="${state.category === c}" data-focus>${esc(c)} <small>${n}</small></button>`).join('')}
      </nav>
      <div class="grid-wrap">
        ${total ? `<div class="grid ${kind === 'live' ? 'grid-wide' : ''}">${items.map((x) => cardFor(kind, x)).join('')}</div>` : `<p class="empty">${all.length ? 'Ningún resultado con este filtro.' : 'Tus fuentes no tienen contenido en esta sección.'}</p>`}
        ${total > limit ? `<button class="btn more" data-act="more" data-focus>Mostrar más (${total - limit} restantes)</button>` : ''}
      </div>
    </div>`;
}

function continueRow(kind) {
  const keys = state.history.filter((h) => h.k.startsWith(kind === 'movie' ? 'movie|' : 'episode|')).slice(0, 12);
  const rows = keys.map((h) => { const p = state.progress[h.k]; return p && p.d && p.t / p.d < 0.95 && p.t > 15 ? h : null; }).filter(Boolean);
  if (!rows.length) return '';
  return `<section class="row"><h2>Continuar viendo</h2><div class="strip">${rows.map((h) => {
    const p = state.progress[h.k]; const [k, sid, ...rest] = h.k.split('|'); const id = rest.join('|');
    return `<button class="card wide" data-act="resume" data-kind="${k}" data-sid="${esc(sid)}" data-id="${esc(id)}" data-focus><span class="art" style="--h:${hue(h.title)}"><span class="art-title">${esc(h.title)}</span><span class="bar"><i style="width:${(p.t / p.d) * 100}%"></i></span></span><span class="meta"><b>${esc(h.title)}</b><small>${fmtTime(p.t)} de ${fmtTime(p.d)}</small></span></button>`;
  }).join('')}</div></section>`;
}

function renderFavorites() {
  const groups = [['live', 'Canales'], ['movie', 'Películas'], ['series', 'Series']].map(([kind, label]) => {
    const pool = allOf(kind === 'live' ? 'live' : kind === 'movie' ? 'movies' : 'series');
    const items = pool.filter((x) => state.favorites.has(favKey(kind, x.sid, x.id)));
    return items.length ? `<section class="row"><h2>${label}</h2><div class="grid ${kind === 'live' ? 'grid-wide' : ''}">${items.map((x) => cardFor(kind, x)).join('')}</div></section>` : '';
  }).join('');
  view.innerHTML = groups || '<p class="empty">Aún no tienes favoritos. Abre un canal, película o serie y pulsa ♥.</p>';
}

function renderSources() {
  view.innerHTML = `
    <section class="sources">
      <h1>Fuentes</h1>
      ${state.sources.map((s) => `
        <article class="source">
          <div><b>${esc(s.name)}</b><small>${s.type === 'xtream' ? 'Xtream Codes' : s.type === 'file' ? 'Archivo M3U' : 'Lista M3U (URL)'}${s.counts ? ` · ${s.counts[0]} canales · ${s.counts[1]} películas · ${s.counts[2]} series` : ''}</small>
            ${s.lastSync ? `<small>Actualizada: ${new Date(s.lastSync).toLocaleString()}</small>` : ''}
            ${s.syncing ? '<small class="ok">Cargando…</small>' : ''}
            ${s.lastError ? `<p class="err">${esc(s.lastError)}</p>` : ''}
            ${(s.warnings || []).map((w) => `<small class="warn">${esc(w)}</small>`).join('')}
          </div>
          <div class="source-actions">
            <button class="btn" data-act="sync" data-sid="${esc(s.id)}" data-focus ${s.syncing ? 'disabled' : ''}>Actualizar</button>
            <button class="btn danger" data-act="delete" data-sid="${esc(s.id)}" data-focus>Eliminar</button>
          </div>
        </article>`).join('')}
      <form id="add" class="add" autocomplete="off">
        <h2>Añadir fuente</h2>
        <div class="seg" role="radiogroup" aria-label="Tipo de fuente">
          ${[['url', 'Lista M3U (URL)'], ['file', 'Archivo M3U'], ['xtream', 'Xtream Codes']].map(([v, l]) => `<label><input type="radio" name="type" value="${v}" ${(state.formType || 'url') === v ? 'checked' : ''} data-focus><span>${l}</span></label>`).join('')}
        </div>
        <label class="field">Nombre<input id="f-name" name="name" maxlength="40" placeholder="Mi proveedor" data-focus></label>
        <div class="t-url t-xtream">
          <label class="field"><span class="t-url">URL de la lista</span><span class="t-xtream">Servidor (o pega el enlace get.php completo)</span><input id="f-loc" name="location" inputmode="url" placeholder="https://" data-focus></label>
        </div>
        <label class="field t-file">Archivo .m3u / .m3u8<input id="f-file" name="file" type="file" accept=".m3u,.m3u8,audio/x-mpegurl,application/vnd.apple.mpegurl,text/plain" data-focus></label>
        <div class="two t-xtream">
          <label class="field">Usuario<input id="f-user" name="username" data-focus></label>
          <label class="field">Contraseña<input id="f-pass" name="password" type="password" data-focus></label>
        </div>
        <p class="fine">${pageIsHttps ? 'Esta página es segura (https), así que el navegador solo permite listas, servidores y canales https://. Para proveedores http:// usa la app Android.' : ''} Usa solo listas y servicios a los que tengas derecho de acceso.</p>
        <p class="err" id="f-err" hidden></p>
        <button class="btn primary" data-focus>Probar y guardar</button>
      </form>
      <p class="fine">Las fuentes, contraseñas, favoritos y el progreso se guardan solo en este navegador.</p>
    </section>`;
  const form = $('#add');
  const sync = () => { const t = form.type.value; form.dataset.type = t; state.formType = t; };
  form.addEventListener('change', sync); sync();
  $('#f-loc').addEventListener('input', (e) => {
    const c = parseCredentialUrl(e.target.value);
    if (c && form.type.value === 'xtream') { e.target.value = c.location; $('#f-user').value = c.username; $('#f-pass').value = c.password; }
  });
  form.addEventListener('submit', onAddSource);
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
      if (type === 'xtream') src.location = xBase(src.location);
      if (!isHttp(src.location)) throw new UserError('La dirección debe empezar por http:// o https://');
      if (insecureOnHttps(src.location)) throw new UserError(MIXED_MSG);
      src.name ||= (() => { try { return new URL(src.location).hostname; } catch { return 'Fuente'; } })();
    }
    btn.disabled = true; btn.textContent = 'Probando conexión…'; show('');
    state.sources.push(src); saveSources();
    const ok = await syncSource(src);
    if (!ok) { const m = src.lastError; state.sources = state.sources.filter((s) => s !== src); saveSources(); idb.del('file:' + src.id); state.tab = 'sources'; render(); $('#f-err').textContent = m; $('#f-err').hidden = false; return; }
    if (!src.password) delete src.password;
    toast(`«${src.name}» añadida: ${src.counts[0]} canales, ${src.counts[1]} películas, ${src.counts[2]} series`);
    state.tab = src.counts[0] ? 'live' : src.counts[1] ? 'movies' : 'series'; state.category = null; render(); focusFirst();
  } catch (ex) { show(ex instanceof UserError ? ex.message : 'No se pudo añadir la fuente.'); btn.disabled = false; btn.textContent = 'Probar y guardar'; }
}

async function renderSeries(d) {
  const s = (state.catalogs[d.sid]?.series || []).find((x) => x.id === d.id);
  if (!s) { state.detail = null; return render(); }
  const src = state.sources.find((x) => x.id === d.sid);
  const fav = state.favorites.has(favKey('series', d.sid, s.id));
  view.innerHTML = `<section class="detail">
    <button class="btn" data-act="back" data-focus>← Volver</button>
    <div class="detail-head"><span class="art poster" style="--h:${hue(s.title)}">${safeImg(s.poster) ? `<img src="${esc(safeImg(s.poster))}" alt="" onerror="this.remove()">` : ''}</span>
      <div><h1>${esc(s.title)}</h1><p class="fine">${[s.year, s.group].filter(Boolean).map(esc).join(' · ')}</p>${s.plot ? `<p>${esc(s.plot)}</p>` : ''}
      <button class="btn" data-act="fav" data-kind="series" data-sid="${esc(d.sid)}" data-id="${esc(s.id)}" data-focus>${fav ? '♥ En favoritos' : '♡ Añadir a favoritos'}</button></div></div>
    <div id="eps"><p class="fine">Cargando episodios…</p></div></section>`;
  focusFirst();
  try {
    if (!s.episodes) { s.episodes = await xtreamEpisodes(src, s.id); idb.put('cat:' + d.sid, state.catalogs[d.sid]); }
    const seasons = [...new Set(s.episodes.map((e) => e.season))];
    const cur = d.season ?? seasons[0];
    $('#eps').innerHTML = !s.episodes.length ? '<p class="empty">No hay episodios disponibles.</p>' : `
      ${seasons.length > 1 ? `<div class="cats horiz">${seasons.map((n) => `<button class="cat" data-act="season" data-n="${n}" aria-pressed="${n === cur}" data-focus>Temporada ${n}</button>`).join('')}</div>` : ''}
      <ol class="episodes">${s.episodes.filter((e) => e.season === cur).map((e) => {
        const p = state.progress[favKey('episode', d.sid, e.id)]; const pct = p && p.d ? (p.t / p.d) * 100 : 0;
        return `<li><button class="ep" data-act="play-ep" data-eid="${esc(e.id)}" data-focus><span class="ep-n">${e.episode}</span><span><b>${esc(e.title)}</b>${e.plot ? `<small>${esc(e.plot)}</small>` : ''}${pct > 2 ? `<span class="bar inline"><i style="width:${pct}%"></i></span>` : ''}</span></button></li>`;
      }).join('')}</ol>`;
  } catch (e) { $('#eps').innerHTML = `<p class="err">${esc(e.message)}</p>`; }
}

// ------------------------------------------------------------------ reproductor
const player = { el: $('#player'), video: $('#video'), hls: null, ts: null, item: null, list: null, retries: 0, saveTimer: null };
const fmtTime = (s) => { s = Math.max(0, Math.floor(s || 0)); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60; return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(x).padStart(2, '0'); };

function destroyEngines() {
  if (player.hls) { player.hls.destroy(); player.hls = null; }
  if (player.ts) { try { player.ts.destroy(); } catch { /* */ } player.ts = null; }
  player.video.removeAttribute('src'); player.video.load();
}

function playerError(msg) {
  $('#p-err-msg').textContent = msg; $('#p-err').hidden = false; $('#p-loading').hidden = true;
  setTimeout(() => $('#p-retry').focus(), 50);
}

function openPlayer(item, list = null) {
  player.item = item; player.list = list; player.retries = 0;
  $('#p-title').textContent = item.title; $('#p-sub').textContent = item.sub || '';
  $('#p-err').hidden = true; $('#p-loading').hidden = false;
  $('#p-fav').textContent = state.favorites.has(favKey(item.kind, item.sid, item.favId || item.id)) ? '♥' : '♡';
  $('#p-prev').hidden = $('#p-next').hidden = !(list && list.length > 1);
  player.el.hidden = false; document.body.classList.add('playing');
  const k = favKey(item.kind, item.sid, item.id);
  state.history = [{ k, title: item.title, at: Date.now() }, ...state.history.filter((h) => h.k !== k)].slice(0, 100); store.set('history', state.history);
  start(item);
  showOverlay();
  $('#p-close').focus();
}

function start(item) {
  destroyEngines();
  const url = item.url, v = player.video;
  if (insecureOnHttps(url)) return playerError(MIXED_MSG);
  const ext = extOf(url);
  const resume = item.kind !== 'live' ? state.progress[favKey(item.kind, item.sid, item.id)] : null;
  const startAt = item.restart ? 0 : resume && resume.t > 15 && resume.t / resume.d < 0.95 ? resume.t : 0;
  const onReady = () => { if (startAt) v.currentTime = startAt; v.play().catch(() => { $('#p-loading').hidden = true; $('#p-tap').hidden = false; }); };
  if ((ext === 'm3u8' || ext === 'm3u') && window.Hls && Hls.isSupported()) {
    const h = new Hls({ enableWorker: true, lowLatencyMode: false });
    player.hls = h;
    h.on(Hls.Events.MANIFEST_PARSED, onReady);
    h.on(Hls.Events.ERROR, (_, d) => {
      if (!d.fatal) return;
      if (d.type === Hls.ErrorTypes.NETWORK_ERROR && player.retries < 3) { player.retries++; setTimeout(() => h.startLoad(), 1000 * 2 ** player.retries); return; }
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && player.retries < 2) { player.retries++; h.recoverMediaError(); return; }
      playerError(d.response && (d.response.code === 401 || d.response.code === 403) ? 'El proveedor denegó el acceso (credenciales o límite de conexiones).'
        : d.type === Hls.ErrorTypes.NETWORK_ERROR ? 'No se pudo cargar el canal. El servidor no responde o no permite reproducir desde navegadores (CORS).' : 'Este stream no se puede reproducir en el navegador.');
    });
    h.loadSource(url); h.attachMedia(v);
  } else if ((ext === 'ts' || (item.kind === 'live' && !ext)) && window.mpegts && mpegts.getFeatureList().mseLivePlayback) {
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: true, url }, { enableWorker: true, lazyLoad: false, liveBufferLatencyChasing: true });
    player.ts = p; p.attachMediaElement(v); p.load();
    p.on(mpegts.Events.ERROR, (type) => {
      if (type === mpegts.ErrorTypes.NETWORK_ERROR && player.retries < 3) { player.retries++; setTimeout(() => start(item), 1000 * 2 ** player.retries); return; }
      playerError(type === mpegts.ErrorTypes.NETWORK_ERROR ? 'No se pudo cargar el canal. El servidor no responde o no permite reproducir desde navegadores (CORS).' : 'Este stream MPEG-TS no se puede reproducir en el navegador.');
    });
    v.addEventListener('loadedmetadata', onReady, { once: true });
  } else {
    v.src = url; v.addEventListener('loadedmetadata', onReady, { once: true });
  }
}
player.video.addEventListener('playing', () => { $('#p-loading').hidden = true; $('#p-tap').hidden = true; player.retries = 0; });
player.video.addEventListener('waiting', () => { if ($('#p-err').hidden) $('#p-loading').hidden = false; });
player.video.addEventListener('error', () => {
  if (player.hls || player.ts) return;
  const ext = extOf(player.item?.url || '');
  playerError(ext === 'mkv' || ext === 'avi' ? `El formato .${ext} no lo reproducen los navegadores. Usa la app Android.` : 'No se pudo reproducir. El formato no es compatible con este navegador o el servidor no responde.');
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
  destroyEngines(); player.el.hidden = true; document.body.classList.remove('playing');
  if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
  player.item = null; render(); focusFirst();
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
  const c = state.catalogs[sid]; if (!c) return null;
  if (kind === 'live') { const x = c.live.find((y) => y.id === id); return x && { kind, sid, id, title: x.name, sub: x.group, url: x.url }; }
  if (kind === 'movie') { const x = c.movies.find((y) => y.id === id); return x && { kind, sid, id, title: x.title, sub: [x.year, x.group].filter(Boolean).join(' · '), url: x.url }; }
  if (kind === 'episode') {
    for (const s of c.series) { const e = (s.episodes || []).find((y) => y.id === id); if (e) return { kind, sid, id, title: s.title, sub: `T${e.season} · E${e.episode} — ${e.title}`, url: e.url, favId: s.id, seriesId: s.id }; }
  }
  return null;
}

document.addEventListener('click', async (e) => {
  const b = e.target.closest('[data-act]'); if (!b) return;
  const { act, kind, sid, id } = b.dataset;
  if (act === 'open') {
    if (kind === 'series') { state.detail = { sid, id }; render(); return; }
    const it = itemFor(kind, sid, id);
    let list = null;
    if (kind === 'live') {
      const q = norm(state.query);
      list = allOf('live').filter((x) => (!state.category || (x.group || 'Sin categoría') === state.category) && (!q || norm(x.name).includes(q))).map((x) => itemFor('live', x.sid, x.id));
    }
    if (it) openPlayer(it, list);
  } else if (act === 'resume') {
    const it = itemFor(kind, sid, id); if (!it) return toast('Ese contenido ya no está en tus fuentes.');
    let list = null;
    if (kind === 'episode') { const s = state.catalogs[sid].series.find((x) => x.id === it.seriesId); list = s.episodes.map((ep) => itemFor('episode', sid, ep.id)); }
    openPlayer(it, list);
  } else if (act === 'play-ep') {
    const d = state.detail; const s = state.catalogs[d.sid].series.find((x) => x.id === d.id);
    const list = s.episodes.map((ep) => itemFor('episode', d.sid, ep.id));
    openPlayer(itemFor('episode', d.sid, b.dataset.eid), list);
  } else if (act === 'season') { state.detail.season = +b.dataset.n; renderSeries(state.detail); }
  else if (act === 'back') { state.detail = null; render(); focusFirst(); }
  else if (act === 'fav') { toggleFav(kind, sid, id); render(); }
  else if (act === 'cat') { state.category = b.dataset.cat || null; state.limit = PAGE; render(); b && document.querySelector(`.cat[data-cat="${CSS.escape(b.dataset.cat)}"]`)?.focus(); }
  else if (act === 'more') { state.limit = (state.limit || PAGE) + PAGE; render(); }
  else if (act === 'goto-sources') { state.tab = 'sources'; render(); focusFirst(); }
  else if (act === 'demo') {
    const src = { id: uid(), type: 'url', name: 'Demostración', location: new URL('demo.m3u', location.href).href };
    state.sources.push(src); saveSources();
    if (await syncSource(src)) { state.tab = 'movies'; render(); focusFirst(); }
    else { const m = src.lastError; state.sources = state.sources.filter((s) => s !== src); saveSources(); toast(m); render(); }
  } else if (act === 'sync') { const s = state.sources.find((x) => x.id === sid); if (s) syncSource(s); }
  else if (act === 'delete') {
    if (b.dataset.confirm !== '1') { b.dataset.confirm = '1'; b.textContent = '¿Seguro? Pulsa otra vez'; setTimeout(() => { if (b.isConnected) { b.dataset.confirm = ''; b.textContent = 'Eliminar'; } }, 4000); return; }
    state.sources = state.sources.filter((x) => x.id !== sid); delete state.catalogs[sid]; saveSources(); idb.del('cat:' + sid); idb.del('file:' + sid); render(); focusFirst();
  }
});

function toggleFav(kind, sid, id) {
  const k = favKey(kind, sid, id);
  if (state.favorites.has(k)) state.favorites.delete(k); else state.favorites.add(k);
  store.set('favorites', [...state.favorites]);
  return state.favorites.has(k);
}

document.querySelectorAll('.tab').forEach((t) => t.addEventListener('click', () => { state.tab = t.dataset.tab; state.category = null; state.detail = null; state.limit = PAGE; render(); }));
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
  await loadCatalogs();
  if (state.sources.length && !Object.keys(state.catalogs).length) state.sources.forEach((s) => syncSource(s));
  render();
  if (!state.sources.length) focusFirst();
  const libs = !!window.Hls && !!window.mpegts;
  if (!libs) console.warn('No se cargaron hls.js o mpegts.js: solo se reproducirán formatos nativos del navegador.');
})();

// Proveedor IPTV simulado para pruebas en emulador Android: catálogo GRANDE y datos "sucios"
// como los de paneles Xtream reales (campos vacíos, tipos cambiados, ids repetidos, info: []...).
//   node tests/android/mock-xtream.mjs [puerto]
//   LIVE=15000 VOD=60000 SERIES=15000 node tests/android/mock-xtream.mjs 8800
import { createServer } from 'node:http';
import { readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const port = +(process.argv[2] ?? 8800);
const N_LIVE = +(process.env.LIVE ?? 15000), N_VOD = +(process.env.VOD ?? 60000), N_SERIES = +(process.env.SERIES ?? 15000);
const MEDIA = join(dirname(fileURLToPath(import.meta.url)), 'media');
const USER = 'user', PASS = 'pass';
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==', 'base64');

const now = Math.floor(Date.now() / 1000);
const cats = (prefix, n) => Array.from({ length: n }, (_, i) => ({ category_id: String(i + 1), category_name: `${prefix} ${i + 1}${i === 3 ? ' 🎬 ÑÜ' : ''}`, parent_id: 0 }));
const liveCats = cats('MX | Canales', 40), vodCats = cats('Películas', 60), seriesCats = cats('Series', 40);
liveCats.push({ category_id: '999', category_name: 'Todas' }); // nombre que coincide con el chip "Todas"
const pick = (arr, i) => arr[i % arr.length].category_id;
const weird = (i) => [null, '', 'N/A', '8,5', 7.4, '11', '-1'][i % 7];

const live = Array.from({ length: N_LIVE }, (_, i) => ({
  num: i + 1, name: i % 500 === 7 ? null : `Canal ${i + 1}${i % 13 === 0 ? ' HD ⚽' : ''}`, stream_type: 'live',
  stream_id: i % 1000 === 999 ? i : i + 1, // algunos ids repetidos
  stream_icon: i % 3 ? `http://HOST/img/${i}.png` : '', epg_channel_id: i < 300 ? `ch${i + 1}` : null,
  added: String(now - i * 60), category_id: i % 97 === 0 ? null : pick(liveCats, i), tv_archive: i % 50 === 0 ? 1 : 0, tv_archive_duration: i % 50 === 0 ? '3' : 0,
}));
const vod = Array.from({ length: N_VOD }, (_, i) => ({
  num: i + 1, name: i % 777 === 5 ? '' : `Película ${i + 1}${i % 11 === 0 ? ' (2024)' : ''}${i % 5000 === 1 ? ' ' + 'muy larga '.repeat(30) : ''}`,
  stream_type: 'movie', stream_id: i % 2000 === 1999 ? i : i + 1, stream_icon: `http://HOST/img/m${i}.png`,
  rating: weird(i), rating_5based: i % 5, added: String(now - i * 30), category_id: i % 89 === 0 ? '12345' : pick(vodCats, i),
  container_extension: ['mp4', 'mkv', 'mp4', 'avi', null][i % 5], plot: i % 4 ? `Sinopsis ${i}` : null, genre: i % 2 ? 'Acción, Drama' : '',
  year: i % 3 ? String(1990 + (i % 35)) : '',
  // Campos extra que envían los paneles reales (aumentan el tamaño como en la vida real).
  tmdb: String(100000 + i), trailer: '', direct_source: '', custom_sid: null, is_adult: '0', category_ids: [i % 60 + 1],
}));
const series = Array.from({ length: N_SERIES }, (_, i) => ({
  num: i + 1, name: `Serie ${i + 1}`, series_id: i + 1, cover: i % 4 ? `http://HOST/img/s${i}.png` : null,
  plot: 'Trama', cast: '', director: '', genre: 'Drama', releaseDate: i % 2 ? '2020-01-01' : null, last_modified: String(now - i * 45),
  rating: weird(i), backdrop_path: i % 3 ? [`http://HOST/img/b${i}.png`] : [], category_id: pick(seriesCats, i),
}));

function seriesInfo(id) {
  const n = +id;
  const ep = (season, e, eid) => ({ id: String(eid), episode_num: e, title: `Episodio ${e}`, container_extension: 'mp4', season,
    info: n % 4 === 0 ? [] : { duration_secs: 1500, duration: '00:25:00', plot: 'Capítulo', movie_image: n % 2 ? `http://HOST/img/e${eid}.png` : '' } });
  // Formatos distintos según el panel: objeto por temporada, array de arrays, temporadas vacías, ids repetidos.
  if (n % 5 === 0) return { info: [], episodes: [[ep(1, 1, n * 100 + 1), ep(1, 2, n * 100 + 2)], [ep(2, 1, n * 100 + 3)]] };
  if (n % 7 === 0) return { info: { name: `Serie ${n}` }, episodes: [] };
  const episodes = {};
  for (let s = 1; s <= 3; s++) episodes[String(s)] = Array.from({ length: 10 }, (_, e) => ep(s, e + 1, n * 100 + s * 10 + e));
  if (n % 3 === 0) episodes['1'].push(ep(1, 10, n * 100 + 19)); // episodio repetido
  return { seasons: [], info: { name: `Serie ${n}`, plot: 'Trama' }, episodes };
}

const cache = {};
const body = (key, make, host) => (cache[key] ??= JSON.stringify(make())).replaceAll('http://HOST', host);

function sendFile(req, res, file, type) {
  const size = statSync(file).size, data = readFileSync(file);
  const m = req.headers.range && /bytes=(\d+)-(\d*)/.exec(req.headers.range);
  if (m) { const s = +m[1], e = m[2] ? +m[2] : size - 1; res.writeHead(206, { 'content-type': type, 'content-range': `bytes ${s}-${e}/${size}`, 'accept-ranges': 'bytes', 'content-length': e - s + 1 }); return res.end(data.subarray(s, e + 1)); }
  res.writeHead(200, { 'content-type': type, 'content-length': size, 'accept-ranges': 'bytes' }); res.end(data);
}

// Canal en vivo .ts: emisión continua (repite el clip) como un stream real que nunca termina.
function liveTs(res) {
  const data = readFileSync(join(MEDIA, 'live.ts'));
  res.writeHead(200, { 'content-type': 'video/mp2t' });
  let off = 0, alive = true;
  res.on('close', () => { alive = false; });
  const tick = () => { if (!alive) return; const chunk = data.subarray(off, off + 64 * 1024); off = (off + chunk.length) % data.length; res.write(chunk); setTimeout(tick, 120); };
  tick();
}

let hits = 0;
createServer((req, res) => {
  hits++;
  const u = new URL(req.url, 'http://x');
  const host = `http://${req.headers.host}`;
  const json = (s) => { res.writeHead(200, { 'content-type': 'application/json' }); res.end(s); };
  if (u.pathname === '/player_api.php') {
    if (u.searchParams.get('username') !== USER || u.searchParams.get('password') !== PASS) return json('{"user_info":{"auth":0}}');
    const a = u.searchParams.get('action');
    if (!a) return json(JSON.stringify({ user_info: { username: USER, auth: 1, status: 'Active', exp_date: String(now + 86400 * 90), max_connections: '1', active_cons: '0', allowed_output_formats: ['m3u8', 'ts', 'rtmp'] }, server_info: { timezone: 'America/Mexico_City' } }));
    switch (a) {
      case 'get_live_categories': return json(body('lc', () => liveCats, host));
      case 'get_vod_categories': return json(body('vc', () => vodCats, host));
      case 'get_series_categories': return json(body('sc', () => seriesCats, host));
      case 'get_live_streams': return json(body('ls', () => live, host));
      case 'get_vod_streams': return json(body('vs', () => vod, host));
      case 'get_series': return json(body('ss', () => series, host));
      case 'get_vod_info': {
        const id = +u.searchParams.get('vod_id');
        return json(JSON.stringify(id % 3 === 0 ? { info: [], movie_data: { stream_id: id } } : { info: { plot: `Sinopsis completa ${id}`, genre: 'Acción', duration_secs: 5400, rating: '7.5', backdrop_path: [`${host}/img/b.png`] }, movie_data: { stream_id: id } }));
      }
      case 'get_series_info': return json(JSON.stringify(seriesInfo(u.searchParams.get('series_id'))));
      case 'get_short_epg': return json('{"epg_listings":[]}');
      default: return json('[]');
    }
  }
  if (u.pathname === '/xmltv.php') {
    res.writeHead(200, { 'content-type': 'application/xml' });
    const t = (d) => new Date(d * 1000).toISOString().replace(/[-:T]/g, '').slice(0, 14) + ' +0000';
    let x = '<?xml version="1.0" encoding="UTF-8"?><tv>';
    for (let c = 1; c <= 300; c++) {
      x += `<channel id="ch${c}"><display-name>Canal ${c}</display-name></channel>`;
      for (let p = -2; p < 24; p++) { const s = now - (now % 3600) + p * 3600; x += `<programme start="${t(s)}" stop="${t(s + 3600)}" channel="ch${c}"><title>Programa ${p} &amp; más</title><desc>Descripción</desc></programme>`; }
      if (c % 10 === 0) x += `<programme start="${t(now)}" stop="${t(now + 1800)}" channel="ch${c}"><title>Duplicado</title></programme>`;
    }
    return res.end(x + '</tv>');
  }
  if (u.pathname === '/get.php') {
    res.writeHead(200, { 'content-type': 'audio/x-mpegurl' });
    const lines = ['#EXTM3U x-tvg-url="' + host + '/xmltv.php"'];
    for (const c of live) lines.push(`#EXTINF:-1 tvg-id="${c.epg_channel_id ?? ''}" tvg-logo="${c.stream_icon.replace('http://HOST', host)}" group-title="Canales",${c.name ?? ''}`, `${host}/live/${USER}/${PASS}/${c.stream_id}.ts`);
    for (const m of vod) lines.push(`#EXTINF:-1 tvg-logo="${m.stream_icon.replace('http://HOST', host)}" group-title="VOD",${m.name}`, `${host}/movie/${USER}/${PASS}/${m.stream_id}.${m.container_extension ?? 'mp4'}`);
    for (const s of series.slice(0, 3000)) for (let e = 1; e <= 5; e++) lines.push(`#EXTINF:-1 group-title="Series",${s.name} S01 E0${e}`, `${host}/series/${USER}/${PASS}/${s.series_id * 100 + e}.mp4`);
    return res.end(lines.join('\n'));
  }
  if (u.pathname.startsWith('/img/')) { res.writeHead(200, { 'content-type': 'image/png', 'cache-control': 'max-age=3600' }); return res.end(PNG); }
  const m = /^\/(live|movie|series)\/([^/]+)\/([^/]+)\/([^/.]+)\.(\w+)$/.exec(u.pathname);
  if (m) {
    if (m[2] !== USER || m[3] !== PASS) { res.writeHead(403); return res.end(); }
    if (m[1] === 'live' && m[5] === 'm3u8') {
      res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' });
      return res.end(readFileSync(join(MEDIA, 'hls/live.m3u8'), 'utf8').replace(/^(seg\d+\.ts)$/gm, '/hls/$1'));
    }
    if (m[1] === 'live') return liveTs(res);
    return sendFile(req, res, join(MEDIA, 'clip.mp4'), 'video/mp4'); // mkv/avi del catálogo también entregan el clip
  }
  if (u.pathname.startsWith('/hls/')) return sendFile(req, res, join(MEDIA, u.pathname.slice(1)), u.pathname.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t');
  res.writeHead(404); res.end();
}).listen(port, '0.0.0.0', () => console.log(`proveedor simulado en :${port} (canales ${N_LIVE}, películas ${N_VOD}, series ${N_SERIES})`));
setInterval(() => { if (hits) { console.log(`peticiones: ${hits}`); hits = 0; } }, 30000).unref();

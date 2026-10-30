// Servidor de pruebas: sirve web-player/ y simula un proveedor Xtream con casos difíciles.
//   node tests/e2e/xtream-mock.mjs <carpeta-web> <puerto>
import { createServer } from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { join, extname } from 'node:path';
const [dir, port = 8766] = process.argv.slice(2);
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.webp': 'image/webp', '.webmanifest': 'application/manifest+json', '.webm': 'video/webm', '.m3u': 'audio/x-mpegurl' };
const json = (res, body, delay = 0) => setTimeout(() => { res.writeHead(200, { 'content-type': 'application/json', 'access-control-allow-origin': '*' }); res.end(JSON.stringify(body)); }, delay);
createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  if (u.pathname === '/player_api.php') {
    if (u.searchParams.get('password') !== 'clave') return json(res, { user_info: { auth: 0 } });
    const a = u.searchParams.get('action');
    if (!a) return json(res, { user_info: { auth: 1, status: 'Active', allowed_output_formats: ['m3u8', 'ts'] }, server_info: {} });
    if (a === 'get_live_categories') return json(res, [{ category_id: '1', category_name: 'MEXICO' }]);
    if (a === 'get_live_streams') return json(res, [
      { stream_id: 1, name: 'MX | CANAL QUE NO RESPONDE', category_id: '1', num: 1 },
      { stream_id: 2, name: 'MX | CANAL CAIDO', category_id: '1', num: 2 },
      { stream_id: 3, name: 'MX | CANAL BUENO', category_id: '1', num: 3 }]);
    if (a === 'get_vod_categories') return json(res, [{ category_id: '9', category_name: 'Estrenos' }]);
    if (a === 'get_vod_streams') return json(res, [{ stream_id: 50, name: 'Película lenta', category_id: '9', container_extension: 'webm' }], 4000);
    if (a === 'get_series_categories') return json(res, []);
    if (a === 'get_series') return json(res, [], 4000);
    return json(res, []);
  }
  if (u.pathname.startsWith('/live/')) {
    const id = u.pathname.split('/').pop();
    if (id.startsWith('1.')) return; // nunca responde (como el canal de la captura)
    if (id.startsWith('2.')) { res.writeHead(404, { 'access-control-allow-origin': '*' }); return res.end(); }
    if (id === '3.m3u8') { res.writeHead(302, { location: '/live-ok.webm', 'access-control-allow-origin': '*' }); return res.end(); }
  }
  if (u.pathname.startsWith('/movie/')) { res.writeHead(302, { location: '/clip.webm' }); return res.end(); }
  let f = join(dir, u.pathname === '/' ? 'index.html' : decodeURIComponent(u.pathname));
  if (!existsSync(f) || !statSync(f).isFile()) { res.writeHead(404); return res.end(); }
  const data = readFileSync(f);
  const range = req.headers.range && /bytes=(\d+)-(\d*)/.exec(req.headers.range);
  if (range) { const s = +range[1], e = range[2] ? +range[2] : data.length - 1; res.writeHead(206, { 'content-type': TYPES[extname(f)] || 'application/octet-stream', 'content-range': `bytes ${s}-${e}/${data.length}`, 'accept-ranges': 'bytes', 'content-length': e - s + 1 }); return res.end(data.subarray(s, e + 1)); }
  res.writeHead(200, { 'content-type': TYPES[extname(f)] || 'application/octet-stream', 'accept-ranges': 'bytes' }); res.end(data);
}).listen(+port, () => console.log('mock en', port));

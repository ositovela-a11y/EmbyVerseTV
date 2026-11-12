// Laboratorio: ¿una PÁGINA WEB (https, como un «MaxPlayer web») puede reproducir listas M3U reales,
// o hace falta la app de PC? Toma canales públicos reales (iptv-org) y, para cada uno, intenta bajar la
// lista del canal y su primer pedazo de video de dos formas:
//   · «App de PC»: sin las reglas del navegador (lo mismo que hace la app de Windows).
//   · «Página web»: desde una página https en Chromium, con las reglas normales (CORS y contenido mixto).
// No mide imagen (el Chromium de pruebas no trae H.264); mide si el navegador DEJA bajar el video,
// que es lo que decide si una página web puede reproducirlo.   Uso: node tests/web-lab.mjs
import { createServer } from 'node:https';
import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('playwright');

const LISTS = ['mx', 'es', 'ar', 'co', 'us'].map((c) => `https://iptv-org.github.io/iptv/countries/${c}.m3u`);
const PER_LIST = 14;
const TIMEOUT = 8000;

function parse(text) {
  const out = []; let title = null;
  for (const raw of text.split(/\r?\n/)) {
    const l = raw.trim();
    if (l.startsWith('#EXTINF')) { title = l.slice(l.lastIndexOf(',') + 1).trim(); continue; }
    if (title && /^https?:\/\//i.test(l)) { out.push({ title, url: l.split('|')[0] }); title = null; }
  }
  return out;
}
const firstChild = (text, base) => {
  for (const l of text.split(/\r?\n/)) { const s = l.trim(); if (s && !s.startsWith('#')) { try { return new URL(s, base).toString(); } catch { return null; } } }
  return null;
};

// ---- Sin reglas del navegador (como la app de PC) ----
async function direct(url) {
  const get = async (u) => {
    const c = new AbortController(); const t = setTimeout(() => c.abort(), TIMEOUT);
    try { const r = await fetch(u, { signal: c.signal, headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36' } });
      if (!r.ok) return { ok: false, why: `HTTP ${r.status}` };
      const ct = r.headers.get('content-type') || '';
      const buf = new Uint8Array(await r.arrayBuffer().catch(() => new ArrayBuffer(0)));
      return { ok: true, text: /mpegurl|text|octet/i.test(ct) || /\.m3u8/i.test(u) ? new TextDecoder().decode(buf.slice(0, 200000)) : '', ct };
    } catch (e) { return { ok: false, why: e.name === 'AbortError' ? 'tiempo agotado' : 'sin conexión' }; } finally { clearTimeout(t); }
  };
  let r = await get(url); if (!r.ok) return r;
  // Lista HLS: se baja una variante y un segmento.
  for (let depth = 0; depth < 2 && /#EXTM3U/.test(r.text); depth++) {
    const next = firstChild(r.text, url); if (!next) break;
    url = next; r = await get(next); if (!r.ok) return { ok: false, why: 'segmento ' + r.why };
  }
  return { ok: true };
}

// ---- Página https con las reglas del navegador ----
execSync('openssl req -x509 -newkey rsa:2048 -nodes -keyout /tmp/lab.key -out /tmp/lab.crt -days 1 -subj "/CN=localhost" 2>/dev/null');
const srv = createServer({ key: readFileSync('/tmp/lab.key'), cert: readFileSync('/tmp/lab.crt') }, (_q, s) => { s.writeHead(200, { 'content-type': 'text/html' }); s.end('<!doctype html><title>web</title>'); }).listen(8443);
const browser = await chromium.launch();
const page = await (await browser.newContext({ ignoreHTTPSErrors: true })).newPage();
await page.goto('https://localhost:8443/');
async function web(url) {
  return page.evaluate(async ({ url, TIMEOUT }) => {
    if (url.startsWith('http:')) return { ok: false, why: 'contenido mixto (http en página https)' };
    const get = async (u) => {
      if (u.startsWith('http:')) return { ok: false, why: 'contenido mixto (http en página https)' };
      const c = new AbortController(); const t = setTimeout(() => c.abort(), TIMEOUT);
      try { const r = await fetch(u, { signal: c.signal }); if (!r.ok) return { ok: false, why: `HTTP ${r.status}` };
        const txt = /\.m3u8/i.test(u) || /mpegurl/i.test(r.headers.get('content-type') || '') ? await r.text() : ''; return { ok: true, text: txt };
      } catch (e) { return { ok: false, why: e.name === 'AbortError' ? 'tiempo agotado' : 'bloqueado por CORS' }; } finally { clearTimeout(t); }
    };
    let r = await get(url); if (!r.ok) return r;
    for (let d = 0; d < 2 && /#EXTM3U/.test(r.text); d++) {
      let next = null; for (const l of r.text.split(/\r?\n/)) { const s = l.trim(); if (s && !s.startsWith('#')) { next = new URL(s, url).toString(); break; } }
      if (!next) break; url = next; r = await get(next); if (!r.ok) return { ok: false, why: 'segmento ' + r.why };
    }
    return { ok: true };
  }, { url, TIMEOUT });
}

let total = 0, okPc = 0, okWeb = 0; const whyWeb = {};
for (const list of LISTS) {
  let items;
  try { items = parse(await (await fetch(list)).text()); } catch { console.log('LISTA', list, 'no se pudo bajar'); continue; }
  const sample = items.length <= PER_LIST ? items : Array.from({ length: PER_LIST }, (_, i) => items[Math.floor(i * items.length / PER_LIST)]);
  console.log(`LISTA ${list.split('/').pop()}: ${items.length} canales · http ${items.filter((x) => x.url.startsWith('http:')).length} / https ${items.filter((x) => x.url.startsWith('https:')).length}`);
  for (const ch of sample) {
    total++;
    const [a, b] = [await direct(ch.url), await web(ch.url)];
    if (a.ok) okPc++;
    if (b.ok) okWeb++;
    if (a.ok && !b.ok) whyWeb[b.why] = (whyWeb[b.why] || 0) + 1;
    console.log(`  ${a.ok ? '✓' : '✗'} PC  ${b.ok ? '✓' : '✗'} WEB  ${ch.title.slice(0, 34)}${a.ok ? '' : ' · PC: ' + a.why}${b.ok || !a.ok ? '' : ' · WEB: ' + b.why}`);
  }
}
// Servidor Xtream del proveedor (el de la app): ¿permite que una página web lo consulte (CORS)?
for (const host of (process.env.PROVIDERS || 'https://krnosmx.com:443').split(',')) {
  const u = `${host}/player_api.php?username=prueba&password=prueba`;
  let cors = '(sin respuesta)', st = '';
  try { const r = await fetch(u, { signal: AbortSignal.timeout(TIMEOUT) }); st = String(r.status); cors = r.headers.get('access-control-allow-origin') ?? '(no envía CORS)'; } catch (e) { st = e.name; }
  const w = await web(u.replace(/^http:/, 'http:'));
  console.log(`PROVEEDOR ${host}: responde ${st} · Access-Control-Allow-Origin ${cors} · desde página web: ${w.ok ? 'sí' : 'no (' + w.why + ')'}`);
}
console.log(`\nRESUMEN: ${total} canales · se pueden bajar desde la app de PC ${okPc} · desde una página web https ${okWeb}`);
console.log('Canales que la PC sí baja pero la página web no, por qué:', JSON.stringify(whyWeb));
await browser.close(); srv.close();

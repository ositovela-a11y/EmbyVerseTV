// Prueba el sitio con dominio propio sin internet: se simula GitHub Pages y GitHub Releases.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import worker, { ORIGIN, RELEASES } from '../src/index.js';
const PAGE = readFileSync(new URL('../../web-player/index.html', import.meta.url), 'utf8');
const PC = readFileSync(new URL('../../web-player/pc/index.html', import.meta.url), 'utf8');
const cfg = (name, logo) => JSON.stringify({ brand: { appName: name, ...(logo ? { logo: { variant: 'asset', url: `${ORIGIN}/config/assets/${logo}` } } : { logo: { variant: 'bundled_full' } }) } });
const calls = [];
globalThis.fetch = async (url, init = {}) => {
  calls.push([url, init]);
  if (url === ORIGIN + '/a') return new Response(null, { status: 301, headers: { location: ORIGIN + '/a/', 'x-github-request-id': 'x' } });
  if (url === ORIGIN + '/a/?v=planetatv') return new Response('<a href="../apk/x.apk">ok</a>', { headers: { 'content-type': 'text/html; charset=utf-8', server: 'GitHub.com' } });
  if (url === ORIGIN + '/?v=planetatv' || url === ORIGIN + '/?v=sinlogo' || url === ORIGIN + '/?v=noexiste' || url === ORIGIN + '/index.html') return new Response(PAGE, { headers: { 'content-type': 'text/html; charset=utf-8' } });
  if (url === ORIGIN + '/pc/?v=planetatv') return new Response(PC, { headers: { 'content-type': 'text/html; charset=utf-8' } });
  if (url === ORIGIN + '/config/vendors/planetatv.json') return new Response(cfg('Planeta <TV>', 'logo-p.png'), { headers: { 'content-type': 'application/json' } });
  if (url === ORIGIN + '/config/vendors/sinlogo.json') return new Response(cfg('Sin Logo'), { headers: { 'content-type': 'application/json' } });
  if (url === ORIGIN + '/config/app.json') return new Response(cfg('EmbyVerse'), { headers: { 'content-type': 'application/json' } });
  if (url === ORIGIN + '/') return new Response(`<p>${ORIGIN}/a/</p>`, { headers: { 'content-type': 'text/html' } });
  if (url === ORIGIN + '/apk/EmbyVerse-planetatv.apk?v=33') return new Response(new Uint8Array([80, 75, 3, 4]), { headers: { 'content-type': 'application/vnd.android.package-archive', 'content-length': '4' } });
  if (url === RELEASES + 'planetatv-PC-Instalador.exe') return new Response(new Uint8Array([77, 90]), { headers: { 'content-length': '2' } });
  return new Response('GitHub 404', { status: 404, headers: { 'content-type': 'text/html' } });
};
const get = (p, m = 'GET') => worker.fetch(new Request('https://eversetv.com' + p, { method: m }));
let n = 0; const t = async (name, f) => { await f(); n++; console.log('✔', name); };

await t('www → sin www', async () => { const r = await worker.fetch(new Request('https://www.eversetv.com/a/?v=x')); assert.equal(r.status, 301); assert.equal(r.headers.get('location'), 'https://eversetv.com/a/?v=x'); });
await t('redirección de GitHub reescrita a tu dominio', async () => { const r = await get('/a'); assert.equal(r.status, 301); assert.equal(r.headers.get('location'), 'https://eversetv.com/a/'); assert.equal(r.headers.get('x-github-request-id'), null); });
await t('página de Downloader del vendedor', async () => { const r = await get('/a/?v=planetatv'); assert.equal(r.status, 200); assert.match(await r.text(), /apk\/x\.apk/); assert.equal(r.headers.get('server'), null); });
await t('ninguna dirección de GitHub en las páginas', async () => { const b = await (await get('/')).text(); assert.ok(!b.includes('github')); assert.ok(b.includes('https://eversetv.com/a/')); });
await t('APK pasa tal cual', async () => { const r = await get('/apk/EmbyVerse-planetatv.apk?v=33'); assert.deepEqual([...new Uint8Array(await r.arrayBuffer())], [80, 75, 3, 4]); assert.equal(r.headers.get('content-type'), 'application/vnd.android.package-archive'); });
await t('instalador de PC con tu dominio', async () => { const r = await get('/descargas/pc/planetatv-PC-Instalador.exe'); assert.equal(r.status, 200); assert.match(r.headers.get('content-disposition'), /planetatv-PC-Instalador\.exe/); assert.equal((await r.arrayBuffer()).byteLength, 2); });
await t('instalador inexistente: aviso propio', async () => { const r = await get('/descargas/pc/otro-PC-Instalador.exe'); assert.equal(r.status, 404); assert.ok(!(await r.text()).includes('GitHub')); });
await t('nombres raros rechazados', async () => { const r = await get('/descargas/pc/..%2Fx.exe'); assert.equal(r.status, 404); assert.ok(!calls.some(([u]) => u.includes('x.exe'))); });
await t('404 propio, sin la página de GitHub', async () => { const r = await get('/nada'); assert.equal(r.status, 404); const b = await r.text(); assert.ok(!b.includes('GitHub')); });
await t('solo lectura', async () => { assert.equal((await get('/', 'POST')).status, 405); });
const html = async (p) => { const r = await get(p); assert.equal(r.status, 200); return r.text(); };
const logoTags = (b) => b.match(/<img class="logo"[^>]*>/g) || [];
await t('página del vendedor ya llega con su logo y nombre (sin el de EmbyVerse)', async () => {
  const b = await html('/?v=planetatv');
  const imgs = logoTags(b); assert.equal(imgs.length, 1);
  assert.match(imgs[0], /src="https:\/\/eversetv\.com\/config\/assets\/logo-p\.png"/);
  assert.ok(!b.includes('data:image/webp'));
  assert.match(b, /<h1 id="name">Planeta &lt;TV&gt;<\/h1>/);
  assert.match(b, /<title>Planeta &lt;TV&gt; — Descargar<\/title>/);
  assert.ok(!/EmbyVerse/.test(b.replace(/EmbyVerse-|EmbyVerseTV/g, '').replace(/<script[\s\S]*?<\/script>/g, '').replace(/<!--[\s\S]*?-->/g, '')), 'sin nombre EmbyVerse visible');
  assert.match(b, /data-brand="planetatv"/);
});
await t('vista previa del enlace (WhatsApp) con la marca del vendedor', async () => {
  const b = await html('/?v=planetatv');
  assert.match(b, /<meta property="og:image" content="https:\/\/eversetv\.com\/config\/assets\/logo-p\.png">/);
  assert.match(b, /<meta property="og:title" content="Planeta &lt;TV&gt; — Descargar">/);
  assert.match(b, /<meta property="og:url" content="https:\/\/eversetv\.com\/\?v=planetatv">/);
  assert.match(b, /<link rel="icon" href="https:\/\/eversetv\.com\/config\/assets\/logo-p\.png">/);
  assert.ok(!b.includes('icon-192.png'));
});
await t('vendedor sin logo: ningún logo, tampoco el de EmbyVerse', async () => {
  const b = await html('/?v=sinlogo');
  assert.equal(logoTags(b).length, 0); assert.ok(!b.includes('og:image')); assert.ok(!b.includes('data:image/webp'));
  assert.match(b, /<h1 id="name">Sin Logo<\/h1>/);
});
await t('tu app principal: tu logo y tu vista previa', async () => {
  const b = await html('/index.html');
  assert.equal(logoTags(b).length, 1); assert.ok(b.includes('data:image/webp'));
  assert.match(b, /<meta property="og:image" content="https:\/\/eversetv\.com\/logo\.png">/);
  assert.match(b, /<title>EmbyVerse — Descargar<\/title>/);
});
await t('enlace de PC del vendedor con su vista previa', async () => {
  const b = await html('/pc/?v=planetatv');
  assert.match(b, /<title>Planeta &lt;TV&gt; — App para PC<\/title>/); assert.match(b, /og:image" content="https:\/\/eversetv\.com\/config\/assets\/logo-p\.png"/);
});
await t('vendedor inexistente: no se rompe', async () => { const b = await html('/?v=noexiste'); assert.ok(b.includes('<h1 id="name">')); });
console.log(`${n} pruebas OK`);

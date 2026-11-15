// Prueba el sitio con dominio propio sin internet: se simula GitHub Pages y GitHub Releases.
import assert from 'node:assert/strict';
import worker, { ORIGIN, RELEASES } from '../src/index.js';
const calls = [];
globalThis.fetch = async (url, init = {}) => {
  calls.push([url, init]);
  if (url === ORIGIN + '/a') return new Response(null, { status: 301, headers: { location: ORIGIN + '/a/', 'x-github-request-id': 'x' } });
  if (url === ORIGIN + '/a/?v=planetatv') return new Response('<a href="../apk/x.apk">ok</a>', { headers: { 'content-type': 'text/html; charset=utf-8', server: 'GitHub.com' } });
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
console.log(`${n} pruebas OK`);

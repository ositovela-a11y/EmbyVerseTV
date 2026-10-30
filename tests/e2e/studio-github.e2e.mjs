// Design Studio en modo GitHub (web-player/studio/) contra una API de GitHub SIMULADA en el navegador:
// entrar con token, cambiar nombre/lema, subir logotipo, publicar, historial y apps de vendedores.
//   BASE=http://localhost:8765 (sirve una copia de web-player/) node tests/e2e/studio-github.e2e.mjs
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
const require = createRequire(import.meta.url);
let pw; try { pw = require('playwright'); } catch { pw = require(process.env.PLAYWRIGHT_PATH); }
const BASE = process.env.BASE ?? 'http://localhost:8765';
const OUT = process.env.OUT ?? 'docs/screenshots/panel';
const results = [];
const step = async (n, f) => { try { results.push(['✔', n, (await f()) ?? '']); } catch (e) { results.push(['✘', n, e.message.split('\n')[0]]); } };

// ---- GitHub simulado (contenidos en memoria) ----
const files = new Map([['web-player/config/app.json', readFileSync(new URL('../../web-player/config/app.json', import.meta.url))]]);
const commits = [];
let sha = 0; const shaOf = new Map();
const b64 = (buf) => Buffer.from(buf).toString('base64');
const seen = { auth: [] };
async function fakeGitHub(route) {
  const req = route.request(); const u = new URL(req.url()); const m = req.method();
  seen.auth.push(req.headers().authorization);
  const repo = '/repos/ositovela-a11y/EmbyVerseTV';
  const json = (status, body) => route.fulfill({ status, contentType: 'application/json', headers: { 'access-control-allow-origin': '*' }, body: JSON.stringify(body) });
  if (u.pathname === repo) return json(200, { permissions: { push: true } });
  if (u.pathname === `${repo}/commits`) return json(200, commits.slice().reverse());
  const cm = /^\/repos\/ositovela-a11y\/EmbyVerseTV\/contents\/(.+)$/.exec(u.pathname);
  if (!cm) return json(404, { message: 'Not Found' });
  const path = decodeURIComponent(cm[1]);
  const info = (p) => ({ name: p.split('/').pop(), path: p, sha: shaOf.get(p) ?? 'sha0', size: files.get(p).length, download_url: null });
  if (m === 'GET') {
    if (files.has(path)) return json(200, { ...info(path), content: b64(files.get(path)) });
    const dir = [...files.keys()].filter((k) => k.startsWith(path + '/'));
    return dir.length ? json(200, dir.map(info)) : json(404, { message: 'Not Found' });
  }
  if (m === 'PUT') {
    const body = JSON.parse(req.postData());
    if (files.has(path) && body.sha !== (shaOf.get(path) ?? 'sha0')) return json(409, { message: 'sha mismatch' });
    files.set(path, Buffer.from(body.content, 'base64')); shaOf.set(path, 'sha' + (++sha));
    commits.push({ sha: 'c' + sha, commit: { message: body.message, author: { date: new Date().toISOString(), name: 'yo' } } });
    return json(200, { content: info(path) });
  }
  if (m === 'DELETE') { files.delete(path); return json(200, {}); }
  return json(405, {});
}

const browser = await pw.chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
page.setDefaultTimeout(15000);
await page.route('https://api.github.com/**', fakeGitHub);
// Las vistas previas de imágenes subidas se piden a raw.githubusercontent.com: se sirven desde la memoria.
await page.route('https://raw.githubusercontent.com/**', (r) => {
  const p = decodeURIComponent(new URL(r.request().url()).pathname.split('/main/')[1] ?? '');
  return files.has(p) ? r.fulfill({ status: 200, contentType: 'image/png', body: files.get(p) }) : r.fulfill({ status: 404 });
});

await step('Entrar con token de GitHub', async () => {
  await page.goto(`${BASE}/studio/`);
  await page.locator('#tok').fill('github_pat_PRUEBA');
  await page.getByRole('button', { name: 'Entrar' }).click();
  await page.getByText('Design Studio').first().waitFor();
  await page.getByRole('button', { name: 'Identidad' }).click();
  if (!seen.auth.every((a) => a === 'Bearer github_pat_PRUEBA')) throw new Error('token no enviado');
  return 'panel abierto';
});
await step('Cambiar nombre y lema, subir logotipo', async () => {
  await page.getByLabel('Nombre comercial').fill('Mi Marca TV');
  await page.getByLabel('Lema').fill('Todo tu entretenimiento');
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.getByText('Subir mi logotipo').click()]);
  await chooser.setFiles(new URL('../../admin-web/public/brand/embyverse-icon.png', import.meta.url).pathname);
  await page.waitForFunction(() => document.querySelector('.choice.on img')?.getAttribute('src')?.includes('raw.githubusercontent.com'));
  await page.screenshot({ path: `${OUT}/14-studio-identidad.png` });
  return 'logotipo subido al repositorio y seleccionado';
});
await step('Publicar: se escribe web-player/config/app.json con versión nueva', async () => {
  await page.getByRole('button', { name: 'Publicar…' }).click();
  await page.locator('.btn-publish', { hasText: /^Publicar$/ }).click();
  await page.getByText(/Publicada la versión 2/).waitFor();
  const cfg = JSON.parse(files.get('web-player/config/app.json').toString());
  if (cfg.version !== 2 || cfg.brand.appName !== 'Mi Marca TV' || cfg.brand.logo.variant !== 'asset') throw new Error(JSON.stringify(cfg.brand));
  const logoFile = [...files.keys()].find((k) => k.startsWith('web-player/config/assets/logo-'));
  if (!logoFile) throw new Error('no se subió el logotipo');
  return `versión ${cfg.version}, commit «${commits.at(-1).commit.message}»`;
});
await step('Historial de versiones desde los commits', async () => {
  await page.getByRole('button', { name: 'Versiones' }).click();
  await page.getByText('Versión 2', { exact: false }).first().waitFor();
  return 'versión 2 listada';
});
await step('Crear app de vendedor y editar su diseño por separado', async () => {
  await page.getByRole('button', { name: 'Vendedores' }).click();
  await page.getByLabel('Nombre del vendedor').fill('Juan TV');
  await page.getByRole('button', { name: 'Crear app de vendedor' }).click();
  await page.getByText(/Vendedor creado/).waitFor();
  const idx = JSON.parse(files.get('web-player/config/vendors/index.json').toString());
  if (idx[0]?.slug !== 'juantv') throw new Error(JSON.stringify(idx));
  await Promise.all([page.waitForEvent('load'), page.locator('.vendor', { hasText: 'Juan TV' }).getByRole('button', { name: 'Editar su app' }).click()]);
  await page.locator('.pill', { hasText: 'App de Juan TV' }).waitFor();
  await page.getByRole('button', { name: 'Identidad' }).click();
  if ((await page.getByLabel('Nombre comercial').inputValue()) !== 'Juan TV') throw new Error('no cargó el diseño del vendedor');
  await page.getByLabel('Lema').fill('La tele de Juan');
  await page.getByRole('button', { name: 'Publicar…' }).click();
  await page.locator('.btn-publish', { hasText: /^Publicar$/ }).click();
  await page.getByText(/Publicada la versión 2/).waitFor();
  const v = JSON.parse(files.get('web-player/config/vendors/juantv.json').toString());
  const main = JSON.parse(files.get('web-player/config/app.json').toString());
  if (v.brand.tagline !== 'La tele de Juan' || main.brand.appName !== 'Mi Marca TV') throw new Error('se mezclaron los diseños');
  await page.getByRole('button', { name: 'Vendedores' }).click();
  await page.screenshot({ path: `${OUT}/16-studio-vendedores.png` });
  return 'vendors/juantv.json publicado; la app principal no cambió';
});
await browser.close();
for (const r of results) console.log(r.join('  '));
process.exit(results.some((r) => r[0] === '✘') ? 1 : 0);

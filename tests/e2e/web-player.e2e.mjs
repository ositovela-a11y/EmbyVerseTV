// Prueba E2E de EmbyVerse Web con Playwright. Sirve web-player/ junto a test.m3u, big.m3u y clips locales:
//   BASE=http://localhost:8765 node tests/e2e/web-player.e2e.mjs
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
const require = createRequire(import.meta.url);
let pw; try { pw = require('playwright'); } catch { pw = require(process.env.PLAYWRIGHT_PATH); }
const BASE = process.env.BASE ?? 'http://localhost:8765';
const OUT = process.env.OUT ?? 'docs/screenshots/web';
mkdirSync(OUT, { recursive: true });
const results = [];
const step = async (n, f) => { const t0 = Date.now(); try { const extra = await f(); results.push(['✔', n, extra ?? '']); } catch (e) { results.push(['✘', n, e.message.split('\n')[0]]); } };
const browser = await pw.chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1366, height: 768 } });
page.setDefaultTimeout(15000);
const errors = []; page.on('pageerror', (e) => errors.push(e.message));
const entrar = () => page.getByRole('button', { name: 'Entrar', exact: true }).click();

await step('Pantalla de entrada (Xtream por defecto)', async () => {
  const t0 = Date.now(); await page.goto(BASE); await page.getByText('Iniciar sesión').waitFor();
  await page.locator('#f-user').waitFor(); await page.screenshot({ path: `${OUT}/01-entrada.png` });
  return `visible en ${Date.now() - t0} ms`;
});
await step('Xtream: servidor inexistente da un error comprensible', async () => {
  await page.locator('#f-loc').fill(`${BASE}/servidor-falso`);
  await page.locator('#f-user').fill('ana'); await page.locator('#f-pass').fill('secreta');
  await entrar();
  await page.locator('#f-err', { hasText: /No se encontró|Xtream/ }).waitFor();
});
await step('Xtream: pegar enlace get.php rellena los campos', async () => {
  await page.locator('#f-loc').fill('http://servidor.example.com:8080/get.php?username=luis&password=abc123&type=m3u_plus');
  if (await page.locator('#f-user').inputValue() !== 'luis' || await page.locator('#f-pass').inputValue() !== 'abc123') throw new Error('campos no extraídos');
});
await step('Lista M3U inexistente: error comprensible', async () => {
  await page.locator('.seg input[value=url]').check();
  await page.locator('#f-loc').fill(`${BASE}/no-existe.m3u`);
  await entrar();
  await page.getByText('No se encontró nada en esa dirección (HTTP 404).').waitFor();
});
await step('Entrar con lista M3U → Inicio premium', async () => {
  await page.locator('#f-loc').fill(`${BASE}/test.m3u`);
  await entrar();
  await page.getByText('3 canales, 1 películas, 1 series').waitFor();
  await page.locator('.row-head', { hasText: 'TV en vivo' }).waitFor();
  await page.waitForTimeout(300); await page.screenshot({ path: `${OUT}/02-inicio.png` });
});
await step('TV en vivo: categorías y reproducción con cambio de canal', async () => {
  await page.locator('.tab[data-tab=live]').click();
  await page.locator('.chip', { hasText: 'Deportes' }).click();
  if (await page.locator('.grid .card').count() !== 1) throw new Error('filtro');
  await page.locator('.chip', { hasText: 'Todas' }).click();
  await page.locator('.card', { hasText: 'Canal Prueba Uno' }).click();
  await page.waitForFunction(() => document.querySelector('#video').currentTime > 0.5);
  await page.screenshot({ path: `${OUT}/03-reproductor.png` });
  await page.keyboard.press('PageDown');
  await page.locator('#p-title', { hasText: 'Canal Prueba Dos' }).waitFor();
  await page.keyboard.press('Escape'); await page.locator('#player').waitFor({ state: 'hidden' });
});
await step('Navegación con flechas (mando)', async () => {
  await page.locator('.grid .card').first().focus();
  await page.keyboard.press('ArrowRight');
  const t = await page.evaluate(() => document.activeElement?.textContent || '');
  if (!t.includes('Canal Prueba Dos')) throw new Error('foco en: ' + t.slice(0, 40));
});
await step('Película: ficha, reproducir y Continuar viendo', async () => {
  await page.locator('.tab[data-tab=movies]').click();
  await page.locator('.card', { hasText: 'Clip de prueba' }).click();
  await page.locator('.detail h1', { hasText: 'Clip de prueba' }).waitFor();
  await page.screenshot({ path: `${OUT}/04-ficha-pelicula.png` });
  await page.getByRole('button', { name: /Reproducir/ }).click();
  await page.waitForFunction(() => document.querySelector('#video').currentTime > 1);
  await page.evaluate(() => { const v = document.querySelector('#video'); v.currentTime = 16; v.pause(); });
  await page.waitForTimeout(300); await page.keyboard.press('Escape');
  await page.getByRole('button', { name: /Continuar \(/ }).waitFor();
  await page.locator('.tab[data-tab=home]').click();
  await page.locator('.row-head', { hasText: 'Continuar viendo' }).waitFor();
});
await step('Serie: ficha con episodios', async () => {
  await page.locator('.tab[data-tab=series]').click();
  await page.locator('.card', { hasText: 'Serie Test' }).click();
  await page.locator('.ep', { hasText: 'Final' }).waitFor();
  await page.screenshot({ path: `${OUT}/05-ficha-serie.png` });
  await page.locator('.ep', { hasText: 'Inicio' }).click();
  await page.waitForFunction(() => document.querySelector('#video').currentTime > 0.5);
  await page.keyboard.press('Escape');
});
await step('Mi lista', async () => {
  await page.getByRole('button', { name: '♡ Mi lista' }).click();
  await page.locator('.tab[data-tab=favorites]').click();
  await page.locator('.card', { hasText: 'Serie Test' }).waitFor();
});
await step('Persistencia al recargar', async () => {
  await page.reload();
  await page.locator('.row-head', { hasText: 'TV en vivo' }).waitFor();
});
await step('Lista grande (20 000 canales, 15 000 películas, 3 000 episodios)', async () => {
  await page.locator('.tab[data-tab=sources]').click();
  await page.locator('.seg input[value=url]').check();
  await page.locator('#f-loc').fill(`${BASE}/big.m3u`);
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Añadir', exact: true }).click();
  await page.locator('.row-head', { hasText: 'TV en vivo' }).waitFor({ timeout: 60000 });
  const load = Date.now() - t0;
  const t1 = Date.now(); await page.locator('.tab[data-tab=movies]').click(); await page.locator('.grid .card').first().waitFor(); const tab = Date.now() - t1;
  const t2 = Date.now(); await page.locator('#search').fill('grande 1234'); await page.waitForFunction(() => document.querySelectorAll('.grid .card').length >= 1 && document.querySelectorAll('.grid .card').length < 20); const search = Date.now() - t2;
  await page.locator('#search').fill('');
  await page.screenshot({ path: `${OUT}/06-peliculas.png` });
  return `carga ${load} ms · abrir Películas ${tab} ms · búsqueda ${search} ms (incluye 250 ms de espera al teclear)`;
});
await step('Vista en teléfono', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('.tab[data-tab=home]').click();
  await page.waitForTimeout(300); await page.screenshot({ path: `${OUT}/07-movil.png` });
});
await browser.close();
for (const r of results) console.log(r.filter(Boolean).join('  '));
if (errors.length) console.log('Errores JS:', errors);
const failed = results.filter((r) => r[0] === '✘').length;
console.log(`\n${results.length - failed}/${results.length} pasos correctos`);
process.exit(failed ? 1 : 0);

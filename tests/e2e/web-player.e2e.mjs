// Prueba E2E de EmbyVerse Web con Playwright. Sirve web-player/ junto a test.m3u y clips locales:
//   BASE=http://localhost:8765 node tests/e2e/web-player.e2e.mjs
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
const require = createRequire(import.meta.url);
let pw; try { pw = require('playwright'); } catch { pw = require(process.env.PLAYWRIGHT_PATH); }
const BASE = process.env.BASE ?? 'http://localhost:8765';
const OUT = process.env.OUT ?? 'docs/screenshots/web';
mkdirSync(OUT, { recursive: true });
const results = [];
const step = async (n, f) => { try { await f(); results.push(['✔', n]); } catch (e) { results.push(['✘', n, e.message.split('\n')[0]]); } };
const browser = await pw.chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
const errors = []; page.on('pageerror', (e) => errors.push(e.message));

await step('Pantalla de entrada (Xtream por defecto)', async () => {
  await page.goto(BASE); await page.getByText('Entrar a EmbyVerse').waitFor();
  await page.locator('#f-user').waitFor(); await page.locator('#f-pass').waitFor();
  await page.screenshot({ path: `${OUT}/01-entrada.png` });
});
await step('Xtream: servidor inexistente da un error comprensible', async () => {
  await page.locator('#f-loc').fill(`${BASE}/servidor-falso`);
  await page.locator('#f-user').fill('ana'); await page.locator('#f-pass').fill('secreta');
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await page.locator('#f-err', { hasText: /No se encontró|Xtream/ }).waitFor();
  if (!(await page.getByText('Entrar a EmbyVerse').isVisible())) throw new Error('salió de la pantalla de entrada');
});
await step('Xtream: pegar enlace get.php rellena los campos', async () => {
  await page.locator('#f-loc').fill('http://servidor.example.com:8080/get.php?username=luis&password=abc123&type=m3u_plus');
  if (await page.locator('#f-user').inputValue() !== 'luis') throw new Error('usuario no extraído');
  if (await page.locator('#f-pass').inputValue() !== 'abc123') throw new Error('contraseña no extraída');
});
await step('Error comprensible con URL inexistente', async () => {
  await page.locator('.seg input[value=url]').check();
  await page.locator('#f-loc').fill(`${BASE}/no-existe.m3u`);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await page.getByText('No se encontró nada en esa dirección (HTTP 404).').waitFor();
});
await step('Entrar con lista M3U por URL', async () => {
  await page.locator('#f-name').fill('Lista de prueba');
  await page.locator('#f-loc').fill(`${BASE}/test.m3u`);
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await page.getByText('3 canales, 1 películas, 1 series').waitFor();
  await page.locator('.card').first().waitFor();
  await page.screenshot({ path: `${OUT}/02-tv-en-vivo.png` });
});
await step('Filtrar por categoría', async () => {
  await page.locator('.cat', { hasText: 'Deportes' }).click();
  if (await page.locator('.card').count() !== 1) throw new Error('filtro');
  await page.locator('.cat', { hasText: 'Todas' }).click();
});
await step('Reproducir canal y cambiar de canal', async () => {
  await page.locator('.card', { hasText: 'Canal Prueba Uno' }).click();
  await page.waitForFunction(() => { const v = document.querySelector('#video'); return v && v.currentTime > 0.5; }, null, { timeout: 15000 });
  await page.screenshot({ path: `${OUT}/03-reproductor.png` });
  await page.keyboard.press('PageDown');
  await page.locator('#p-title', { hasText: 'Canal Prueba Dos' }).waitFor();
  await page.keyboard.press('Escape');
  await page.locator('#player').waitFor({ state: 'hidden' });
});
await step('Navegación con flechas (mando)', async () => {
  await page.locator('.card').first().focus();
  await page.keyboard.press('ArrowRight');
  const t = await page.evaluate(() => document.activeElement?.textContent || '');
  if (!t.includes('Canal Prueba Dos')) throw new Error('foco en: ' + t.slice(0, 40));
});
await step('Película: progreso y Continuar viendo', async () => {
  await page.locator('.tab[data-tab=movies]').click();
  await page.locator('.card', { hasText: 'Clip de prueba' }).click();
  await page.waitForFunction(() => document.querySelector('#video').currentTime > 1, null, { timeout: 20000 });
  await page.evaluate(() => { const v = document.querySelector('#video'); v.currentTime = 16; v.pause(); });
  await page.waitForTimeout(300);
  await page.keyboard.press('Escape');
  await page.getByText('Continuar viendo').waitFor();
  await page.screenshot({ path: `${OUT}/04-continuar.png` });
});
await step('Serie con episodios', async () => {
  await page.locator('.tab[data-tab=series]').click();
  await page.locator('.card', { hasText: 'Serie Test' }).click();
  await page.locator('.ep', { hasText: 'Final' }).waitFor();
  await page.screenshot({ path: `${OUT}/05-serie.png` });
});
await step('Favoritos', async () => {
  await page.getByRole('button', { name: '♡ Añadir a favoritos' }).click();
  await page.locator('.tab[data-tab=favorites]').click();
  await page.locator('.card', { hasText: 'Serie Test' }).waitFor();
});
await step('Persistencia al recargar', async () => {
  await page.reload();
  await page.locator('.tab[data-tab=live]').click();
  await page.locator('.card', { hasText: 'Canal Prueba Uno' }).waitFor();
});
await step('Vista en teléfono', async () => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(200);
  await page.screenshot({ path: `${OUT}/06-movil.png` });
});
await browser.close();
for (const r of results) console.log(r.join('  '));
if (errors.length) console.log('Errores JS:', errors);
const failed = results.filter((r) => r[0] === '✘').length;
console.log(`\n${results.length - failed}/${results.length} pasos correctos`);
process.exit(failed ? 1 : 0);

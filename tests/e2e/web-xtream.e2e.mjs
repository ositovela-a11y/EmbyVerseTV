// Prueba con proveedor Xtream simulado (tests/e2e/xtream-mock.mjs): carga progresiva y canales que no responden.
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let pw; try { pw = require('playwright'); } catch { pw = require(process.env.PLAYWRIGHT_PATH); }
const BASE = process.env.BASE ?? 'http://localhost:8766';
const OUT = process.env.OUT ?? 'docs/screenshots/web';
const results = [];
const step = async (n, f) => { try { results.push(['✔', n, (await f()) ?? '']); } catch (e) { results.push(['✘', n, e.message.split('\n')[0]]); } };
const browser = await pw.chromium.launch();
const page = await browser.newPage({ viewport: { width: 1366, height: 768 } });
page.setDefaultTimeout(40000);
await step('Entrar con Xtream: los canales aparecen antes que las películas', async () => {
  await page.goto(BASE);
  await page.locator('#f-loc').fill(BASE); await page.locator('#f-user').fill('ana'); await page.locator('#f-pass').fill('clave');
  const t0 = Date.now();
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
  await page.locator('.row-head', { hasText: 'TV en vivo' }).waitFor();
  const tLive = Date.now() - t0;
  const moviesYet = await page.locator('.row-head', { hasText: 'Películas' }).count();
  await page.locator('.row-head', { hasText: 'Películas recién añadidas' }).waitFor();
  const tAll = Date.now() - t0;
  if (moviesYet) throw new Error('las películas no deberían estar aún');
  return `canales visibles en ${tLive} ms; películas (servidor lento, 4 s) en ${tAll} ms`;
});
await step('Canal que nunca responde: prueba otro formato y muestra un error (no gira para siempre)', async () => {
  await page.locator('.tab[data-tab=live]').click();
  const t0 = Date.now();
  await page.locator('.card', { hasText: 'CANAL QUE NO RESPONDE' }).click();
  await page.locator('#p-err').waitFor({ state: 'visible' });
  const msg = await page.locator('#p-err-msg').textContent();
  await page.screenshot({ path: `${OUT}/08-error-canal.png` });
  await page.locator('#p-err-close').click();
  return `error mostrado a los ${Math.round((Date.now() - t0) / 1000)} s: «${msg.slice(0, 70)}…»`;
});
await step('Canal caído (404): error inmediato', async () => {
  const t0 = Date.now();
  await page.locator('.card', { hasText: 'CANAL CAIDO' }).click();
  await page.locator('#p-err').waitFor({ state: 'visible' });
  await page.locator('#p-err-close').click();
  return `${Date.now() - t0} ms`;
});
await step('Película del proveedor Xtream: ficha y reproducción', async () => {
  await page.locator('.tab[data-tab=movies]').click();
  await page.locator('.card', { hasText: 'Película lenta' }).click();
  await page.getByRole('button', { name: /Reproducir/ }).click();
  await page.waitForFunction(() => document.querySelector('#video').currentTime > 0.5, null, { timeout: 20000 });
  await page.keyboard.press('Escape');
});
await browser.close();
for (const r of results) console.log(r.filter(Boolean).join('  '));
const failed = results.filter((r) => r[0] === '✘').length;
console.log(`\n${results.length - failed}/${results.length} pasos correctos`);
process.exit(failed ? 1 : 0);

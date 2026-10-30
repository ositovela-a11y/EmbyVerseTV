// EmbyVerse Web Local: proveedor SIN CORS (como muchos IPTV) reproducido a través del programa de la PC.
//   Requiere: el programa en LOCAL (por defecto http://localhost:8080) y un proveedor estricto en PROV.
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let pw; try { pw = require('playwright'); } catch { pw = require(process.env.PLAYWRIGHT_PATH); }
const LOCAL = process.env.LOCAL ?? 'http://localhost:8080';
const PROV = process.env.PROV ?? 'http://127.0.0.1:9100';
const PLAIN = process.env.PLAIN; // misma web servida sin el programa (opcional, para comparar)
const OUT = process.env.OUT ?? 'docs/screenshots/web';
const results = [];
const step = async (n, f) => { try { results.push(['✔', n, (await f()) ?? '']); } catch (e) { results.push(['✘', n, e.message.split('\n')[0]]); } };
const browser = await pw.chromium.launch();
const login = async (page, base) => {
  await page.goto(base);
  await page.locator('#f-loc').fill(PROV); await page.locator('#f-user').fill('ana'); await page.locator('#f-pass').fill('clave');
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
};
if (PLAIN) await step('Sin el programa: el proveedor sin CORS se bloquea (control)', async () => {
  const page = await browser.newPage(); page.setDefaultTimeout(20000);
  await login(page, PLAIN);
  const err = await (await page.locator('#f-err').waitFor({ timeout: 20000 }), page.locator('#f-err').textContent());
  await page.close();
  return `mensaje: «${err.trim().slice(0, 90)}…»`;
});
const page = await browser.newPage({ viewport: { width: 1366, height: 768 } });
page.setDefaultTimeout(30000);
await step('Con el programa: entra con Xtream http:// sin CORS', async () => {
  await login(page, LOCAL);
  await page.locator('.row-head', { hasText: 'TV en vivo' }).waitFor();
  return 'catálogo cargado a través de /proxy';
});
await step('Canal en vivo HLS (lista + segmentos reescritos) se reproduce', async () => {
  await page.locator('.tab[data-tab=live]').click();
  await page.locator('.card', { hasText: 'CANAL HLS' }).click();
  await page.waitForFunction(() => { const v = document.querySelector('video'); return v && v.currentTime > 2; }, null, { timeout: 30000 });
  const t = await page.evaluate(() => document.querySelector('video').currentTime.toFixed(1));
  await page.screenshot({ path: `${OUT}/10-local-canal.png` });
  await page.keyboard.press('Escape');
  return `video avanzando (${t} s)`;
});
await step('Película http:// sin CORS se reproduce', async () => {
  await page.locator('.tab[data-tab=movies]').click();
  await page.locator('.card', { hasText: 'Película de prueba' }).click();
  await page.getByRole('button', { name: /Reproducir|Ver ahora/ }).first().click();
  await page.waitForFunction(() => { const v = document.querySelector('video'); return v && v.currentTime > 2; }, null, { timeout: 30000 });
  return 'película reproduciéndose';
});
await browser.close();
for (const r of results) console.log(r.join('  '));
process.exit(results.some((r) => r[0] === '✘') ? 1 : 0);

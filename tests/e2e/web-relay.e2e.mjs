// EmbyVerse Relay: proveedor SIN CORS reproducido a través del intermediario en la nube.
//   Requiere la web en WEB, el relay en RELAY (node relay/node-dev.mjs) y el proveedor estricto en PROV.
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
let pw; try { pw = require('playwright'); } catch { pw = require(process.env.PLAYWRIGHT_PATH); }
const WEB = process.env.WEB ?? 'http://localhost:8767';
const RELAY = process.env.RELAY ?? 'http://localhost:8790';
const PROV = process.env.PROV ?? 'http://127.0.0.1:9100';
const OUT = process.env.OUT ?? 'docs/screenshots/web';
const results = [];
const step = async (n, f) => { try { results.push(['✔', n, (await f()) ?? '']); } catch (e) { results.push(['✘', n, e.message.split('\n')[0]]); } };
const browser = await pw.chromium.launch();
const login = async (page) => {
  await page.locator('#f-loc').fill(PROV); await page.locator('#f-user').fill('ana'); await page.locator('#f-pass').fill('clave');
  await page.getByRole('button', { name: 'Entrar', exact: true }).click();
};
const playing = (page) => page.waitForFunction(() => { const v = document.querySelector('video'); return v && v.currentTime > 2; }, null, { timeout: 30000 });

await step('Sin intermediario: el proveedor se bloquea y el aviso explica qué hacer', async () => {
  const page = await browser.newPage(); page.setDefaultTimeout(20000);
  await page.goto(WEB + '/?relay=');
  await login(page);
  await page.locator('#f-err').waitFor();
  const msg = await page.locator('#f-err').textContent();
  if (!/intermediario/.test(msg)) throw new Error('el mensaje no menciona el intermediario: ' + msg);
  await page.close();
  return `«${msg.trim().slice(0, 100)}…»`;
});

const ctx = await browser.newContext({ ...pw.devices['iPhone 13'], defaultBrowserType: undefined });
const page = await ctx.newPage(); page.setDefaultTimeout(30000);
await step('Configurar el intermediario desde la pantalla de entrada (vista iPhone)', async () => {
  await page.goto(WEB + '/?relay=');
  await page.locator('.relay-box summary').click();
  await page.locator('#relay-url').fill(RELAY);
  await Promise.all([page.waitForEvent('framenavigated'), page.locator('#relay-form button').first().click()]);
  await page.waitForLoadState('load');
  await page.locator('#f-loc').waitFor();
  const active = await page.evaluate(() => localStorage.getItem('ev.relay'));
  if (!active || !active.includes('8790')) throw new Error('no se guardó: ' + active);
  return 'guardado y página recargada';
});
await step('Entrar con Xtream http:// sin CORS a través del intermediario', async () => {
  await login(page);
  await page.locator('.row-head', { hasText: 'TV en vivo' }).waitFor();
  await page.screenshot({ path: `${OUT}/11-relay-inicio-iphone.png` });
  return 'catálogo cargado';
});
await step('Canal en vivo HLS por el intermediario', async () => {
  await page.locator('.tab[data-tab=live]').click();
  await page.locator('.card', { hasText: 'CANAL HLS' }).click();
  await playing(page);
  await page.screenshot({ path: `${OUT}/12-relay-canal-iphone.png` });
  await page.keyboard.press('Escape');
  return 'reproduciendo';
});
await step('Película por el intermediario (con saltos de posición)', async () => {
  await page.locator('.tab[data-tab=movies]').click();
  await page.locator('.card', { hasText: 'Película de prueba' }).click();
  await page.getByRole('button', { name: /Reproducir|Ver ahora/ }).first().click();
  await playing(page);
  await page.evaluate(() => { document.querySelector('video').currentTime = 12; });
  await page.waitForFunction(() => document.querySelector('video').currentTime > 13, null, { timeout: 20000 });
  return 'reproduce y salta';
});
await browser.close();
for (const r of results) console.log(r.join('  '));
process.exit(results.some((r) => r[0] === '✘') ? 1 : 0);

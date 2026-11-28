// Versión para TVs viejas: se fuerza con ?legacy=1 y se comprueba que arranca y llega a iniciar sesión sin errores.
const { chromium } = require('playwright');
const out = process.argv[2] || '/tmp/tvshots';
(async () => {
  const b = await chromium.launch({ channel: 'chrome' }).catch(() => chromium.launch());
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = []; p.on('pageerror', (e) => errs.push(e.message));
  await p.goto('http://localhost:8090/index.html?legacy=1');
  const ok = await p.waitForSelector('#tab-xtream, #f-user, .content, #device-code', { timeout: 30000 }).then(() => true).catch(() => false);
  const bundle = await p.evaluate(() => window.__EV_BUNDLE__);
  await p.waitForTimeout(800); await p.screenshot({ path: `${out}/20-tv-vieja-legacy.png` });
  console.log('Versión cargada:', bundle, '· pantalla:', ok ? 'bien' : 'NO');
  if (errs.length) console.log('Errores:', errs.slice(0, 5));
  await b.close();
  if (!ok || bundle !== 'app.legacy' || errs.length) { console.error('FALLA: la versión para TVs viejas no arrancó bien'); process.exit(1); }
  console.log('RESULTADO: la versión para TVs viejas arranca bien');
})();

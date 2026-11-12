// Prueba de la app de PC (Electron) con el proveedor simulado: entra con una lista M3U «difícil» y
// reproduce un canal HLS, uno sin extensión, uno que exige User-Agent y uno con Referer estilo Kodi,
// además de una película. Falla si algo no reproduce o hay errores de JavaScript.
const { _electron } = require('playwright');
const path = require('path');
const out = process.argv[2] || '/tmp/pcshots';
require('fs').mkdirSync(out, { recursive: true });
const fail = (m) => { console.error('FALLA: ' + m); process.exitCode = 1; };
(async () => {
  const app = await _electron.launch({ executablePath: require('electron'), args: ['--no-sandbox', path.join(__dirname, '..')] });
  const p = await app.firstWindow();
  const errs = []; p.on('pageerror', (e) => errs.push(e.message));
  const shot = (n) => p.screenshot({ path: `${out}/${n}.png` });
  const menu = (i) => p.evaluate((i) => document.querySelectorAll('.menu-item')[i].click(), i);
  await p.waitForSelector('#tab-m3u', { timeout: 30000 }); await p.waitForTimeout(500);
  const zoom = await app.evaluate(({ BrowserWindow }) => { const w = BrowserWindow.getAllWindows()[0]; return { z: w.webContents.getZoomFactor(), s: w.getContentSize() }; });
  console.log('Ventana', zoom.s.join('×'), 'zoom', zoom.z.toFixed(3)); if (Math.abs(zoom.z - Math.min(zoom.s[0] / 1920, zoom.s[1] / 1080)) > 0.02) fail('la pantalla no se ajustó a la ventana');
  await shot('01-entrar');
  await p.click('#tab-m3u'); await p.fill('#f-m3u', 'http://127.0.0.1:8800/lista.m3u'); await shot('02-lista');
  await p.click('#m3u-open');
  await p.waitForSelector('.content', { timeout: 30000 }).catch(() => fail('la lista no abrió'));
  await p.waitForTimeout(1500); await shot('03-inicio');
  await menu(1); await p.waitForSelector('.channel', { timeout: 20000 }).catch(() => fail('no aparecieron los canales'));
  const names = ['HLS normal', 'sin extensión', 'con User-Agent', 'con Referer (Kodi)'];
  for (let i = 0; i < names.length; i++) {
    await p.evaluate((i) => document.querySelectorAll('.channel')[i].click(), i);
    await p.waitForTimeout(500);
    // Un clic enfoca y muestra la ficha; si no abrió el video, OK lo abre.
    if (!(await p.evaluate(() => !!document.querySelector('.player')))) await p.keyboard.press('Enter');
    await p.waitForTimeout(7000);
    const v = await p.evaluate(() => { const v = document.getElementById('bgvideo'); return { t: v.currentTime, w: v.videoWidth, err: document.querySelector('.player-error h2')?.textContent ?? '' }; });
    console.log(`Canal ${names[i]}:`, JSON.stringify(v));
    if (!(v.t > 0)) fail(`el canal ${names[i]} no se reprodujo`);
    await shot(`04-canal-${i + 1}`);
    await p.keyboard.press('Escape'); await p.waitForTimeout(900);
  }
  await menu(2); await p.waitForSelector('.grid .card', { timeout: 20000 }).catch(() => fail('no aparecieron las películas'));
  await p.evaluate(() => document.querySelector('.grid .card').click()); await p.waitForSelector('.detail', { timeout: 15000 }).catch(() => fail('no abrió la ficha'));
  await p.waitForTimeout(800);
  await p.evaluate(() => [...document.querySelectorAll('.detail-btns .btn')].find((b) => b.textContent.includes('Ver ahora'))?.click());
  await p.waitForTimeout(7000);
  const mv = await p.evaluate(() => document.getElementById('bgvideo').currentTime);
  console.log('Película en el segundo', mv.toFixed(1)); if (!(mv > 0)) fail('la película no se reprodujo');
  await shot('05-pelicula');
  if (errs.length) { console.log('Errores de JavaScript:\n' + errs.join('\n')); fail('errores de JavaScript'); }
  await app.close();
  console.log(process.exitCode ? 'RESULTADO: FALLÓ' : 'RESULTADO: TODO BIEN');
})().catch((e) => { console.error(e); process.exit(1); });

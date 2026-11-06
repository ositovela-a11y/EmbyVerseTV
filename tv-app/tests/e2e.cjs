// Recorrido automático de la app de TV en Chromium a 1920×1080 con el proveedor simulado.
// Falla si: no inicia sesión, no carga el catálogo, no reproduce un canal o una película, o hay errores de JavaScript.
const { chromium } = require('playwright');
const out = process.argv[2] || '/tmp/tvshots';
require('fs').mkdirSync(out, { recursive: true });
const fail = (m) => { console.error('FALLA: ' + m); process.exitCode = 1; };
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1920, height: 1080 } });
  const errs = []; p.on('pageerror', (e) => errs.push(e.message));
  const key = async (k, n = 1) => { for (let i = 0; i < n; i++) { await p.keyboard.press(k); await p.waitForTimeout(180); } };
  const shot = (n) => p.screenshot({ path: `${out}/${n}.png` });
  const menu = (i) => p.evaluate((i) => document.querySelectorAll('.menu-item')[i].click(), i);
  const t0 = Date.now();
  await p.goto('http://localhost:8090/index.html');
  await p.waitForSelector('#f-user', { timeout: 20000 }); await shot('01-login');
  await p.fill('#f-user', 'user'); await p.fill('#f-pass', 'pass');
  await p.evaluate(() => [...document.querySelectorAll('.btn')].find((b) => b.textContent.includes('Entrar')).click());
  await p.waitForSelector('.hero', { timeout: 60000 }).catch(() => fail('el inicio no mostró el banner'));
  console.log(`Inicio con contenido en ${Date.now() - t0} ms (incluye la entrada de 8 s)`);
  await p.waitForTimeout(1500); await shot('02-inicio');
  const focusedInContent = await p.evaluate(() => !!document.querySelector('.content .focused'));
  if (!focusedInContent) fail('el foco no quedó en el contenido del inicio');
  // Navegación con flechas
  await key('ArrowDown', 2); await shot('03-inicio-abajo');
  // TV en vivo
  await menu(1); await p.waitForSelector('.channel', { timeout: 30000 }).catch(() => fail('no cargaron los canales'));
  await key('ArrowRight'); await key('ArrowDown', 2); await p.waitForTimeout(800); await shot('04-tv-en-vivo');
  await key('Enter'); await p.waitForTimeout(6000);
  const live = await p.evaluate(() => { const v = document.getElementById('bgvideo'); return { t: v.currentTime, w: v.videoWidth }; });
  console.log('Canal en vivo:', JSON.stringify(live)); if (!(live.t > 0)) fail('el canal en vivo no se reprodujo');
  await shot('05-reproductor-vivo');
  await key('ArrowDown'); await p.waitForTimeout(1500); await shot('06-cambio-de-canal');
  await key('Enter'); await p.waitForTimeout(700); await shot('07-opciones');
  await key('Escape'); await key('Escape'); await p.waitForTimeout(800);
  // Películas
  await menu(2); await p.waitForSelector('.grid .card', { timeout: 60000 }).catch(() => fail('no cargaron las películas'));
  await p.waitForTimeout(1500); await shot('08-peliculas');
  const imgs = await p.evaluate(() => { const l = [...document.querySelectorAll('.grid .card img')]; return { total: l.length, ok: l.filter((i) => i.complete && i.naturalWidth > 0).length }; });
  console.log('Pósters:', JSON.stringify(imgs)); if (imgs.ok < Math.min(6, imgs.total)) fail('los pósters no cargaron');
  await key('ArrowDown'); await key('Enter'); await p.waitForSelector('.detail', { timeout: 20000 }).catch(() => fail('no abrió la ficha'));
  await p.waitForTimeout(2500); await shot('09-ficha');
  await key('Enter'); await p.waitForTimeout(7000);
  const mv = await p.evaluate(() => document.getElementById('bgvideo').currentTime);
  console.log('Película reproduciendo en segundo', mv.toFixed(1)); if (!(mv > 0)) fail('la película no se reprodujo');
  await shot('10-reproductor-pelicula'); await key('Escape'); await p.waitForTimeout(800);
  // Series
  await menu(3); await p.waitForSelector('.grid .card', { timeout: 60000 }); await key('ArrowDown'); await key('Enter');
  await p.waitForSelector('.episodes .card', { timeout: 30000 }).catch(() => fail('no cargaron los episodios')); await p.waitForTimeout(1500); await shot('11-serie');
  // Búsqueda con falta de ortografía
  await menu(4); await p.fill('input', 'pelicla 12'); await p.waitForTimeout(6000); await shot('12-busqueda');
  const found = await p.evaluate(() => document.querySelectorAll('.search .card').length); console.log('Búsqueda «pelicla 12»:', found); if (!found) fail('la búsqueda no encontró nada');
  // Perfiles e infantil
  await menu(6); await p.waitForTimeout(800); await shot('13-ajustes');
  if (errs.length) { console.log('Errores de JavaScript:\n' + errs.slice(0, 10).join('\n')); fail('hubo errores de JavaScript'); }
  await b.close();
  console.log(process.exitCode ? 'RESULTADO: con fallas' : 'RESULTADO: todo bien');
})().catch((e) => { console.error(e); process.exit(1); });

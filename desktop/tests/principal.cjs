// App principal de PC («Everse»): nombre y logotipo propios aunque el diseño publicado diga «EmbyVerse»,
// 7 días de prueba, y opción de escribir otro servidor Xtream además del de la app.
const { _electron } = require('playwright');
const path = require('path');
const out = process.argv[2] || '/tmp/pcshots';
require('fs').mkdirSync(out, { recursive: true });
const fail = (m) => { console.error('FALLA: ' + m); process.exitCode = 1; };
(async () => {
  const app = await _electron.launch({ executablePath: require('electron'), args: ['--no-sandbox', path.join(__dirname, '..')] });
  const p = await app.firstWindow();
  const errs = []; p.on('pageerror', (e) => errs.push(e.message));
  const data = await app.evaluate(({ app }) => app.getPath('userData'));
  console.log('Carpeta de datos:', data); if (!/EmbyVerse$/.test(data)) fail('cambió la carpeta de datos (se perderían sesión y activación)');
  await p.waitForSelector('#device-code', { timeout: 30000 }).catch(() => fail('no apareció la activación'));
  await p.waitForTimeout(2500); // que llegue el diseño publicado
  const title = await p.title(); console.log('Título:', title); if (title !== 'Everse') fail('la ventana no se llama Everse');
  const trial = (await p.textContent('#act-trial').catch(() => '')) || '';
  console.log('Prueba:', trial.trim()); if (!/7 días/.test(trial)) fail('la prueba gratis no es de 7 días');
  if (/EmbyVerse/i.test(await p.evaluate(() => document.body.innerText))) fail('la pantalla dice EmbyVerse');
  await p.screenshot({ path: `${out}/10-everse-activacion.png` });
  await p.click('#act-trial');
  await p.waitForSelector('#tab-xtream', { timeout: 20000 }).catch(() => fail('no llegó a iniciar sesión'));
  const logo = await p.evaluate(() => { const i = document.querySelector('.login-logo'); return i ? { src: i.getAttribute('src'), w: i.naturalWidth, alt: i.alt } : null; });
  console.log('Logotipo:', JSON.stringify(logo)); if (!logo || logo.src !== 'logo.png' || !logo.w || logo.alt !== 'Everse') fail('el logotipo no es el de Everse');
  if (await p.$('#f-host')) fail('con servidor fijo no debería pedir la URL de entrada');
  await p.screenshot({ path: `${out}/11-everse-entrar.png` });
  await p.click('#other-server');
  await p.waitForSelector('#f-host', { timeout: 5000 }).then(() => console.log('«Otro servidor»: aparece el campo del servidor (bien)')).catch(() => fail('«Otro servidor» no muestra el campo'));
  await p.fill('#f-host', 'http://127.0.0.1:8800'); await p.fill('#f-user', 'user'); await p.fill('#f-pass', 'pass');
  await p.screenshot({ path: `${out}/12-everse-otro-servidor.png` });
  await p.evaluate(() => [...document.querySelectorAll('.login-form .btn')].find((b) => b.textContent.includes('Entrar'))?.click());
  await p.waitForSelector('.content', { timeout: 40000 }).then(() => console.log('Entró con otro servidor (bien)')).catch(() => fail('no entró con otro servidor'));
  await p.waitForTimeout(1500); await p.screenshot({ path: `${out}/13-everse-inicio.png` });
  if (errs.length) { console.log('Errores JS:', errs); fail('errores de JavaScript'); }
  await app.close();
})();

// Panel → Actualizaciones: muestra qué versión tienen los clientes y cuál espera, y al pulsar «Actualizar versión»
// guarda la aprobación (aprobadas.json) solo para esa app. GitHub y el sitio se simulan.
// Uso: node admin-web/test/updates.e2e.cjs <carpeta-capturas>   (sirve web-player/ en :8091)
const { chromium } = require('playwright');
const http = require('http'); const fs = require('fs'); const path = require('path');
const out = process.argv[2] || '/tmp/panel-shots'; fs.mkdirSync(out, { recursive: true });
const root = path.join(__dirname, '../../web-player');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.woff2': 'font/woff2', '.json': 'application/json', '.svg': 'image/svg+xml' };
const srv = http.createServer((q, s) => { const f = path.join(root, decodeURIComponent(q.url.split('?')[0]).replace(/\/$/, '/index.html'));
  fs.readFile(f, (e, d) => { if (e) { s.writeHead(404); return s.end(); } s.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream' }); s.end(d); }); }).listen(8091);
const status = { updated: '2026-11-27T08:00:00Z',
  android: [
    { asset: 'EmbyVerse.apk', slug: '', name: 'Tu app (EmbyVerse)', live: { code: 44, name: '1.15.17' }, nueva: { code: 45, name: '1.15.18' }, pendiente: true, aprobada: null },
    { asset: 'EmbyVerse-planetatv.apk', slug: 'planetatv', name: 'Planetatv', live: { code: 44, name: '1.15.17' }, nueva: { code: 45, name: '1.15.18' }, pendiente: true, aprobada: null },
    { asset: 'EmbyVerse-streampro.apk', slug: 'streampro', name: 'stream PRO', live: { code: 45, name: '1.15.18' }, nueva: { code: 45, name: '1.15.18' }, pendiente: false, aprobada: 45 },
  ],
  tv: { live: { name: '1.2.9' }, nueva: { name: '1.2.10' }, pendiente: true, aprobada: null },
  pc: { live: { name: '1.2.10' }, nueva: { name: '1.2.10' }, pendiente: false, aprobada: '1.2.10' } };
let saved = null; let fails = 0;
const fail = (m) => { console.log('✘', m); fails++; };
(async () => {
  const b = await chromium.launch();
  for (const [vp, name] of [[{ width: 1366, height: 900 }, 'pc'], [{ width: 390, height: 844 }, 'celular']]) {
    saved = null;
    const ctx = await b.newContext({ viewport: vp, deviceScaleFactor: name === 'pc' ? 1 : 2 });
    await ctx.addInitScript(() => localStorage.setItem('ev.studio.ghToken', 'token-de-prueba'));
    await ctx.route('https://api.github.com/**', async (r) => {
      const u = new URL(r.request().url()); const m = r.request().method();
      if (u.pathname.endsWith('/EmbyVerse-codigo')) return r.fulfill({ status: 404, body: '{}' });
      if (/\/repos\/[^/]+\/EmbyVerseTV$/.test(u.pathname)) return r.fulfill({ contentType: 'application/json', body: JSON.stringify({ permissions: { push: true }, private: false }) });
      if (u.pathname.endsWith('/contents/web-player/config/aprobadas.json')) {
        if (m === 'PUT') { saved = JSON.parse(Buffer.from(JSON.parse(r.request().postData()).content, 'base64').toString()); return r.fulfill({ contentType: 'application/json', body: '{"content":{"sha":"b"}}' }); }
        return saved ? r.fulfill({ contentType: 'application/json', body: JSON.stringify({ sha: 'a', content: Buffer.from(JSON.stringify(saved)).toString('base64') }) }) : r.fulfill({ status: 404, body: '{}' });
      }
      if (u.pathname.endsWith('/user')) return r.fulfill({ contentType: 'application/json', body: '{"login":"dueno"}' });
      return r.fulfill({ status: 404, contentType: 'application/json', body: '{"message":"Not Found"}' });
    });
    await ctx.route('https://ositovela-a11y.github.io/**', (r) => r.request().url().includes('versiones.json')
      ? r.fulfill({ contentType: 'application/json', body: JSON.stringify(status) }) : r.fulfill({ status: 404, body: '' }));
    const p = await ctx.newPage();
    p.on('dialog', (d) => d.accept());
    await p.goto('http://localhost:8091/studio/');
    const nav = p.getByRole('button', { name: 'Actualizaciones' }).or(p.getByText('Actualizaciones', { exact: true })).first();
    await nav.waitFor({ timeout: 20000 }).catch(() => {});
    if (!(await nav.count())) { fail(`${name}: no aparece «Actualizaciones» en el menú`); await p.screenshot({ path: `${out}/panel-${name}-error.png` }); await ctx.close(); continue; }
    await nav.click();
    await p.waitForSelector('#updates-panel', { timeout: 10000 }).catch(() => fail(`${name}: no abrió la sección`));
    await p.waitForTimeout(400);
    await p.screenshot({ path: `${out}/panel-actualizaciones-${name}.png`, fullPage: true });
    const btns = await p.$$('[data-approve]');
    if (btns.length !== 3) fail(`${name}: deberían ser 3 botones (tu app, planetatv y TV), hay ${btns.length}`); else console.log(`✔ ${name}: 3 versiones esperando (tu app, planetatv, TV); stream PRO y PC al día`);
    if (name === 'pc') {
      await p.click('[data-approve="EmbyVerse-planetatv.apk"]');
      await p.waitForSelector('.notice-ok', { timeout: 10000 }).catch(() => {});
      const ok = saved && saved.android && saved.android['EmbyVerse-planetatv.apk'] === 45 && !saved.android['EmbyVerse.apk'] && !saved.tv;
      if (!ok) fail('aprobar planetatv: se guardó ' + JSON.stringify(saved)); else console.log('✔ «Actualizar versión» de planetatv guarda SOLO esa app:', JSON.stringify(saved.android));
      await p.waitForTimeout(400);
      await p.screenshot({ path: `${out}/panel-actualizaciones-aprobada.png`, fullPage: true });
    }
    await ctx.close();
  }
  await b.close(); srv.close();
  console.log(fails ? `RESULTADO: ${fails} fallas` : 'RESULTADO: todo bien'); process.exit(fails ? 1 : 0);
})();

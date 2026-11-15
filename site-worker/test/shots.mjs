// Capturas del sitio con dominio propio (se suben a la publicación «ci-logs»).
import { chromium } from 'playwright';
const b = await chromium.launch();
const shots = [['https://eversetv.com/?v=planetatv', 'dominio-pagina-celular.png', { width: 390, height: 800 }],
  ['https://eversetv.com/', 'dominio-pagina-pc.png', { width: 1280, height: 800 }]];
for (const [url, file, vp] of shots) {
  const p = await (await b.newContext({ viewport: vp, deviceScaleFactor: 2 })).newPage();
  await p.goto(url, { waitUntil: 'networkidle' }); await p.waitForTimeout(800);
  await p.screenshot({ path: file }); console.log(file, p.url());
}
const p = await (await b.newContext({ acceptDownloads: true })).newPage();
const [dl] = await Promise.all([p.waitForEvent('download', { timeout: 30000 }), p.goto('https://eversetv.com/a/?v=planetatv').catch(() => {})]);
console.log('Downloader baja:', dl.url(), dl.suggestedFilename());
await b.close();

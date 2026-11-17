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
// Lo que ve WhatsApp/Facebook al compartir el enlace (HTML tal cual llega, sin ejecutar nada).
let bad = 0;
for (const v of ['planetatv', 'streampro', 'allinone', '']) {
  const h = await (await fetch('https://eversetv.com/' + (v ? '?v=' + v : ''), { headers: { 'user-agent': 'WhatsApp/2.23' } })).text();
  const pick = (re) => (h.match(re) || [])[1] || '—';
  const logo = pick(/<img class="logo"[^>]*src="([^"]{0,70})/);
  console.log(`${v || 'principal'}: título «${pick(/<title>([^<]*)/)}» · nombre «${pick(/<h1 id="name">([^<]*)/)}» · og:image ${pick(/og:image" content="([^"]*)/)} · logo ${logo}`);
  if (v && (logo.startsWith('data:image') || /EmbyVerse —/.test(h))) { bad++; console.log('::error::' + v + ' todavía muestra la marca EmbyVerse'); }
}
await b.close();
if (bad) process.exit(1);

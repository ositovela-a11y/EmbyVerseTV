// Prepara la app de PC (Windows) de una marca: la principal o la de un vendedor creado en el panel.
//   node brand.mjs --config <web-player/config/app.json | web-player/config/vendors/<slug>.json> [--slug <slug>]
//                  [--site https://…/EmbyVerseTV] [--config-url <url del diseño>] [--license-url <url>]
// Escribe en desktop/: package.json (nombre, identificador, nombre del instalador), icon.png (logotipo
// del vendedor o uno con sus iniciales) y app/index.html (diseño, servidor, edición de pago y
// activaciones de ESTA app, que se conservan aunque la app se actualice sola desde el sitio).
import { createRequire } from 'node:module';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const arg = (k, d = '') => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] ?? d : d; };

const slug = arg('slug');
if (slug && !/^[a-z][a-z0-9]{1,30}$/.test(slug)) { console.error('slug inválido:', slug); process.exit(1); }
const site = arg('site', 'https://ositovela-a11y.github.io/EmbyVerseTV').replace(/\/$/, '');
const cfgFile = resolve(arg('config', join(root, slug ? `web-player/config/vendors/${slug}.json` : 'web-player/config/app.json')));
const cfg = existsSync(cfgFile) ? JSON.parse(readFileSync(cfgFile, 'utf8')) : {};
const configUrl = arg('config-url', `${site}/config/${slug ? `vendors/${slug}.json` : 'app.json'}`);
// Todas las apps de PC (la principal y las de vendedores) se activan desde TU panel: una sola lista.
const licenseUrl = arg('license-url', `${site}/config/licenses.json`);
const name = (String(cfg.brand?.appName ?? '').trim() || (slug ? slug : 'EmbyVerse')).slice(0, 40);
const askHost = cfg.texts?.['provider.askHost'] === '1';

// ---------- index.html: datos de esta instalación ----------
const idx = join(here, 'app/index.html');
if (!existsSync(idx)) { console.error('Falta desktop/app (compila primero tv-app y cópiala).'); process.exit(1); }
const ev = { config: configUrl, edition: 'pc', license: licenseUrl, name, ...(askHost ? { host: '' } : {}) };
const esc = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
let html = readFileSync(idx, 'utf8').replace(/<script>window\.__EV__=[^<]*<\/script>\n?/, '');
html = html.replace(/<title>[^<]*<\/title>/, `<title>${esc(name)}</title>`)
  .replace('<head>', `<head>\n<script>window.__EV__=${JSON.stringify(ev).replace(/</g, '\\u003c')}</script>`);
writeFileSync(idx, html);

// ---------- package.json ----------
const pkgFile = join(here, 'package.json');
const pkg = JSON.parse(readFileSync(pkgFile, 'utf8'));
const file = slug ? slug : 'EmbyVerse'; // los archivos del vendedor no dicen «EmbyVerse»
pkg.productName = name;
pkg.description = `${name} para PC (Windows): TV en vivo, películas y series con tu cuenta Xtream o lista M3U.`;
pkg.author = slug ? name : 'EmbyVerse';
pkg.build.appId = slug ? `com.embyverse.pc.${slug}` : 'com.embyverse.pc';
pkg.build.productName = name;
pkg.build.nsis.shortcutName = name;
pkg.build.nsis.artifactName = `${file}-PC-Instalador.\${ext}`;
pkg.build.portable.artifactName = `${file}-PC-Portable.\${ext}`;
pkg.build.win.artifactName = `${file}-PC-\${version}-\${arch}.\${ext}`;
writeFileSync(pkgFile, JSON.stringify(pkg, null, 2) + '\n');

// ---------- Ícono ----------
const logoUrl = cfg.brand?.logo?.variant === 'asset' ? String(cfg.brand.logo.url ?? '') : '';
const m = /\/config\/assets\/([^/?#]+)$/.exec(logoUrl);
if (slug || m) {
  const require = createRequire(import.meta.url);
  let sharp;
  try { sharp = require('sharp'); } catch { sharp = require(join(process.env.SHARP_DIR || '/tmp/sharp', 'node_modules/sharp')); }
  const p = cfg.theme?.palette ?? {};
  const bg = p.background || '#03070F';
  let src = m ? join(root, 'web-player/config/assets', decodeURIComponent(m[1])) : null;
  let logo;
  if (src && existsSync(src)) logo = await sharp(src).resize(400, 400, { fit: 'contain', background: '#00000000' }).png().toBuffer();
  else {
    // Sin logotipo: iniciales con los colores del vendedor (nunca el logotipo de EmbyVerse).
    const words = name.split(/\s+/).filter(Boolean);
    const ini = (words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2)).toUpperCase().replace(/[<>&"']/g, '');
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">`
      + `<stop offset="0" stop-color="${p.primary || '#7BE52F'}"/><stop offset="1" stop-color="${p.accent || p.secondary || '#22D3EE'}"/></linearGradient></defs>`
      + `<circle cx="200" cy="200" r="190" fill="url(#g)"/><text x="200" y="200" dy="0.35em" text-anchor="middle" font-family="DejaVu Sans, Arial, sans-serif" font-weight="bold" font-size="${ini.length > 1 ? 160 : 210}" fill="#fff">${ini}</text></svg>`;
    logo = await sharp(Buffer.from(svg)).png().toBuffer();
  }
  await sharp({ create: { width: 512, height: 512, channels: 4, background: bg } }).composite([{ input: logo, gravity: 'center' }]).png().toFile(join(here, 'icon.png'));
  // Logotipo dentro de la app (inicio de sesión, activación): el del vendedor, nunca el de EmbyVerse.
  await sharp(logo).png().toFile(join(here, 'app/logo.png'));
}
console.log(`App de PC: «${name}»${slug ? ` (vendedor ${slug})` : ''} · ${askHost ? 'pide la URL del servidor' : 'servidor del diseño'} · activación ${licenseUrl} · instalador ${file}-PC-Instalador.exe`);

// Aplica a la APK el nombre y el logotipo publicados desde el Design Studio (web-player/config/app.json).
// Android no permite cambiar el nombre bajo el ícono ni el ícono sin instalar una APK nueva, así que
// GitHub Actions ejecuta esto antes de compilar.   Uso: node scripts/apply-brand.mjs
import { createRequire } from 'node:module';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
// Uso: node scripts/apply-brand.mjs [ruta del diseño]  (por defecto el de la app principal)
const cfgFile = process.argv[2] ? resolve(process.argv[2]) : join(root, 'web-player/config/app.json');
if (!existsSync(cfgFile)) { console.log('Sin diseño publicado: se mantiene la marca integrada.'); process.exit(0); }
const cfg = JSON.parse(readFileSync(cfgFile, 'utf8'));
const res = join(root, 'android-app/app/src/main/res');

// ---------- Nombre ----------
const name = String(cfg.brand?.appName ?? '').trim().slice(0, 30);
const xmlEsc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/'/g, "\\'").replace(/"/g, '\\"');
if (name) {
  for (const dir of ['values', 'values-en']) {
    const f = join(res, dir, 'strings.xml');
    if (!existsSync(f)) continue;
    const x = readFileSync(f, 'utf8').replace(/(<string name="app_name"[^>]*>)[^<]*(<\/string>)/, `$1${xmlEsc(name)}$2`);
    writeFileSync(f, x);
  }
  console.log('Nombre de la app:', name);
}

// ---------- App de vendedor: ninguna mención a EmbyVerse ----------
// Todos los textos de la app dicen el nombre del vendedor; solo quedan los créditos («credit_*»).
const isVendor = /[\\/]vendors[\\/]/.test(cfgFile);
if (isVendor && name) {
  for (const dir of ['values', 'values-en']) {
    const f = join(res, dir, 'strings.xml');
    if (!existsSync(f)) continue;
    const x = readFileSync(f, 'utf8').replace(/(<string name="([a-z0-9_]+)"[^>]*>)([^<]*)(<\/string>)/g,
      (all, open, key, text, close) => key.startsWith('credit_') ? all : open + text.replace(/EmbyVerse/g, xmlEsc(name)) + close);
    writeFileSync(f, x);
  }
  console.log('Textos sin «EmbyVerse» (salvo los créditos).');
}

// ---------- Logotipo ----------
const url = cfg.brand?.logo?.variant === 'asset' ? String(cfg.brand.logo.url ?? '') : '';
const m = /\/config\/assets\/([^/?#]+)$/.exec(url);
if (!m && !isVendor) { console.log('Logotipo integrado (sin cambios de ícono).'); process.exit(0); }

const require = createRequire(import.meta.url);
let sharp;
try { sharp = require('sharp'); } catch { sharp = require(join(process.env.SHARP_DIR || '/tmp/sharp', 'node_modules/sharp')); }

let src;
if (m) {
  src = join(root, 'web-player/config/assets', decodeURIComponent(m[1]));
  if (!existsSync(src)) { console.error('No se encuentra el logotipo publicado:', src); process.exit(1); }
} else {
  // Vendedor sin logotipo: se genera uno con sus iniciales y sus colores (nunca el de EmbyVerse).
  const p = cfg.theme?.palette ?? {};
  const words = (name || 'App').split(/\s+/).filter(Boolean);
  const initials = (words.length > 1 ? words[0][0] + words[1][0] : words[0].slice(0, 2)).toUpperCase().replace(/[<>&"']/g, '');
  const c1 = /^#[0-9a-f]{6}$/i.test(p.primary ?? '') ? p.primary : '#7BE52F';
  const c2 = /^#[0-9a-f]{6}$/i.test(p.accent ?? '') ? p.accent : '#22D3EE';
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c1}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs>`
    + `<circle cx="512" cy="512" r="470" fill="url(#g)"/><text x="512" y="512" dy="0.35em" text-anchor="middle" font-family="DejaVu Sans, Arial, sans-serif" font-weight="bold" font-size="${initials.length > 1 ? 400 : 520}" fill="#ffffff">${initials}</text></svg>`;
  src = '/tmp/vendor-logo.png';
  await sharp(Buffer.from(svg)).png().toFile(src);
  console.log('Vendedor sin logotipo: se generó uno con las iniciales', initials);
}
const out = (p) => { mkdirSync(dirname(p), { recursive: true }); return p; };
const bg = cfg.theme?.palette?.background?.length === 7 ? cfg.theme.palette.background : '#000000';

// Ícono: el logotipo centrado en un cuadrado (sin recortar), con el fondo del diseño.
const square = async (size, pad) => {
  const inner = await sharp(src).resize(Math.round(size * (1 - 2 * pad)), Math.round(size * (1 - 2 * pad)), { fit: 'contain', background: '#00000000' }).png().toBuffer();
  return sharp({ create: { width: size, height: size, channels: 4, background: bg } }).composite([{ input: inner, gravity: 'center' }]).png().toBuffer();
};
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const roundMask = (s) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}"><circle cx="${s / 2}" cy="${s / 2}" r="${s / 2}"/></svg>`);
for (const [d, k] of Object.entries(densities)) {
  const s = Math.round(48 * k);
  const sq = await square(s, 0.06);
  await sharp(sq).webp({ quality: 92 }).toFile(out(join(res, `mipmap-${d}/ic_launcher.webp`)));
  await sharp(sq).composite([{ input: roundMask(s), blend: 'dest-in' }]).webp({ quality: 92 }).toFile(out(join(res, `mipmap-${d}/ic_launcher_round.webp`)));
}
// Fondo del ícono adaptativo = fondo del diseño
const colors = join(res, 'values/colors.xml');
if (existsSync(colors)) writeFileSync(colors, readFileSync(colors, 'utf8').replace(/(<color name="ic_launcher_background">)[^<]*(<\/color>)/, `$1${bg}$2`));
// Ícono adaptativo (108 dp, zona segura 66 dp): contenido a 288 px dentro de 432 px.
const fg = await sharp(src).resize(288, 288, { fit: 'contain', background: '#00000000' }).png().toBuffer();
await sharp({ create: { width: 432, height: 432, channels: 4, background: '#00000000' } }).composite([{ input: fg, top: 72, left: 72 }])
  .webp({ quality: 92 }).toFile(out(join(res, 'drawable-nodpi/ic_launcher_foreground.webp')));
// Logotipo de arranque y cabecera
await sharp(src).resize(768, 768, { fit: 'inside' }).webp({ quality: 90 }).toFile(out(join(res, 'drawable-nodpi/embyverse_logo.webp')));
await sharp(await square(288, 0)).webp({ quality: 92 }).toFile(out(join(res, 'drawable-nodpi/embyverse_planet.webp')));
// Banner de Android TV (640x360)
const bl = await sharp(src).resize(600, 320, { fit: 'inside' }).png().toBuffer();
await sharp({ create: { width: 640, height: 360, channels: 4, background: bg } }).composite([{ input: bl, gravity: 'center' }])
  .webp({ quality: 92 }).toFile(out(join(res, 'drawable-xhdpi/tv_banner.webp')));
console.log('Logotipo e íconos generados desde', m ? m[1] : 'iniciales');

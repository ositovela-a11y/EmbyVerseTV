#!/usr/bin/env node
// Genera todas las variantes de marca a partir del logotipo ORIGINAL de EmbyVerse.
// No se dibuja ni se inventa ningún logotipo: solo se recorta, escala y rellena con negro.
//
// Uso:  node scripts/generate-brand-assets.mjs [ruta/al/logo-original.(jpg|png)]
// Requiere: sharp (npm i -D sharp)
import { createRequire } from 'node:module';
import { mkdirSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
let sharp;
try { sharp = require('sharp'); } catch {
  // Entorno de desarrollo con sharp global
  sharp = require(process.env.SHARP_PATH || '/home/claude/.npm-global/lib/node_modules/sharp');
}

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = resolve(process.argv[2] || join(root, 'assets/brand/embyverse-logo-original.png'));
if (!existsSync(src)) { console.error('No se encuentra el logo original:', src); process.exit(1); }

const out = (p) => { const f = join(root, p); mkdirSync(dirname(f), { recursive: true }); return f; };

const meta = await sharp(src).metadata();
// El original adjunto traía una franja gris de 36 px (captura de pantalla). Si la imagen ya
// está recortada (1320x1219) no se toca.
const topTrim = meta.height === 1255 ? 36 : 0;
const logo = await sharp(src).extract({ left: 0, top: topTrim, width: meta.width, height: meta.height - topTrim }).png().toBuffer();
const lm = await sharp(logo).metadata();

// Variante "planeta" (sin texto) recortada del propio logo, con fundido inferior a negro.
const sx = (v) => Math.round(v * lm.width / 1320), sy = (v) => Math.round(v * lm.height / 1219);
const planetStrip = await sharp(logo).extract({ left: sx(160), top: sy(164), width: sx(1000), height: sy(545) }).png().toBuffer();
const ps = await sharp(planetStrip).metadata();
const fade = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${ps.width}" height="${ps.height}">
  <defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1">
    <stop offset="0.80" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="1"/>
  </linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/></svg>`);
const planetFaded = await sharp(planetStrip).composite([{ input: fade }]).png().toBuffer();
const side = Math.max(ps.width, ps.height);
const planetSquare = await sharp({ create: { width: side, height: side, channels: 4, background: '#000000ff' } })
  .composite([{ input: planetFaded, top: Math.round((side - ps.height) / 2), left: Math.round((side - ps.width) / 2) }])
  .png().toBuffer();

// ---------- assets/brand ----------
await sharp(logo).resize(1024).webp({ quality: 90 }).toFile(out('assets/brand/embyverse-logo.webp'));
await sharp(planetSquare).resize(512).png().toFile(out('assets/brand/embyverse-icon-planet.png'));

// ---------- Android ----------
const res = 'android-app/app/src/main/res';
const densities = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const roundMask = (s) => Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}"><circle cx="${s / 2}" cy="${s / 2}" r="${s / 2}"/></svg>`);
for (const [d, k] of Object.entries(densities)) {
  const s = Math.round(48 * k);
  await sharp(planetSquare).resize(s).webp({ quality: 92 }).toFile(out(`${res}/mipmap-${d}/ic_launcher.webp`));
  await sharp(planetSquare).resize(s).composite([{ input: roundMask(s), blend: 'dest-in' }]).webp({ quality: 92 })
    .toFile(out(`${res}/mipmap-${d}/ic_launcher_round.webp`));
}
// Icono adaptativo: 108dp con zona segura de 66dp → 432 px (xxxhdpi), contenido a 288 px.
const fgInner = await sharp(planetSquare).resize(288).png().toBuffer();
await sharp({ create: { width: 432, height: 432, channels: 4, background: '#00000000' } })
  .composite([{ input: fgInner, top: 72, left: 72 }]).webp({ quality: 92 })
  .toFile(out(`${res}/drawable-nodpi/ic_launcher_foreground.webp`));
// Logo completo para splash/cabecera
await sharp(logo).resize(768).webp({ quality: 90 }).toFile(out(`${res}/drawable-nodpi/embyverse_logo.webp`));
await sharp(planetSquare).resize(288).webp({ quality: 92 }).toFile(out(`${res}/drawable-nodpi/embyverse_planet.webp`));
// Banner de Android TV (320x180 dp → xhdpi 640x360)
const bannerLogo = await sharp(logo).resize({ height: 340 }).png().toBuffer();
const bl = await sharp(bannerLogo).metadata();
await sharp({ create: { width: 640, height: 360, channels: 4, background: '#000000ff' } })
  .composite([{ input: bannerLogo, top: 10, left: Math.round((640 - bl.width) / 2) }])
  .webp({ quality: 92 }).toFile(out(`${res}/drawable-xhdpi/tv_banner.webp`));

// ---------- Panel web / backend ----------
await sharp(logo).resize(512).webp({ quality: 90 }).toFile(out('admin-web/public/brand/embyverse-logo.webp'));
await sharp(planetSquare).resize(192).png().toFile(out('admin-web/public/brand/embyverse-icon.png'));
await sharp(planetSquare).resize(64).png().toFile(out('admin-web/public/favicon.png'));

console.log('Variantes de marca generadas desde', src);

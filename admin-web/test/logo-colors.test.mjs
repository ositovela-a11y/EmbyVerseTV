// Prueba los colores tomados del logotipo (sin navegador): logos sintéticos y el logo real de Planetatv.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { existsSync, readFileSync } from 'node:fs';
const out = await esbuild.build({ entryPoints: [new URL('../src/logo-colors.ts', import.meta.url).pathname], bundle: true, format: 'esm', write: false, platform: 'node' });
const m = await import('data:text/javascript;base64,' + Buffer.from(out.outputFiles[0].text).toString('base64'));
const px = (parts) => { const a = []; for (const [rgb, n] of parts) for (let i = 0; i < n; i++) a.push(...rgb, 255); return a; };
const hue = (hex) => m.hexToHsl(hex)[0]; const l = (hex) => m.hexToHsl(hex)[2];

const orange = m.paletteFromPixels(px([[[255, 122, 0], 300], [[10, 10, 10], 600], [[255, 255, 255], 100]]));
assert.ok(hue(orange.primary) > 20 && hue(orange.primary) < 40, 'naranja: ' + orange.primary);
assert.ok(l(orange.background) < 0.08, 'fondo casi negro: ' + orange.background);
console.log('✔ logo naranja con negro →', orange);
const p = m.fullPalette(orange);
assert.equal(p.buttonBackground, orange.primary); assert.ok(l(p.surface) > l(p.background) && l(p.surfaceVariant) > l(p.surface));
console.log('✔ paleta completa (fondo < tarjetas < tarjeta resaltada)');

const planeta = m.paletteFromPixels(px([[[80, 230, 40], 400], [[150, 70, 230], 200], [[255, 200, 40], 80], [[5, 5, 30], 500]]));
assert.ok(hue(planeta.primary) > 90 && hue(planeta.primary) < 130, 'verde: ' + planeta.primary);
assert.ok(hue(planeta.accent) > 250 && hue(planeta.accent) < 300, 'acento morado: ' + planeta.accent);
assert.ok(hue(planeta.background) > 200 && hue(planeta.background) < 260, 'fondo azul marino: ' + planeta.background);
console.log('✔ logo verde/morado sobre azul marino →', planeta);

assert.equal(m.paletteFromPixels(px([[[128, 128, 128], 500], [[0, 0, 0], 500]])), null);
console.log('✔ logo en grises: se queda el tema');
const themes = [{ id: 'naranja', palette: { primary: '#FF7A00' } }, { id: 'neon', palette: { primary: '#39FF14' } }, { id: 'purpura', palette: { primary: '#A855F7' } }];
assert.equal(m.closestTheme(themes, orange.primary).id, 'naranja'); assert.equal(m.closestTheme(themes, planeta.primary).id, 'neon');
console.log('✔ tema más parecido');

// Logo real de Planetatv (si está y hay sharp).
const cfg = JSON.parse(readFileSync(new URL('../../web-player/config/vendors/planetatv.json', import.meta.url), 'utf8'));
const f = /\/config\/assets\/([^/?#]+)$/.exec(cfg.brand?.logo?.url ?? '')?.[1];
const file = f && new URL(`../../web-player/config/assets/${decodeURIComponent(f)}`, import.meta.url).pathname;
let sharp; try { sharp = createRequire(import.meta.url)(process.env.SHARP_DIR ? process.env.SHARP_DIR + '/node_modules/sharp' : 'sharp'); } catch { /* */ }
if (file && existsSync(file) && sharp) {
  const raw = await sharp(file).resize(96, 96, { fit: 'fill' }).ensureAlpha().raw().toBuffer();
  console.log('✔ logo real de Planetatv →', m.paletteFromPixels(raw));
} else console.log('· logo real no probado (falta sharp o el archivo)');

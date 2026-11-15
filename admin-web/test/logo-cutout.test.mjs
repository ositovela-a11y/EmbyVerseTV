// Prueba quitar el fondo del logotipo (sin navegador): fondo blanco con ruido de JPG, fondo negro,
// logo ya transparente y foto con fondo de muchos colores (no se toca).
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
const out = await esbuild.build({ entryPoints: [new URL('../src/logo-cutout.ts', import.meta.url).pathname], bundle: true, format: 'esm', write: false, platform: 'neutral' });
const m = await import('data:text/javascript;base64,' + Buffer.from(out.outputFiles[0].text).toString('base64'));
const W = 120, H = 100;
function img(bg, noise = 0, draw = () => null) {
  const d = new Uint8ClampedArray(W * H * 4);
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const i = (y * W + x) * 4; const c = draw(x, y) ?? (typeof bg === 'function' ? bg(x, y) : bg);
    const r = () => (noise ? Math.round((Math.random() - 0.5) * noise) : 0);
    d[i] = c[0] + r(); d[i + 1] = c[1] + r(); d[i + 2] = c[2] + r(); d[i + 3] = c[3] ?? 255;
  }
  return d;
}
const ring = (x, y) => { const r = Math.hypot(x - 60, y - 50); return r < 30 && r > 18 ? [255, 120, 0] : r <= 18 ? [255, 255, 255] : null; }; // aro naranja con centro blanco
const alpha = (d, x, y) => d[(y * W + x) * 4 + 3];

let d = img([250, 250, 250], 10, ring); let r = m.cutoutPixels(d, W, H);
assert.ok(r.removed); assert.equal(alpha(d, 2, 2), 0); assert.equal(alpha(d, 60, 25), 255);
assert.equal(alpha(d, 60, 50), 255, 'el centro blanco del logo se respeta (no toca la orilla)');
assert.ok(r.box.w >= 58 && r.box.w <= 64 && r.box.x >= 28, 'recortado al logo: ' + JSON.stringify(r.box));
console.log('✔ fondo blanco (con ruido de JPG) quitado, el blanco dentro del logo se queda');

d = img([0, 0, 0], 6, ring); r = m.cutoutPixels(d, W, H);
assert.ok(r.removed && alpha(d, 1, 1) === 0 && alpha(d, 60, 22) === 255); console.log('✔ fondo negro quitado');

d = img([0, 0, 0, 0], 0, (x, y) => { const c = ring(x, y); return c ? [...c, 255] : null; }); r = m.cutoutPixels(d, W, H);
assert.ok(!r.removed && /ya no tenía fondo/.test(r.note) && r.box.w < 70); console.log('✔ PNG sin fondo: solo se recorta');

d = img((x, y) => [(x * 37) % 255, (y * 53) % 255, ((x + y) * 29) % 255], 0, ring); r = m.cutoutPixels(d, W, H);
assert.ok(!r.removed && /muchos colores/.test(r.note)); console.log('✔ foto con fondo de muchos colores: no se toca');

d = img([20, 30, 160], 4, (x, y) => (x > 50 && x < 54 && y > 45 && y < 49 ? [255, 255, 255] : null)); r = m.cutoutPixels(d, W, H);
assert.ok(!r.removed && r.box === null); console.log('✔ imagen sin logo: se usa la original');

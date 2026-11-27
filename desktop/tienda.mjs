// Imágenes del paquete de Microsoft Store (MSIX) a partir del ícono de la app: las pide Windows para el menú
// Inicio y la tienda. Se escriben en desktop/build/appx/. Uso: node desktop/tienda.mjs  (necesita sharp)
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
const here = dirname(fileURLToPath(import.meta.url));
const sharp = createRequire(import.meta.url)('sharp');
const out = join(here, 'build/appx'); mkdirSync(out, { recursive: true });
const bg = { r: 3, g: 7, b: 15, alpha: 1 };
const tiles = [['StoreLogo.png', 50, 50], ['Square44x44Logo.png', 44, 44], ['Square150x150Logo.png', 150, 150],
  ['Wide310x150Logo.png', 310, 150], ['LargeTile.png', 310, 310], ['SmallTile.png', 71, 71]];
for (const [name, w, h] of tiles) {
  const side = Math.round(Math.min(w, h) * (w === h && w <= 50 ? 1 : 0.8));
  const icon = await sharp(join(here, 'icon.png')).resize(side, side, { fit: 'contain', background: '#00000000' }).png().toBuffer();
  await sharp({ create: { width: w, height: h, channels: 4, background: w <= 50 ? '#00000000' : bg } })
    .composite([{ input: icon, gravity: 'center' }]).png().toFile(join(out, name));
  console.log('✔', name, `${w}x${h}`);
}

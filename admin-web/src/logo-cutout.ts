// Quita el fondo del logotipo (foto o captura con fondo liso: blanco, negro o de un color) y lo recorta
// a su tamaño, para que en la app solo se vea el logo. Funciona en el navegador, sin subir la imagen a
// ningún servicio. Si el fondo tiene muchos colores (foto de una pared, degradados fuertes), no se toca.

export interface Cutout { removed: boolean; note: string }

const dist = (d: ArrayLike<number>, i: number, c: [number, number, number]) =>
  Math.sqrt((d[i]! - c[0]) ** 2 + (d[i + 1]! - c[1]) ** 2 + (d[i + 2]! - c[2]) ** 2);

/**
 * Trabaja sobre los píxeles RGBA (se modifican en el mismo arreglo). Devuelve si quitó el fondo y el
 * recuadro donde quedó el logo.
 */
export function cutoutPixels(d: Uint8ClampedArray | number[], w: number, h: number): Cutout & { box: { x: number; y: number; w: number; h: number } | null } {
  const n = w * h;
  const border: number[] = [];
  for (let x = 0; x < w; x++) { border.push(x, (h - 1) * w + x); }
  for (let y = 1; y < h - 1; y++) { border.push(y * w, y * w + w - 1); }
  const transparent = border.filter((p) => d[p * 4 + 3]! < 24).length;
  let removed = false; let note = '';
  if (transparent / border.length > 0.6) note = 'El logotipo ya no tenía fondo.';
  else {
    // Color del fondo = el más común en la orilla (por grupos de color).
    const groups = new Map<string, { n: number; r: number; g: number; b: number }>();
    for (const p of border) {
      const i = p * 4; if (d[i + 3]! < 24) continue;
      const k = `${d[i]! >> 5},${d[i + 1]! >> 5},${d[i + 2]! >> 5}`;
      const gr = groups.get(k) ?? { n: 0, r: 0, g: 0, b: 0 }; gr.n++; gr.r += d[i]!; gr.g += d[i + 1]!; gr.b += d[i + 2]!; groups.set(k, gr);
    }
    const top = [...groups.values()].sort((a, b) => b.n - a.n)[0];
    const bg: [number, number, number] | null = top ? [top.r / top.n, top.g / top.n, top.b / top.n] : null;
    const TOL = 42, SOFT = 78;
    const share = bg ? border.filter((p) => d[p * 4 + 3]! >= 24 && dist(d, p * 4, bg) < SOFT).length / border.length : 0;
    if (!bg || share < 0.55) note = 'El fondo tiene muchos colores y no se pudo quitar solo. Si quieres, súbelo como PNG sin fondo.';
    else {
      // Relleno desde las orillas: solo se borra el fondo que toca el borde (los huecos del logo con
      // el mismo color, como el centro de una «O» blanca, se respetan si no tocan el borde).
      const seen = new Uint8Array(n); const stack: number[] = [];
      for (const p of border) if (dist(d, p * 4, bg) < SOFT) { seen[p] = 1; stack.push(p); }
      while (stack.length) {
        const p = stack.pop()!; const i = p * 4; const dd = dist(d, i, bg);
        if (dd < TOL) d[i + 3] = 0;
        else { d[i + 3] = Math.min(d[i + 3]!, Math.round(255 * (dd - TOL) / (SOFT - TOL))); continue; } // orilla suave: no sigue
        const x = p % w, y = (p - x) / w;
        for (const q of [x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1])
          if (q >= 0 && !seen[q] && dist(d, q * 4, bg) < SOFT) { seen[q] = 1; stack.push(q); }
      }
      removed = true; note = 'Fondo quitado: solo queda el logotipo.';
    }
  }
  // Recorte a lo que se ve del logo.
  let x0 = w, y0 = h, x1 = -1, y1 = -1;
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) if (d[(y * w + x) * 4 + 3]! > 16) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
  if (x1 < 0) return { removed: false, note: 'La imagen quedó vacía; se usa la original.', box: null };
  if (removed && (x1 - x0 + 1) * (y1 - y0 + 1) < n * 0.02) return { removed: false, note: 'No se encontró el logotipo en la imagen; se usa la original.', box: null };
  return { removed, note, box: { x: x0, y: y0, w: x1 - x0 + 1, h: y1 - y0 + 1 } };
}

/** En el navegador: devuelve el logotipo sin fondo (PNG) o el mismo archivo si no se pudo. */
export async function removeLogoBackground(file: File): Promise<Cutout & { file: File }> {
  const bmp = await createImageBitmap(file);
  const scale = Math.min(1, 1024 / Math.max(bmp.width, bmp.height));
  const w = Math.max(1, Math.round(bmp.width * scale)), h = Math.max(1, Math.round(bmp.height * scale));
  const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
  const ctx = cv.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(bmp, 0, 0, w, h);
  const img = ctx.getImageData(0, 0, w, h);
  const r = cutoutPixels(img.data, w, h);
  if (!r.box || (!r.removed && r.box.w === w && r.box.h === h)) return { file, removed: false, note: r.note };
  ctx.putImageData(img, 0, 0);
  const pad = Math.round(Math.max(r.box.w, r.box.h) * 0.04);
  const out = document.createElement('canvas'); out.width = r.box.w + pad * 2; out.height = r.box.h + pad * 2;
  out.getContext('2d')!.drawImage(cv, r.box.x, r.box.y, r.box.w, r.box.h, pad, pad, r.box.w, r.box.h);
  const blob = await new Promise<Blob | null>((ok) => out.toBlob(ok, 'image/png'));
  if (!blob) return { file, removed: false, note: 'No se pudo preparar el logotipo; se usa la original.' };
  return { file: new File([blob], file.name.replace(/\.[^.]+$/, '') + '.png', { type: 'image/png' }), removed: r.removed, note: r.note || 'Logotipo recortado.' };
}

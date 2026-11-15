// Colores de la app tomados del logotipo del vendedor: si su logo es naranja con negro, la app queda
// naranja con negro (botones, foco, fondo, tarjetas y degradado), y se elige el tema más parecido.

export interface BrandColors { primary: string; accent: string; background: string }
type Hsl = [number, number, number]; // h 0–360, s 0–1, l 0–1

const clamp = (x: number, a: number, b: number) => Math.min(b, Math.max(a, x));
export function rgbToHsl(r: number, g: number, b: number): Hsl {
  r /= 255; g /= 255; b /= 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
  if (mx === mn) return [0, 0, l];
  const d = mx - mn, s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
  const h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h * 60, s, l];
}
export function hslToHex([h, s, l]: Hsl): string {
  const k = (n: number) => (n + h / 30) % 12, a = s * Math.min(l, 1 - l);
  const f = (n: number) => Math.round(255 * (l - a * Math.max(-1, Math.min(k(n) - 3, 9 - k(n), 1))));
  return '#' + [f(0), f(8), f(4)].map((x) => x.toString(16).padStart(2, '0')).join('').toUpperCase();
}
export function hexToHsl(hex: string): Hsl {
  const n = parseInt(hex.replace('#', ''), 16);
  return rgbToHsl(n >> 16, (n >> 8) & 255, n & 255);
}
const hueDist = (a: number, b: number) => { const d = Math.abs(a - b) % 360; return d > 180 ? 360 - d : d; };
const lum = (hex: string) => { const n = parseInt(hex.slice(1), 16); return (0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255)) / 255; };

/** Colores principales a partir de los píxeles RGBA del logotipo (sin fondo transparente). */
export function paletteFromPixels(data: ArrayLike<number>): BrandColors | null {
  const buckets = new Map<string, { n: number; h: number; s: number; l: number }>();
  let dark = 0, darkH = 0, darkS = 0, total = 0;
  const side = Math.round(Math.sqrt(data.length / 4)) || 1;
  for (let i = 0; i + 3 < data.length; i += 4) {
    if (data[i + 3]! < 200) continue;
    const [h, s, l] = rgbToHsl(data[i]!, data[i + 1]!, data[i + 2]!);
    total++;
    if (l < 0.16) { dark++; if (s > 0.25) { darkH += h; darkS++; } continue; }
    if (s < 0.3 || l > 0.85) continue; // grises y blancos no definen la marca
    // Pesan más los colores vivos y los del centro (la figura del logo), menos los del fondo y orillas.
    const p = i / 4, x = (p % side) / side - 0.5, y = Math.floor(p / side) / side - 0.5;
    const center = 1.6 - Math.min(1, Math.hypot(x, y) / 0.7) * 1.2;
    const r0 = data[i]!, g0 = data[i + 1]!, b0 = data[i + 2]!;
    const bright = (0.299 * r0 + 0.587 * g0 + 0.114 * b0) / 255; // los colores que «brillan» representan mejor la marca
    const vivid = s * clamp(1 - Math.abs(l - 0.55) * 1.6, 0.15, 1) * (0.35 + bright);
    const wgt = center * vivid;
    const key = `${Math.round(h / 20) % 18}`;
    const b = buckets.get(key) ?? { n: 0, h: 0, s: 0, l: 0 };
    b.n += wgt; b.h += h * wgt; b.s += s * wgt; b.l += l * wgt; buckets.set(key, b);
  }
  if (!total) return null;
  const cands = [...buckets.values()].map((b) => ({ n: b.n, h: b.h / b.n, s: b.s / b.n, l: b.l / b.n }))
    .map((c) => ({ ...c, w: c.n })).sort((a, b) => b.w - a.w);
  const top = cands[0];
  if (!top || top.n < total * 0.01) return null; // logo casi sin color: se queda el tema elegido
  const primary: Hsl = [top.h, clamp(top.s, 0.65, 1), clamp(top.l, 0.45, 0.62)];
  const second = cands.find((c) => c !== top && hueDist(c.h, top.h) >= 35 && c.w >= top.w * 0.08);
  const accent: Hsl = second ? [second.h, clamp(second.s, 0.6, 1), clamp(second.l, 0.55, 0.7)] : [(top.h + 25) % 360, clamp(top.s, 0.65, 1), 0.68];
  // Fondo: casi negro con el tono del fondo oscuro del logo (si lo tiene) o del color principal.
  const bgHue = darkS > dark * 0.3 && darkS > 0 ? darkH / darkS : top.h;
  const background: Hsl = [bgHue, 0.45, 0.05];
  return { primary: hslToHex(primary), accent: hslToHex(accent), background: hslToHex(background) };
}

/** Paleta completa de la app a partir de los 3 colores de la marca. */
export function fullPalette(c: BrandColors): Record<string, string> {
  const [ph, ps, pl] = hexToHsl(c.primary);
  const [bh, bs, bl] = hexToHsl(c.background);
  const on = (hex: string) => (lum(hex) > 0.6 ? '#0A0F18' : '#FFFFFF');
  const bgL = clamp(bl, 0.02, 0.12);
  return {
    primary: c.primary, buttonBackground: c.primary, onPrimary: on(c.primary), buttonText: on(c.primary),
    accent: c.accent, focusRing: c.accent,
    secondary: hslToHex([ph, ps, clamp(pl - 0.16, 0.2, 0.5)]),
    background: c.background,
    surface: hslToHex([bh, clamp(bs, 0.2, 0.5), bgL + 0.05]),
    surfaceVariant: hslToHex([bh, clamp(bs, 0.2, 0.45), bgL + 0.1]),
    textSecondary: hslToHex([bh, 0.14, 0.68]),
    onBackground: '#F2F4F8', onSurface: '#E3E7EE',
  };
}

/** Degradado del fondo: del fondo de la marca a un tono profundo de su color principal. */
export function gradientEnd(c: BrandColors): string { const [ph] = hexToHsl(c.primary); return hslToHex([ph, 0.6, 0.12]); }

/** El tema (distribución, letra, bordes) cuyo color se parece más al del logotipo. */
export function closestTheme<T extends { id: string; palette: Record<string, string> }>(themes: T[], primary: string): T {
  const [h] = hexToHsl(primary);
  return themes.slice().sort((a, b) => hueDist(hexToHsl(a.palette.primary!)[0], h) - hueDist(hexToHsl(b.palette.primary!)[0], h))[0]!;
}

/** Lee el logotipo en el navegador (reducido a 96×96) y devuelve sus colores. */
export async function logoColors(file: Blob): Promise<BrandColors | null> {
  const bmp = await createImageBitmap(file);
  const size = 96, cv = document.createElement('canvas');
  cv.width = size; cv.height = size;
  const ctx = cv.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  ctx.drawImage(bmp, 0, 0, size, size);
  return paletteFromPixels(ctx.getImageData(0, 0, size, size).data);
}

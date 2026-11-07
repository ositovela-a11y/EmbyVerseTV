// Ajusta cualquier imagen que el navegador pueda abrir (PNG, JPG, WebP, GIF, BMP, SVG, AVIF, ICO…)
// para que el panel y la app la acepten: tamaño máximo, formato PNG/JPG/WebP y peso menor a 1,4 MB.
// Todo ocurre en el navegador: la imagen original nunca sale de tu equipo.

const MAX_BYTES = 1_400_000;

function loadImage(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('decode')); };
    img.src = url;
  });
}

const toBlob = (c: HTMLCanvasElement, type: string, q?: number) => new Promise<Blob | null>((r) => c.toBlob(r, type, q));

/** Esquinas de la imagen: ¿tiene transparencia? (para decidir PNG/WebP o JPG). */
function hasAlpha(ctx: CanvasRenderingContext2D, w: number, h: number): boolean {
  const d = ctx.getImageData(0, 0, w, h).data;
  const step = Math.max(4, Math.floor(d.length / 4 / 40_000)) * 4;
  for (let i = 3; i < d.length; i += step) if (d[i]! < 250) return true;
  return false;
}

export interface FixedImage { file: File; changed: boolean; note: string }

/**
 * Devuelve la imagen lista para subir. Logotipos: máximo 1024 px, se conserva la transparencia.
 * Banners e imágenes: máximo 1920 px. Si ya cumple (formato, tamaño y peso) se deja igual.
 */
export async function fixImage(file: File, kind: 'logo' | 'banner' | 'image'): Promise<FixedImage> {
  const maxSide = kind === 'logo' ? 1024 : 1920;
  const okType = /^image\/(png|jpeg|webp)$/.test(file.type);
  let img: HTMLImageElement;
  try { img = await loadImage(file); } catch {
    if (/heic|heif/i.test(file.type) || /\.(heic|heif)$/i.test(file.name))
      throw new Error('Las fotos HEIC del iPhone no se pueden abrir en este navegador. En el iPhone: Ajustes → Cámara → Formatos → «Más compatible», o compártela como JPG.');
    throw new Error('No se pudo abrir la imagen. Prueba con otra o guárdala como PNG o JPG.');
  }
  const w0 = img.naturalWidth || 512, h0 = img.naturalHeight || 512;
  if (okType && file.size <= MAX_BYTES && Math.max(w0, h0) <= maxSide) return { file, changed: false, note: '' };

  const scale = Math.min(1, maxSide / Math.max(w0, h0));
  const w = Math.max(1, Math.round(w0 * scale)), h = Math.max(1, Math.round(h0 * scale));
  const canvas = document.createElement('canvas'); canvas.width = w; canvas.height = h;
  const ctx = canvas.getContext('2d')!;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(img, 0, 0, w, h);
  const alpha = hasAlpha(ctx, w, h);

  let blob: Blob | null = null; let ext = 'png';
  if (alpha || kind === 'logo') {
    blob = await toBlob(canvas, 'image/png');
    if (!blob || blob.size > MAX_BYTES) { for (const q of [0.92, 0.85, 0.75]) { blob = await toBlob(canvas, 'image/webp', q); ext = 'webp'; if (blob && blob.size <= MAX_BYTES) break; } }
  } else {
    for (const q of [0.9, 0.82, 0.72, 0.6]) { blob = await toBlob(canvas, 'image/jpeg', q); ext = 'jpg'; if (blob && blob.size <= MAX_BYTES) break; }
  }
  if (!blob) throw new Error('No se pudo convertir la imagen.');
  const type = ext === 'png' ? 'image/png' : ext === 'webp' ? 'image/webp' : 'image/jpeg';
  const base = file.name.replace(/\.[^.]+$/, '') || 'imagen';
  const note = `Imagen ajustada automáticamente: ${w0}×${h0} → ${w}×${h}, ${ext.toUpperCase()}, ${Math.round(blob.size / 1024)} KB.`;
  return { file: new File([blob], `${base}.${ext}`, { type }), changed: true, note };
}

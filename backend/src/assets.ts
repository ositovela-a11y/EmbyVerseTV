import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { DB } from './db.js';
import { HttpError } from './http.js';

export const MAX_ASSET_BYTES = 3 * 1024 * 1024;
export const MAX_DIMENSION = 4096;

export interface AssetRow {
  id: string; filename: string; mime: string; size: number; width: number | null; height: number | null;
  kind: 'image' | 'banner' | 'logo'; approved: number; uploaded_by: number | null; created_at: number;
}

const EXT: Record<string, string> = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp' };

/**
 * Detecta el tipo REAL por los bytes iniciales (no se confía en el nombre ni en Content-Type).
 * Sólo PNG, JPEG y WebP: se excluye SVG porque puede contener scripts.
 */
export function sniffImage(buf: Buffer): { mime: string; width: number; height: number } | null {
  if (buf.length >= 24 && buf.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])) && buf.toString('ascii', 12, 16) === 'IHDR') {
    return { mime: 'image/png', width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  }
  if (buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) {
    let o = 2;
    while (o + 9 < buf.length) {
      if (buf[o] !== 0xff) { o++; continue; }
      const marker = buf[o + 1]!;
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { o += 2; continue; }
      const len = buf.readUInt16BE(o + 2);
      if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
        return { mime: 'image/jpeg', height: buf.readUInt16BE(o + 5), width: buf.readUInt16BE(o + 7) };
      }
      o += 2 + len;
    }
    return null;
  }
  if (buf.length >= 30 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') {
    const chunk = buf.toString('ascii', 12, 16);
    if (chunk === 'VP8X') return { mime: 'image/webp', width: 1 + buf.readUIntLE(24, 3), height: 1 + buf.readUIntLE(27, 3) };
    if (chunk === 'VP8L') { const b = buf.readUInt32LE(21); return { mime: 'image/webp', width: (b & 0x3fff) + 1, height: ((b >> 14) & 0x3fff) + 1 }; }
    if (chunk === 'VP8 ') return { mime: 'image/webp', width: buf.readUInt16LE(26) & 0x3fff, height: buf.readUInt16LE(28) & 0x3fff };
  }
  return null;
}

export class AssetStore {
  private dir: string;
  constructor(private db: DB, dataDir: string, private publicBaseUrl: string) {
    this.dir = join(dataDir, 'assets');
    mkdirSync(this.dir, { recursive: true });
  }

  fileName(a: Pick<AssetRow, 'id' | 'mime'>) { return `${a.id}.${EXT[a.mime] ?? 'bin'}`; }
  publicUrl(a: Pick<AssetRow, 'id' | 'mime'>) { return `${this.publicBaseUrl.replace(/\/$/, '')}/api/v1/public/assets/${this.fileName(a)}`; }

  save(buf: Buffer, originalName: string, kind: AssetRow['kind'], userId: number): AssetRow {
    if (buf.length === 0) throw new HttpError(400, 'empty_file', 'Archivo vacío');
    if (buf.length > MAX_ASSET_BYTES) throw new HttpError(413, 'file_too_large', `La imagen supera ${MAX_ASSET_BYTES / 1024 / 1024} MB`);
    const info = sniffImage(buf);
    if (!info) throw new HttpError(415, 'unsupported_image', 'Formato no admitido. Usa PNG, JPEG o WebP.');
    if (info.width < 1 || info.height < 1 || info.width > MAX_DIMENSION || info.height > MAX_DIMENSION) {
      throw new HttpError(422, 'bad_dimensions', `Dimensiones no válidas (máx. ${MAX_DIMENSION} px)`);
    }
    const id = createHash('sha256').update(buf).digest('hex').slice(0, 32);
    const existing = this.get(id);
    if (existing) return existing;
    const safeName = originalName.replace(/[^\w.\- ]+/g, '_').slice(0, 120) || 'imagen';
    const row: AssetRow = { id, filename: safeName, mime: info.mime, size: buf.length, width: info.width, height: info.height,
      kind, approved: kind === 'logo' ? 0 : 1, uploaded_by: userId, created_at: Date.now() };
    writeFileSync(join(this.dir, this.fileName(row)), buf, { flag: 'wx' });
    this.db.prepare('INSERT INTO assets (id, filename, mime, size, width, height, kind, approved, uploaded_by, created_at) VALUES (?,?,?,?,?,?,?,?,?,?)')
      .run(row.id, row.filename, row.mime, row.size, row.width, row.height, row.kind, row.approved, row.uploaded_by, row.created_at);
    return row;
  }

  get(id: string): AssetRow | undefined {
    return this.db.prepare('SELECT * FROM assets WHERE id = ?').get(id) as AssetRow | undefined;
  }

  list(): AssetRow[] { return this.db.prepare('SELECT * FROM assets ORDER BY created_at DESC').all() as unknown as AssetRow[]; }

  approve(id: string, approved: boolean) {
    const r = this.db.prepare('UPDATE assets SET approved = ? WHERE id = ?').run(approved ? 1 : 0, id);
    if (r.changes === 0) throw new HttpError(404, 'not_found', 'Recurso no encontrado');
  }

  delete(id: string) {
    const a = this.get(id);
    if (!a) throw new HttpError(404, 'not_found', 'Recurso no encontrado');
    this.db.prepare('DELETE FROM assets WHERE id = ?').run(id);
    const f = join(this.dir, this.fileName(a));
    if (existsSync(f)) unlinkSync(f);
  }

  /** Lee el archivo por nombre público "<id>.<ext>" validando el formato (evita recorrer rutas). */
  read(file: string): { row: AssetRow; data: Buffer } | null {
    const m = /^([a-f0-9]{32})\.(png|jpg|webp)$/.exec(file);
    if (!m) return null;
    const row = this.get(m[1]!);
    if (!row || this.fileName(row) !== file) return null;
    const p = join(this.dir, file);
    return existsSync(p) ? { row, data: readFileSync(p) } : null;
  }

  /** Si la URL apunta a un recurso de este servidor, devuelve su fila. */
  fromUrl(url: string): AssetRow | null {
    const prefix = `${this.publicBaseUrl.replace(/\/$/, '')}/api/v1/public/assets/`;
    if (!url.startsWith(prefix)) return null;
    const m = /^([a-f0-9]{32})\./.exec(url.slice(prefix.length));
    return m ? this.get(m[1]!) ?? null : null;
  }
}

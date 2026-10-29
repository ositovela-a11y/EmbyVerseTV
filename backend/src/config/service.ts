import { createHash } from 'node:crypto';
import type { AssetStore } from '../assets.js';
import { tx, type DB } from '../db.js';
import { HttpError } from '../http.js';
import { MAX_CONFIG_BYTES, parseConfig, type ParseResult, type RemoteConfig } from './schema.js';

export interface VersionRow {
  version: number; config_json: string; etag: string; published_at: number;
  published_by: number | null; note: string | null; rollback_of: number | null;
}

export interface ValidationReport extends ParseResult { assetErrors: string[] }

/**
 * Borrador → validación → publicación versionada. Cada publicación (incluida una reversión)
 * crea una versión NUEVA con número creciente; nunca se reescribe el historial.
 */
export class ConfigService {
  constructor(
    private db: DB,
    private defaults: RemoteConfig,
    private assets: AssetStore,
    private allowedImageHosts: string[],
  ) {}

  latestVersion(): VersionRow | undefined {
    return this.db.prepare('SELECT * FROM versions ORDER BY version DESC LIMIT 1').get() as VersionRow | undefined;
  }

  /** Última versión compatible con la app que la pide (las apps antiguas reciben la última que pueden usar). */
  latestFor(appVersionCode: number | null): VersionRow | undefined {
    const rows = this.db.prepare('SELECT * FROM versions ORDER BY version DESC LIMIT 50').all() as unknown as VersionRow[];
    if (appVersionCode === null) return rows[0];
    return rows.find((r) => {
      try { return (JSON.parse(r.config_json).minAppVersionCode ?? 1) <= appVersionCode; } catch { return false; }
    });
  }

  getVersion(v: number): VersionRow {
    const row = this.db.prepare('SELECT * FROM versions WHERE version = ?').get(v) as VersionRow | undefined;
    if (!row) throw new HttpError(404, 'not_found', `La versión ${v} no existe`);
    return row;
  }

  listVersions() {
    return this.db.prepare(`SELECT v.version, v.etag, v.published_at, v.note, v.rollback_of, u.email AS published_by
      FROM versions v LEFT JOIN users u ON u.id = v.published_by ORDER BY v.version DESC LIMIT 200`).all();
  }

  getDraft(): { config: unknown; baseVersion: number; updatedAt: number; updatedBy: string | null } {
    const row = this.db.prepare(`SELECT d.config_json, d.base_version, d.updated_at, u.email FROM draft d LEFT JOIN users u ON u.id = d.updated_by WHERE d.id = 1`)
      .get() as { config_json: string; base_version: number; updated_at: number; email: string | null } | undefined;
    if (row) return { config: JSON.parse(row.config_json), baseVersion: row.base_version, updatedAt: row.updated_at, updatedBy: row.email };
    const latest = this.latestVersion();
    const config = latest ? JSON.parse(latest.config_json) : structuredClone(this.defaults);
    return { config, baseVersion: latest?.version ?? 0, updatedAt: 0, updatedBy: null };
  }

  validate(config: unknown): ValidationReport {
    const r = parseConfig(config, this.defaults);
    const assetErrors = r.ok && r.config ? this.checkAssets(r.config) : [];
    return { ...r, ok: r.ok && assetErrors.length === 0, assetErrors };
  }

  /** Imágenes: sólo recursos de esta biblioteca u hosts autorizados; logotipos: sólo variantes aprobadas. */
  private checkAssets(c: RemoteConfig): string[] {
    const errs: string[] = [];
    if (c.brand.logo.variant === 'asset' && c.brand.logo.url) {
      const a = this.assets.fromUrl(c.brand.logo.url);
      if (!a || a.kind !== 'logo') errs.push('$.brand.logo.url: debe ser un logotipo de la biblioteca de recursos');
      else if (!a.approved) errs.push('$.brand.logo.url: el logotipo aún no está aprobado por un propietario');
    }
    c.banners.forEach((b, i) => {
      if (!b.imageUrl) return;
      if (this.assets.fromUrl(b.imageUrl)) return;
      const host = new URL(b.imageUrl).hostname;
      if (!this.allowedImageHosts.includes(host)) errs.push(`$.banners[${i}].imageUrl: el host '${host}' no está autorizado (sube la imagen a la biblioteca)`);
    });
    return errs;
  }

  saveDraft(config: unknown, userId: number, baseVersion?: number): ValidationReport {
    if (typeof config !== 'object' || config === null || Array.isArray(config)) throw new HttpError(422, 'invalid_config', 'La configuración debe ser un objeto JSON');
    const json = JSON.stringify(config);
    if (Buffer.byteLength(json) > MAX_CONFIG_BYTES) throw new HttpError(413, 'config_too_large', 'La configuración es demasiado grande');
    const base = baseVersion ?? this.getDraft().baseVersion;
    this.db.prepare(`INSERT INTO draft (id, config_json, base_version, updated_at, updated_by) VALUES (1, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET config_json = excluded.config_json, base_version = excluded.base_version, updated_at = excluded.updated_at, updated_by = excluded.updated_by`)
      .run(json, base, Date.now(), userId);
    return this.validate(config);
  }

  discardDraft() { this.db.prepare('DELETE FROM draft WHERE id = 1').run(); }

  /** Publica el borrador si es válido. Devuelve la versión creada. */
  publish(userId: number, note: string | null, force = false): { version: number; report: ValidationReport } {
    const draft = this.getDraft();
    const latest = this.latestVersion()?.version ?? 0;
    // Protección ante dos administradores publicando a la vez sobre bases distintas.
    if (!force && draft.baseVersion !== latest) {
      throw new HttpError(409, 'stale_draft', `El borrador se basa en la versión ${draft.baseVersion} pero ya existe la ${latest}. Revisa los cambios antes de publicar.`);
    }
    const report = this.validate(draft.config);
    if (!report.ok || !report.config) throw new HttpError(422, 'invalid_config', 'La configuración no es válida', report);
    const version = this.insertVersion(draft.config as Record<string, unknown>, userId, note, null);
    this.db.prepare('UPDATE draft SET base_version = ?, config_json = ? WHERE id = 1').run(version, this.getVersion(version).config_json);
    return { version, report };
  }

  /** Reversión: publica de nuevo el contenido de una versión anterior como versión nueva. */
  rollback(toVersion: number, userId: number, note: string | null): number {
    const old = this.getVersion(toVersion);
    const cfg = JSON.parse(old.config_json) as Record<string, unknown>;
    const report = this.validate(cfg);
    if (!report.ok) throw new HttpError(422, 'invalid_config', 'La versión indicada ya no es válida (p. ej. una imagen fue eliminada)', report);
    const version = this.insertVersion(cfg, userId, note ?? `Reversión a la versión ${toVersion}`, toVersion);
    this.db.prepare('DELETE FROM draft WHERE id = 1').run();
    return version;
  }

  private insertVersion(cfg: Record<string, unknown>, userId: number, note: string | null, rollbackOf: number | null): number {
    return tx(this.db, () => {
      const next = (this.latestVersion()?.version ?? 0) + 1;
      const published = { ...cfg, version: next, publishedAt: new Date().toISOString() };
      const json = JSON.stringify(published);
      const etag = '"' + createHash('sha256').update(json).digest('hex').slice(0, 32) + '"';
      this.db.prepare('INSERT INTO versions (version, config_json, etag, published_at, published_by, note, rollback_of) VALUES (?,?,?,?,?,?,?)')
        .run(next, json, etag, Date.now(), userId, note?.slice(0, 300) ?? null, rollbackOf);
      return next;
    });
  }

  /** ¿Algún recurso está en uso en la versión publicada o en el borrador? */
  assetInUse(url: string): boolean {
    const latest = this.latestVersion();
    const draft = JSON.stringify(this.getDraft().config);
    return draft.includes(url) || (latest?.config_json.includes(url) ?? false);
  }
}

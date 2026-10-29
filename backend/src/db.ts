import { DatabaseSync } from 'node:sqlite';
import { mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

/**
 * SQLite embebido (módulo nativo node:sqlite, sin dependencias). Suficiente para un panel con
 * pocos administradores y una configuración pequeña; para alta disponibilidad ver docs/BACKEND.md.
 */
export type DB = DatabaseSync;

const MIGRATIONS: string[] = [
  `CREATE TABLE users (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     email TEXT NOT NULL UNIQUE COLLATE NOCASE,
     name TEXT NOT NULL,
     role TEXT NOT NULL CHECK (role IN ('owner','publisher','editor','viewer')),
     password_hash TEXT NOT NULL,
     disabled INTEGER NOT NULL DEFAULT 0,
     failed_logins INTEGER NOT NULL DEFAULT 0,
     locked_until INTEGER,
     created_at INTEGER NOT NULL,
     password_changed_at INTEGER NOT NULL
   );
   CREATE TABLE sessions (
     id_hash TEXT PRIMARY KEY,
     user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
     csrf TEXT NOT NULL,
     created_at INTEGER NOT NULL,
     last_seen INTEGER NOT NULL,
     expires_at INTEGER NOT NULL,
     ip TEXT, user_agent TEXT
   );
   CREATE TABLE draft (
     id INTEGER PRIMARY KEY CHECK (id = 1),
     config_json TEXT NOT NULL,
     base_version INTEGER NOT NULL DEFAULT 0,
     updated_at INTEGER NOT NULL,
     updated_by INTEGER
   );
   CREATE TABLE versions (
     version INTEGER PRIMARY KEY,
     config_json TEXT NOT NULL,
     etag TEXT NOT NULL,
     published_at INTEGER NOT NULL,
     published_by INTEGER,
     note TEXT,
     rollback_of INTEGER
   );
   CREATE TABLE assets (
     id TEXT PRIMARY KEY,
     filename TEXT NOT NULL,
     mime TEXT NOT NULL,
     size INTEGER NOT NULL,
     width INTEGER, height INTEGER,
     kind TEXT NOT NULL CHECK (kind IN ('image','banner','logo')),
     approved INTEGER NOT NULL DEFAULT 0,
     uploaded_by INTEGER,
     created_at INTEGER NOT NULL
   );
   CREATE TABLE audit (
     id INTEGER PRIMARY KEY AUTOINCREMENT,
     at INTEGER NOT NULL,
     user_id INTEGER,
     user_email TEXT,
     action TEXT NOT NULL,
     target TEXT,
     detail TEXT,
     ip TEXT
   );
   CREATE INDEX audit_at ON audit(at DESC);`,
];

export function openDb(path: string): DB {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
  db.exec('CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)');
  const row = db.prepare('SELECT MAX(version) AS v FROM schema_migrations').get() as { v: number | null } | undefined;
  const current = row?.v ?? 0;
  MIGRATIONS.forEach((sql, i) => {
    const v = i + 1;
    if (v <= current) return;
    db.exec('BEGIN');
    try {
      db.exec(sql);
      db.prepare('INSERT INTO schema_migrations (version, applied_at) VALUES (?, ?)').run(v, Date.now());
      db.exec('COMMIT');
    } catch (e) { db.exec('ROLLBACK'); throw e; }
  });
  return db;
}

export function tx<T>(db: DB, fn: () => T): T {
  db.exec('BEGIN IMMEDIATE');
  try { const r = fn(); db.exec('COMMIT'); return r; } catch (e) { db.exec('ROLLBACK'); throw e; }
}

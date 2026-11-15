-- Estadísticas de instalaciones y descargas (Cloudflare D1). Una fila por equipo instalado.
CREATE TABLE IF NOT EXISTS devices (
  id         TEXT PRIMARY KEY,   -- código al azar de cada instalación (no identifica a la persona)
  app        TEXT NOT NULL,      -- '' = app principal; si no, el identificador del vendedor
  platform   TEXT,               -- android, androidtv, samsung, lg, pc
  model      TEXT,
  version    TEXT,
  xuser      TEXT,               -- usuario Xtream con el que entra (nunca la contraseña)
  host       TEXT,               -- servidor IPTV que usa
  country    TEXT,
  first_seen INTEGER NOT NULL,
  last_seen  INTEGER NOT NULL,
  opens      INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS devices_app_seen ON devices(app, last_seen);
CREATE TABLE IF NOT EXISTS downloads (
  day      TEXT NOT NULL,
  app      TEXT NOT NULL,
  platform TEXT NOT NULL,
  country  TEXT NOT NULL,
  n        INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, app, platform, country)
);

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
-- Perfiles, favoritos y «seguir viendo» de cada cuenta, iguales en todos sus equipos.
-- id = huella de (servidor + usuario + contraseña) calculada en el equipo: aquí no se guarda ni el
-- usuario ni la contraseña, y solo quien tiene la cuenta puede leer o cambiar sus datos.
CREATE TABLE IF NOT EXISTS sync (
  id      TEXT PRIMARY KEY,
  rev     INTEGER NOT NULL,
  data    TEXT NOT NULL,
  updated INTEGER NOT NULL
);
-- Reporte de fallos: cierres inesperados y videos que no se pudieron reproducir. Se agrupan por «firma»
-- (app + plataforma + versión + tipo + mensaje) para contar cuántas veces y en cuántos equipos pasa.
-- No lleva usuario, contraseña ni dirección del canal; solo el equipo (código al azar), modelo y el error.
CREATE TABLE IF NOT EXISTS crashes (
  sig        TEXT PRIMARY KEY,
  app        TEXT NOT NULL,
  platform   TEXT,
  version    TEXT,
  kind       TEXT NOT NULL,      -- crash (la app se cerró) · play (no reprodujo) · js (error en TV/PC)
  message    TEXT,
  detail     TEXT,               -- primeras líneas del error
  model      TEXT,               -- último modelo de equipo donde pasó
  os         TEXT,
  last_id    TEXT,               -- último equipo (para buscarlo en Estadísticas)
  devices    TEXT NOT NULL DEFAULT '',  -- hasta 50 equipos distintos, separados por coma
  n          INTEGER NOT NULL DEFAULT 1,
  first_seen INTEGER NOT NULL,
  last_seen  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS crashes_seen ON crashes(last_seen);

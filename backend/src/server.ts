import { join } from 'node:path';
import { createApp } from './app.js';
import { parseConfig } from './config/schema.js';
import { openDb } from './db.js';
import { loadDefaults, loadEnv } from './env.js';

const env = loadEnv();
const defaults = loadDefaults(env.defaultsPath);
const check = parseConfig(defaults, defaults);
if (!check.ok) { console.error('La configuración predeterminada no es válida:', check.errors); process.exit(1); }

const db = openDb(join(env.dataDir, 'embyverse.db'));
const app = createApp({
  db, dataDir: env.dataDir, defaults, publicBaseUrl: env.publicBaseUrl, secureCookies: env.secureCookies,
  trustProxy: env.trustProxy, allowedImageHosts: env.allowedImageHosts, adminDist: env.adminDist,
});

// Primer arranque: crea el propietario a partir de variables de entorno (nunca credenciales fijas en el código).
const users = (db.prepare('SELECT COUNT(*) AS n FROM users').get() as { n: number }).n;
if (users === 0) {
  const email = process.env.ADMIN_EMAIL, password = process.env.ADMIN_PASSWORD;
  if (email && password) {
    app.auth.createUser(email, process.env.ADMIN_NAME ?? 'Propietario', 'owner', password);
    console.log(`Usuario propietario creado: ${email}. Elimina ADMIN_PASSWORD del entorno.`);
  } else {
    console.warn('No hay usuarios. Crea el propietario con: npm run cli -- create-owner <email> (o ADMIN_EMAIL + ADMIN_PASSWORD).');
  }
}

if (!env.publicBaseUrl.startsWith('https://')) {
  console.warn(`Aviso: PUBLIC_BASE_URL (${env.publicBaseUrl}) no es https. Las imágenes subidas no pasarán la validación, porque la app solo acepta recursos por HTTPS.`);
}

app.server.listen(env.port, env.host, () => {
  console.log(`EmbyVerse backend escuchando en http://${env.host}:${env.port} · URL pública ${env.publicBaseUrl}`);
});

const shutdown = () => { app.server.close(() => { db.close(); process.exit(0); }); setTimeout(() => process.exit(0), 5000).unref(); };
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

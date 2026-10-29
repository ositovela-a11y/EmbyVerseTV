import { join } from 'node:path';
import { createInterface } from 'node:readline/promises';
import { Auth } from './auth.js';
import { openDb } from './db.js';
import { loadEnv } from './env.js';

/** Utilidades de administración: npm run cli -- create-owner <email> [nombre] */
async function main() {
  const [cmd, email, name] = process.argv.slice(2);
  const env = loadEnv();
  const db = openDb(join(env.dataDir, 'embyverse.db'));
  const auth = new Auth(db, env.secureCookies);
  if (cmd === 'create-owner' && email) {
    const rl = createInterface({ input: process.stdin, output: process.stdout });
    const password = process.env.ADMIN_PASSWORD ?? (await rl.question('Contraseña (mín. 12 caracteres, letras y números): '));
    rl.close();
    const id = auth.createUser(email, name ?? 'Propietario', 'owner', password);
    console.log(`Propietario creado (id ${id}).`);
  } else if (cmd === 'unlock' && email) {
    db.prepare('UPDATE users SET failed_logins = 0, locked_until = NULL WHERE email = ?').run(email);
    console.log('Usuario desbloqueado.');
  } else {
    console.log('Uso:\n  npm run cli -- create-owner <email> [nombre]\n  npm run cli -- unlock <email>');
    process.exitCode = 1;
  }
  db.close();
}
main().catch((e) => { console.error(e instanceof Error ? e.message : e); process.exit(1); });

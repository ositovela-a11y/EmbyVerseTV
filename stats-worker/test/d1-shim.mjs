// D1 de mentira con SQLite de Node (solo para probar el servidor sin Cloudflare).
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
export function makeDB(schemaPath) {
  const db = new DatabaseSync(':memory:');
  db.exec(readFileSync(schemaPath, 'utf8'));
  const conv = (sql) => sql.replace(/\?(\d+)/g, '?$1');
  return {
    raw: db,
    prepare(sql) {
      let args = [];
      const st = db.prepare(conv(sql));
      const api = {
        bind(...a) { args = a; return api; },
        async run() { st.run(...args); return { success: true }; },
        async all() { return { results: st.all(...args) }; },
        async first() { return st.get(...args) ?? null; },
      };
      return api;
    },
  };
}

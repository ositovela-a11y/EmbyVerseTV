// Prueba la combinación de perfiles entre equipos (sin TV): agregar, borrar, renombrar y conflictos.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
const out = await esbuild.build({ entryPoints: [new URL('../src/account-sync.ts', import.meta.url).pathname], bundle: true, format: 'esm', write: false, platform: 'neutral',
  define: { __CONFIG_URL__: '""', __VERSION__: '"t"' } });
globalThis.window = globalThis; globalThis.navigator ??= { userAgent: 'node', platform: 'node' };
const store = new Map(); globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k), get length() { return store.size; } };
const m = await import('data:text/javascript;base64,' + Buffer.from(out.outputFiles[0].text).toString('base64'));
const t = (name, f) => { f(); console.log('✔', name); };
const P = (uid, name) => ({ uid, name });
const F = (id, at) => ({ kind: 'movie', id, title: id, at });
const doc = (profiles, favs = {}, prog = {}) => ({ v: 1, profiles, favs, prog });

t('primera vez: se juntan los perfiles de los dos equipos', () => {
  const r = m.mergeDoc(null, doc([P('a', 'Principal'), P('k', 'Niños')]), doc([P('a', 'Principal'), P('p', 'Papá')]));
  assert.deepEqual(r.profiles.map((p) => p.name), ['Principal', 'Papá', 'Niños']);
});
t('perfil borrado en otro equipo se borra aquí', () => {
  const base = doc([P('a', 'A'), P('b', 'B')]);
  assert.deepEqual(m.mergeDoc(base, doc([P('a', 'A'), P('b', 'B')]), doc([P('a', 'A')])).profiles.map((p) => p.uid), ['a']);
});
t('perfil borrado aquí se borra en el servidor', () => {
  const base = doc([P('a', 'A'), P('b', 'B')]);
  assert.deepEqual(m.mergeDoc(base, doc([P('a', 'A')]), doc([P('a', 'A'), P('b', 'B')])).profiles.map((p) => p.uid), ['a']);
});
t('renombrar aquí gana; renombrar allá llega aquí', () => {
  const base = doc([P('a', 'A')]);
  assert.equal(m.mergeDoc(base, doc([P('a', 'Mamá')]), doc([P('a', 'A')])).profiles[0].name, 'Mamá');
  assert.equal(m.mergeDoc(base, doc([P('a', 'A')]), doc([P('a', 'Papá')])).profiles[0].name, 'Papá');
});
t('favoritos: se suman y los quitados en un equipo se quitan en todos', () => {
  const base = doc([P('a', 'A')], { a: [F('1', 1), F('2', 2)] });
  const r = m.mergeDoc(base, doc([P('a', 'A')], { a: [F('1', 1), F('2', 2), F('3', 3)] }), doc([P('a', 'A')], { a: [F('1', 1), F('4', 4)] }));
  assert.deepEqual(r.favs.a.map((f) => f.id).sort(), ['1', '3', '4']);
});
t('seguir viendo: gana el más reciente y un episodio por serie', () => {
  const g = (id, at, pos, seriesId) => ({ kind: seriesId ? 'episode' : 'movie', id, seriesId, title: id, pos, dur: 100, at });
  const r = m.mergeDoc(null, doc([P('a', 'A')], {}, { a: [g('m', 5, 10), g('e1', 1, 5, 's')] }), doc([P('a', 'A')], {}, { a: [g('m', 9, 70), g('e2', 3, 8, 's')] }));
  assert.equal(r.prog.a.find((x) => x.id === 'm').pos, 70);
  assert.deepEqual(r.prog.a.filter((x) => x.seriesId === 's').map((x) => x.id), ['e2']);
});
t('mismo nombre en otro equipo = mismo perfil (no se duplica)', () => {
  const l = m.adoptUids([{ name: 'principal' }, { name: 'Nuevo' }], [P('a', 'Principal')], () => 'zz');
  assert.deepEqual(l.map((p) => p.uid), ['a', 'zz']);
});
const k = await m.sha256Hex('ev-sync-v1|krnosmx.com|u|p');
assert.equal(k, createHash('sha256').update('ev-sync-v1|krnosmx.com|u|p').digest('hex')); console.log('✔ llave sha256 igual en TV y servidor');
const saved = globalThis.crypto; Object.defineProperty(globalThis, 'crypto', { value: { getRandomValues: saved.getRandomValues.bind(saved) }, configurable: true });
assert.equal(await m.sha256Hex('ev-sync-v1|krnosmx.com|ñandú|p@ss'), createHash('sha256').update('ev-sync-v1|krnosmx.com|ñandú|p@ss').digest('hex'));
Object.defineProperty(globalThis, 'crypto', { value: saved, configurable: true }); console.log('✔ sha256 sin crypto.subtle (TV viejos)');

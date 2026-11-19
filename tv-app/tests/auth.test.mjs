// Autorización del dueño (TV/PC): solo servidores autorizados y apagado a distancia.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
const out = await esbuild.build({ entryPoints: [new URL('../src/config.ts', import.meta.url).pathname], bundle: true, format: 'esm', write: false, platform: 'neutral',
  define: { __CONFIG_URL__: '""', __VERSION__: '"t"', __DEFAULT_HOST__: '"https://krnosmx.com:443"' }, logLevel: 'silent' });
globalThis.window = globalThis; globalThis.navigator ??= { userAgent: 'node', platform: 'node' };
const store = new Map(); globalThis.localStorage = { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: (k) => store.delete(k), get length() { return store.size; } };
const m = await import('data:text/javascript;base64,' + Buffer.from(out.outputFiles[0].text).toString('base64'));
const t = (name, f) => { f(); console.log('✔', name); };
const cfg = (texts) => ({ texts: { 'provider.host': 'https://mi.proveedor.com:443', ...texts } });
t('apagado por defecto: todo se permite', () => assert.ok(m.authAllowed(cfg({}), 'http://pirata.com')));
t('encendido: proveedor y subdominios sí, otros no', () => {
  const c = cfg({ 'auth.only': '1', 'auth.hosts': 'otro.tv, extra.net' });
  assert.ok(m.authAllowed(c, 'https://mi.proveedor.com:8080')); assert.ok(m.authAllowed(c, 'http://cdn.mi.proveedor.com/l.m3u'));
  assert.ok(m.authAllowed(c, 'http://www.otro.tv/get.php?u=a')); assert.ok(!m.authAllowed(c, 'http://pirata.com'));
  assert.ok(!m.authAllowed(c, 'http://proveedor.com.pirata.net')); assert.ok(!m.authAllowed(c, null));
});
t('apagado a distancia', () => { assert.ok(m.appDisabled(cfg({ 'app.disabled': '1' }))); assert.ok(!m.appDisabled(cfg({}))); });

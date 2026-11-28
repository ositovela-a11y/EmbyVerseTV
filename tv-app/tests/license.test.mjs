// Activación de cobro: Samsung y LG la piden (salvo que el panel la apague), igual que la PC.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
const load = async (tv) => {
  const out = await esbuild.build({ entryPoints: [new URL('../src/license.ts', import.meta.url).pathname], bundle: true, format: 'esm', write: false, platform: 'neutral',
    define: { __EDITION__: '"embyverse"', __LICENSE_URL__: '""', __CONFIG_URL__: '""', __VERSION__: '"t"', __DEFAULT_HOST__: '""' }, logLevel: 'silent' });
  globalThis.window = globalThis; Object.defineProperty(globalThis, 'navigator', { value: { userAgent: tv === 'lg' ? 'Mozilla/5.0 (Web0S; Linux/SmartTV)' : 'Chrome' }, configurable: true });
  if (tv === 'samsung') globalThis.tizen = {}; else delete globalThis.tizen;
  return import('data:text/javascript;base64,' + Buffer.from(out.outputFiles[0].text + `\n//${tv}`).toString('base64'));
};
const s = await load('samsung'); assert.ok(s.licenseRequired({})); assert.ok(!s.licenseRequired({ 'license.required': '0' })); console.log('✔ Samsung: de cobro por defecto; el panel la puede apagar');
const l = await load('lg'); assert.ok(l.licenseRequired({})); console.log('✔ LG: de cobro por defecto');
const n = await load('navegador'); assert.ok(!n.licenseRequired({})); assert.ok(n.licenseRequired({ 'license.required': '1' })); console.log('✔ otros equipos: solo si el panel la pide');

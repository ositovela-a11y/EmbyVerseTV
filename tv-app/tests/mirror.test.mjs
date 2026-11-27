// Respaldo de eversetv.com en TV/PC: si GitHub falla, se pide lo mismo a eversetv.com.
import * as esbuild from 'esbuild';
import assert from 'node:assert/strict';
const out = await esbuild.build({ entryPoints: [new URL('../src/mirror.ts', import.meta.url).pathname], bundle: true, format: 'esm', write: false, platform: 'neutral', logLevel: 'silent' });
const m = await import('data:text/javascript;base64,' + Buffer.from(out.outputFiles[0].text).toString('base64'));
const G = 'https://ositovela-a11y.github.io/EmbyVerseTV/';
assert.equal(m.alternate(G + 'config/app.json?t=1'), 'https://eversetv.com/config/app.json?t=1');
assert.equal(m.alternate('http://krnosmx.com/get.php'), null);
const calls = []; let mode = 'down';
globalThis.window = globalThis;
globalThis.fetch = async (u) => { calls.push(u); if (u.startsWith(G)) { if (mode === 'down') throw new Error('sin red'); if (mode === '404') return new Response('x', { status: 404 }); return new Response('github'); } return new Response('copia'); };
m.installMirror();
assert.equal(await (await fetch(G + 'config/app.json')).text(), 'copia'); console.log('✔ GitHub sin red → copia');
mode = '404'; assert.equal(await (await fetch(G + 'update.json')).text(), 'copia'); console.log('✔ GitHub 404 → copia');
mode = 'ok'; assert.equal(await (await fetch(G + 'update.json')).text(), 'github'); console.log('✔ GitHub bien → GitHub');
assert.equal(await (await fetch('http://krnosmx.com/x')).text(), 'copia'.replace('copia', 'copia')); console.log('✔ otras direcciones no cambian');

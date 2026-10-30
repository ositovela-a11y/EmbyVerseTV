// Decide qué APK compilar (GitHub Actions). Solo se recompila lo necesario: los cambios de colores,
// banners o textos llegan a las apps instaladas sin APK nueva. Hace falta APK nueva cuando cambia el
// código o el NOMBRE / LOGOTIPO de una app (Android no permite cambiarlos sin reinstalar).
import { appendFileSync, readFileSync } from 'node:fs';
import { execSync } from 'node:child_process';

const base = 'https://ositovela-a11y.github.io/EmbyVerseTV/config';
const all = [{ slug: '', name: 'Principal', config: 'web-player/config/app.json', url: `${base}/app.json`, asset: 'EmbyVerse.apk' }];
try {
  for (const v of JSON.parse(readFileSync('web-player/config/vendors/index.json', 'utf8'))) {
    if (!/^[a-z][a-z0-9]{1,30}$/.test(v.slug)) continue;
    all.push({ slug: v.slug, name: v.name, config: `web-player/config/vendors/${v.slug}.json`, url: `${base}/vendors/${v.slug}.json`, asset: `EmbyVerse-${v.slug}.apk` });
  }
} catch { /* sin vendedores */ }

const before = process.env.BEFORE || '';
let changed = null;
if (process.env.EVENT === 'push' && before && !/^0+$/.test(before)) {
  try { changed = execSync(`git diff --name-only ${before} HEAD`).toString().split('\n').filter(Boolean); } catch { changed = null; }
}
const brand = (ref, path) => {
  try {
    const text = ref ? execSync(`git show ${ref}:${path}`, { stdio: ['ignore', 'pipe', 'ignore'] }).toString() : readFileSync(path, 'utf8');
    const c = JSON.parse(text);
    return JSON.stringify([c.brand?.appName, c.brand?.logo, c.theme?.palette?.background]);
  } catch { return null; }
};
let published = [];
try { published = JSON.parse(process.env.PUBLISHED ?? execSync('gh release view apk-latest --json assets -q "[.assets[].name]"').toString()); } catch { /* sin release */ }

const codeChanged = changed === null || changed.some((f) => !f.startsWith('web-player/config/'));
const build = all.filter((t) => codeChanged || !published.includes(t.asset) || brand(before, t.config) !== brand(null, t.config));
const out = (k, v) => process.env.GITHUB_OUTPUT && appendFileSync(process.env.GITHUB_OUTPUT, `${k}=${v}\n`);
out('matrix', JSON.stringify({ include: build }));
out('all', JSON.stringify({ include: all }));
out('count', String(build.length));
console.log(codeChanged ? 'Cambió el código: se compilan todas' : 'Solo diseño', '→', build.map((x) => x.asset).join(', ') || 'ninguna APK (el diseño llega a las apps sin reinstalar)');

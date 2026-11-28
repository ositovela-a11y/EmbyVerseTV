// Compila la app de TV (Samsung Tizen / LG webOS) en dist/ y, con --package, arma los paquetes:
//   dist-pkg/EmbyVerse-Samsung.wgt  (sin firmar: se firma con tu certificado de Samsung, ver docs/TV-SAMSUNG-LG.md)
//   dist-pkg/EmbyVerse-LG.ipk       (listo para instalar en modo desarrollador)
import { createRequire } from 'node:module';
import { cpSync, existsSync, mkdirSync, readFileSync, rmSync, writeFileSync, statSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { execSync } from 'node:child_process';
import zlib from 'node:zlib';

const require = createRequire(import.meta.url);
let esbuild;
try { esbuild = require('esbuild'); } catch { esbuild = require('/opt/npm-tools/node_modules/esbuild'); }

const pkg = JSON.parse(readFileSync('package.json', 'utf8'));
const CONFIG_URL = process.env.EMBYVERSE_CONFIG_URL || 'https://ositovela-a11y.github.io/EmbyVerseTV/config/app.json';
const DEFAULT_HOST = process.env.EMBYVERSE_HOST || 'https://krnosmx.com:443';
const VERSION = process.env.EMBYVERSE_TV_VERSION || pkg.version;

// Librerías opcionales (en CI se instalan con npm; en local, si faltan, se usa un reemplazo vacío).
const resolveOr = (name, stub) => { try { return require.resolve(name); } catch { return stub; } };
const alias = {
  'hls.js': resolveOr('hls.js', new URL('./src/stubs/hls.js', import.meta.url).pathname),
  'mpegts.js': resolveOr('mpegts.js', new URL('./src/stubs/mpegts.js', import.meta.url).pathname),
};
const reactPath = (n) => { try { return dirname(require.resolve(`${n}/package.json`)); } catch { return `/home/claude/.npm-global/lib/node_modules/${n}`; } };

rmSync('dist', { recursive: true, force: true });
mkdirSync('dist', { recursive: true });
cpSync('public', 'dist', { recursive: true });
writeFileSync('dist/index.html', readFileSync('index.html', 'utf8').replaceAll('__VERSION__', VERSION));

// Dos versiones de la app y un cargador:
//  - app.modern.js: TVs 2018+ (Chromium 53 o más nuevo) y PC.
//  - app.legacy.js: TVs viejas (LG webOS 1–3, Samsung 2015–2017…): el mismo código traducido a ES5 con Babel y con
//    los complementos que les faltan (Promise, fetch, Map, Symbol…). Solo se arma si están instaladas las
//    herramientas (en CI sí; en local puede faltar y entonces todas usan la moderna).
//  - app.js: cargador pequeño en ES5 que revisa el navegador y carga la que sirve (también para las TVs que ya
//    tienen instalada una versión anterior, porque su index.html siempre pide «app.js»).
const buildOpts = {
  entryPoints: ['src/main.tsx'],
  bundle: true,
  outfile: 'dist/app.modern.js',
  format: 'iife',
  jsx: 'automatic',
  minify: true,
  // TVs Samsung 2018+ (Tizen 4, Chromium 56) y LG 2018+ (webOS 4, Chromium 53).
  target: ['chrome53'],
  loader: { '.css': 'css' },
  alias: { ...alias, react: reactPath('react'), 'react-dom': reactPath('react-dom') },
  define: {
    'process.env.NODE_ENV': '"production"',
    __CONFIG_URL__: JSON.stringify(CONFIG_URL),
    // «store» = edición para tiendas (reproductor neutral con activación por equipo).
    __EDITION__: JSON.stringify(process.env.EMBYVERSE_EDITION || 'embyverse'),
    __LICENSE_URL__: JSON.stringify(process.env.EMBYVERSE_LICENSE_URL || CONFIG_URL.replace(/[^/]*$/, 'licenses.json')),
    __DEFAULT_HOST__: JSON.stringify(DEFAULT_HOST),
    __VERSION__: JSON.stringify(VERSION),
  },
  logLevel: 'info',
};
await esbuild.build(buildOpts);
if (existsSync('dist/app.modern.css')) cpSync('dist/app.modern.css', 'dist/app.css');

// Cargador (ES5 puro: lo entienden hasta las TVs más viejas).
writeFileSync('dist/app.js', `(function () {
  var cur = document.currentScript, base = '';
  if (cur && cur.src) base = cur.src.replace(/app\\.js(\\?.*)?$/, '');
  function modern() {
    if (/[?&]legacy=1/.test(location.search)) return false;
    try {
      new Function('"use strict"; let a = 1; const f = (x) => x; class C {} return \`\${f(a)}\`;')();
      return !!(window.fetch && window.Promise && window.Symbol && window.CSS && CSS.supports && CSS.supports('(--a: 0)'));
    } catch (e) { return false; }
  }
  var useModern = modern();
  var legacy = ${existsSync('dist/app.legacy.js') ? 'true' : 'false'};
  var name = useModern || !legacy ? 'app.modern' : 'app.legacy';
  window.__EV_BUNDLE__ = name;
  if (name === 'app.legacy') {
    var links = document.getElementsByTagName('link');
    for (var i = 0; i < links.length; i++) if (/app\\.css(\\?|$)/.test(links[i].href)) links[i].href = base + 'app.legacy.css';
  }
  var s = document.createElement('script'); s.src = base + name + '.js';
  document.body.appendChild(s);
})();
`);

// ---------- Versión para TVs viejas (ES5) ----------
await (async () => {
  if ((process.env.EMBYVERSE_EDITION || '') === 'pc') return; // la PC usa siempre la moderna
  let babel, postcss, customProps;
  try { babel = require('@babel/core'); require.resolve('core-js/stable'); require.resolve('regenerator-runtime/runtime');
        require.resolve('whatwg-fetch'); postcss = require('postcss'); customProps = require('postcss-custom-properties'); }
  catch { console.log('Sin herramientas para la versión de TVs viejas: solo se arma la moderna.'); return; }
  writeFileSync('src/.legacy-entry.ts', "import 'core-js/stable';\nimport 'regenerator-runtime/runtime';\nimport 'whatwg-fetch';\nimport 'abortcontroller-polyfill/dist/polyfill-patch-fetch';\nimport './main';\n");
  try {
    await esbuild.build({ ...buildOpts, entryPoints: ['src/.legacy-entry.ts'], outfile: 'dist-legacy/app.js', target: ['es2015'], minify: false, logLevel: 'warning' });
  } finally { rmSync('src/.legacy-entry.ts', { force: true }); }
  const out = await babel.transformAsync(readFileSync('dist-legacy/app.js', 'utf8'), {
    babelrc: false, configFile: false, compact: true, comments: false, sourceType: 'script',
    presets: [[require.resolve('@babel/preset-env'), { targets: { chrome: '34', safari: '7' }, modules: false, useBuiltIns: false, loose: true }]],
  });
  writeFileSync('dist/app.legacy.js', out.code);
  const css = await postcss([customProps({ preserve: true })]).process(readFileSync('dist/app.css', 'utf8'), { from: undefined });
  writeFileSync('dist/app.legacy.css', css.css);
  rmSync('dist-legacy', { recursive: true, force: true });
  console.log(`Versión para TVs viejas: app.legacy.js (${Math.round(out.code.length / 1024)} KB)`);
})();
// El cargador ya se escribió antes: se reescribe sabiendo si hay versión vieja.
{
  const loader = readFileSync('dist/app.js', 'utf8').replace(/var legacy = (true|false);/, `var legacy = ${existsSync('dist/app.legacy.js')};`);
  writeFileSync('dist/app.js', loader);
}

if (!process.argv.includes('--package')) process.exit(0);

// ---------- Paquetes ----------
rmSync('dist-pkg', { recursive: true, force: true });
mkdirSync('dist-pkg', { recursive: true });

// Samsung (Tizen): .wgt = zip con config.xml. Se firma después con el certificado de Samsung.
const tz = 'dist-pkg/tizen';
cpSync('dist', tz, { recursive: true });
writeFileSync(`${tz}/config.xml`, readFileSync('platform/tizen/config.xml', 'utf8').replaceAll('__VERSION__', VERSION));
execSync(`cd ${tz} && zip -qr ../EmbyVerse-Samsung.wgt .`);

// LG (webOS): .ipk = paquete «ar» con control.tar.gz + data.tar.gz, IGUAL que ares-package (herramienta oficial de LG):
//  - data: usr/palm/applications/<id>/ (la app + appinfo.json) y usr/palm/packages/<id>/packageinfo.json,
//  - rutas sin «./», encabezados «ar» sin «/» al final del nombre y modo 100644.
// Sin packageinfo.json (o con el formato de GNU ar) algunas TVs responden «Failed to extract package» (errorCode -5).
const appId = 'com.embyverse.tv';
const wo = 'dist-pkg/webos';
rmSync(wo, { recursive: true, force: true });
const appDir = `${wo}/data/usr/palm/applications/${appId}`;
const pkgDir = `${wo}/data/usr/palm/packages/${appId}`;
mkdirSync(appDir, { recursive: true }); mkdirSync(pkgDir, { recursive: true });
cpSync('dist', appDir, { recursive: true });
writeFileSync(`${appDir}/appinfo.json`, readFileSync('platform/webos/appinfo.json', 'utf8').replaceAll('__VERSION__', VERSION));
writeFileSync(`${pkgDir}/packageinfo.json`, JSON.stringify({ app: appId, id: appId, version: VERSION }, null, 2) + '\n');
mkdirSync(`${wo}/control`, { recursive: true });
const size = execSync(`du -sk ${wo}/data | cut -f1`).toString().trim();
writeFileSync(`${wo}/control/control`, `Package: ${appId}\nVersion: ${VERSION}\nSection: misc\nPriority: optional\nArchitecture: all\nInstalled-Size: ${size}\nMaintainer: N/A <nobody@example.com>\nDescription: This is a webOS application.\nwebOS-Package-Format-Version: 2\nwebOS-Packager-Version: x.y.x\n`);
execSync(`cd ${wo}/control && tar --owner=0 --group=0 --numeric-owner -czf ../control.tar.gz control`);
execSync(`cd ${wo}/data && tar --owner=0 --group=0 --numeric-owner -czf ../data.tar.gz usr`);
{
  const now = String(Math.floor(Date.now() / 1000));
  const pad = (v, n) => String(v).padEnd(n, ' ');
  const head = (name, len) => Buffer.from(pad(name, 16) + pad(now, 12) + '0     ' + '0     ' + '100644  ' + pad(len, 10) + '`\n', 'latin1');
  const parts = [Buffer.from('!<arch>\n')];
  for (const [name, data] of [['debian-binary', Buffer.from('2.0\n')], ['control.tar.gz', readFileSync(`${wo}/control.tar.gz`)], ['data.tar.gz', readFileSync(`${wo}/data.tar.gz`)]]) {
    parts.push(head(name, data.length), data);
    if (data.length % 2) parts.push(Buffer.from('\n'));
  }
  writeFileSync('dist-pkg/EmbyVerse-LG.ipk', Buffer.concat(parts));
}
console.log('Paquetes listos en dist-pkg/: EmbyVerse-Samsung.wgt (firmar), EmbyVerse-LG.ipk');

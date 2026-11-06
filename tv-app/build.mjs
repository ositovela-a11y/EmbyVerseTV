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

await esbuild.build({
  entryPoints: ['src/main.tsx'],
  bundle: true,
  outfile: 'dist/app.js',
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
    __DEFAULT_HOST__: JSON.stringify(DEFAULT_HOST),
    __VERSION__: JSON.stringify(VERSION),
  },
  logLevel: 'info',
});

if (!process.argv.includes('--package')) process.exit(0);

// ---------- Paquetes ----------
rmSync('dist-pkg', { recursive: true, force: true });
mkdirSync('dist-pkg', { recursive: true });

// Samsung (Tizen): .wgt = zip con config.xml. Se firma después con el certificado de Samsung.
const tz = 'dist-pkg/tizen';
cpSync('dist', tz, { recursive: true });
writeFileSync(`${tz}/config.xml`, readFileSync('platform/tizen/config.xml', 'utf8').replaceAll('__VERSION__', VERSION));
execSync(`cd ${tz} && zip -qr ../EmbyVerse-Samsung.wgt .`);

// LG (webOS): .ipk = paquete «ar» con control.tar.gz + data.tar.gz (como ares-package).
const appId = 'com.embyverse.tv';
const wo = 'dist-pkg/webos';
const appDir = `${wo}/data/usr/palm/applications/${appId}`;
mkdirSync(appDir, { recursive: true });
cpSync('dist', appDir, { recursive: true });
writeFileSync(`${appDir}/appinfo.json`, readFileSync('platform/webos/appinfo.json', 'utf8').replaceAll('__VERSION__', VERSION));
mkdirSync(`${wo}/control`, { recursive: true });
const size = execSync(`du -sk ${wo}/data | cut -f1`).toString().trim();
writeFileSync(`${wo}/control/control`, `Package: ${appId}\nVersion: ${VERSION}\nSection: misc\nPriority: optional\nArchitecture: all\nInstalled-Size: ${size}\nMaintainer: EmbyVerse\nDescription: EmbyVerse\nwebOS-Package-Format-Version: 2\nwebOS-Packager-Version: x.y.x\n`);
writeFileSync(`${wo}/debian-binary`, '2.0\n');
execSync(`cd ${wo}/control && tar --owner=0 --group=0 -czf ../control.tar.gz ./control`);
execSync(`cd ${wo}/data && tar --owner=0 --group=0 -czf ../data.tar.gz ./usr`);
execSync(`cd ${wo} && rm -f ../EmbyVerse-LG.ipk && ar -qc ../EmbyVerse-LG.ipk debian-binary control.tar.gz data.tar.gz`);
console.log('Paquetes listos en dist-pkg/: EmbyVerse-Samsung.wgt (firmar), EmbyVerse-LG.ipk');

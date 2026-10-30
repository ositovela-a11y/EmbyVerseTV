// Empaqueta el panel con esbuild en dist/ (lo sirve el backend en la misma URL que la API).
import * as esbuild from 'esbuild';
import { cpSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs';

const watch = process.argv.includes('--watch');
// --github: versión sin servidor para GitHub Pages (web-player/studio/), publica en el repositorio.
const github = process.argv.includes('--github');
const out = github ? '../web-player/studio' : 'dist';
rmSync(out, { recursive: true, force: true });
mkdirSync(`${out}/assets`, { recursive: true });
cpSync('public', out, { recursive: true });
let html = readFileSync('index.html', 'utf8');
// En GitHub Pages la web vive en una subcarpeta: rutas relativas.
if (github) html = html.replaceAll('href="/', 'href="').replaceAll('src="/', 'src="');
writeFileSync(`${out}/index.html`, html);

const opts = {
  entryPoints: ['src/main.tsx'],
  bundle: true,
  outfile: `${out}/assets/app.js`,
  format: 'esm',
  jsx: 'automatic',
  minify: !watch,
  sourcemap: !github,
  target: ['es2022', 'chrome100', 'safari15', 'firefox100'],
  loader: { '.css': 'css' },
  external: ['/fonts/*', '/brand/*'],
  define: { 'process.env.NODE_ENV': watch ? '"development"' : '"production"', __GITHUB__: github ? 'true' : 'false' },
  logLevel: 'info',
};
if (watch) { const ctx = await esbuild.context(opts); await ctx.watch(); }
else await esbuild.build(opts);
if (github) {
  // Rutas absolutas del panel (/brand, /fonts) → relativas a la subcarpeta.
  const js = `${out}/assets/app.js`, css = `${out}/assets/app.css`;
  writeFileSync(js, readFileSync(js, 'utf8').replaceAll('"/brand/', '"brand/'));
  writeFileSync(css, readFileSync(css, 'utf8').replaceAll("url('/fonts/", "url('../fonts/").replaceAll('url("/fonts/', 'url("../fonts/').replaceAll('url(/fonts/', 'url(../fonts/'));
}

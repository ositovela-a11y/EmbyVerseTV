// Empaqueta el panel con esbuild en dist/ (lo sirve el backend en la misma URL que la API).
import * as esbuild from 'esbuild';
import { cpSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs';

const watch = process.argv.includes('--watch');
rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/assets', { recursive: true });
cpSync('public', 'dist', { recursive: true });
const html = readFileSync('index.html', 'utf8');
writeFileSync('dist/index.html', html);

const opts = {
  entryPoints: ['src/main.tsx'],
  bundle: true,
  outfile: 'dist/assets/app.js',
  format: 'esm',
  jsx: 'automatic',
  minify: !watch,
  sourcemap: true,
  target: ['es2022', 'chrome100', 'safari15', 'firefox100'],
  loader: { '.css': 'css' },
  external: ['/fonts/*', '/brand/*'],
  define: { 'process.env.NODE_ENV': watch ? '"development"' : '"production"' },
  logLevel: 'info',
};
if (watch) { const ctx = await esbuild.context(opts); await ctx.watch(); }
else await esbuild.build(opts);

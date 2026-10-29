import { existsSync, readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { RemoteConfig } from './config/schema.js';

const here = dirname(fileURLToPath(import.meta.url));
/** Raíz del paquete backend/ tanto desde src/ (tsx) como desde dist/src/. */
export const backendRoot = existsSync(resolve(here, '../package.json')) ? resolve(here, '..') : resolve(here, '../..');

export interface Env {
  port: number; host: string; dataDir: string; publicBaseUrl: string; secureCookies: boolean; trustProxy: boolean;
  allowedImageHosts: string[]; adminDist: string; defaultsPath: string; production: boolean;
}

export function loadEnv(e: NodeJS.ProcessEnv = process.env): Env {
  const production = e.NODE_ENV === 'production';
  const publicBaseUrl = (e.PUBLIC_BASE_URL ?? (production ? '' : 'http://localhost:8080')).replace(/\/$/, '');
  if (production && !publicBaseUrl.startsWith('https://')) {
    throw new Error('PUBLIC_BASE_URL debe ser una URL https:// en producción (la app sólo acepta recursos por HTTPS).');
  }
  return {
    port: Number(e.PORT ?? 8080),
    host: e.HOST ?? '0.0.0.0',
    dataDir: resolve(e.DATA_DIR ?? resolve(backendRoot, 'data')),
    publicBaseUrl,
    secureCookies: (e.COOKIE_SECURE ?? (production ? 'true' : 'false')) === 'true',
    trustProxy: e.TRUST_PROXY === 'true',
    allowedImageHosts: (e.ALLOWED_IMAGE_HOSTS ?? '').split(',').map((s) => s.trim()).filter(Boolean),
    adminDist: resolve(e.ADMIN_DIST ?? resolve(backendRoot, '../admin-web/dist')),
    defaultsPath: resolve(e.DEFAULT_CONFIG ?? resolve(backendRoot, '../shared/default-config.json')),
    production,
  };
}

export function loadDefaults(path: string): RemoteConfig {
  return JSON.parse(readFileSync(path, 'utf8')) as RemoteConfig;
}

import type { RemoteConfig } from '../../backend/src/config/schema';

export type Permission = 'config:read' | 'config:edit' | 'config:publish' | 'assets:read' | 'assets:write' | 'assets:approve' | 'audit:read' | 'users:manage';
export interface User { id: number; email: string; name: string; role: 'owner' | 'publisher' | 'editor' | 'viewer'; permissions: Permission[] }
export interface Validation { ok: boolean; errors: string[]; warnings: string[]; assetErrors: string[] }
export interface Asset { id: string; filename: string; mime: string; size: number; width: number; height: number; kind: 'image' | 'banner' | 'logo'; approved: boolean; url: string; previewUrl: string; created_at: number }
export interface VersionInfo { version: number; published_at: number; published_by: string | null; note: string | null; rollback_of: number | null }

export { ApiError } from './errors';
import { ApiError } from './errors';
import { githubApi } from './api-github';

/** Compilación para GitHub Pages (sin servidor): build.mjs --github define __GITHUB__ = true. */
declare const __GITHUB__: boolean | undefined;
export const IS_GITHUB = typeof __GITHUB__ !== 'undefined' && !!__GITHUB__;

let csrf = '';

async function call<T>(method: string, path: string, body?: unknown, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { ...(init.headers as Record<string, string>) };
  if (body !== undefined && !(body instanceof Blob)) headers['content-type'] = 'application/json';
  if (method !== 'GET' && csrf) headers['x-csrf-token'] = csrf;
  const res = await fetch(path, { method, credentials: 'same-origin', ...init, headers,
    body: body === undefined ? undefined : body instanceof Blob ? body : JSON.stringify(body) });
  const text = await res.text();
  const json = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, json?.error ?? 'error', json?.message ?? `Error ${res.status}`, json?.details);
  return json as T;
}

const serverApi = {
  async me() { const r = await call<{ user: User; csrfToken: string }>('GET', '/api/v1/auth/me'); csrf = r.csrfToken; return r.user; },
  async login(email: string, password: string) { const r = await call<{ user: User; csrfToken: string }>('POST', '/api/v1/auth/login', { email, password }); csrf = r.csrfToken; return r.user; },
  logout: () => call('POST', '/api/v1/auth/logout'),
  changePassword: (current: string, next: string) => call('POST', '/api/v1/auth/password', { current, next }),

  draft: () => call<{ config: RemoteConfig; baseVersion: number; latestVersion: number; updatedAt: number; updatedBy: string | null; validation: Validation; publicBaseUrl: string }>('GET', '/api/v1/config/draft'),
  saveDraft: (config: RemoteConfig) => call<{ validation: Validation }>('PUT', '/api/v1/config/draft', { config }),
  discardDraft: () => call('DELETE', '/api/v1/config/draft'),
  validate: (config: RemoteConfig) => call<Validation>('POST', '/api/v1/config/validate', { config }),
  publish: (note: string, force = false) => call<{ version: number; warnings: string[] }>('POST', '/api/v1/config/publish', { note, force }),
  versions: () => call<{ versions: VersionInfo[] }>('GET', '/api/v1/config/versions'),
  version: (v: number) => call<{ version: number; config: RemoteConfig }>('GET', `/api/v1/config/versions/${v}`),
  restoreDraft: (v: number) => call('POST', `/api/v1/config/versions/${v}/restore-draft`),
  rollback: (toVersion: number) => call<{ version: number }>('POST', '/api/v1/config/rollback', { toVersion }),

  assets: () => call<{ assets: Asset[] }>('GET', '/api/v1/assets'),
  upload: (file: File, kind: Asset['kind']) => call<{ asset: Asset }>('POST', `/api/v1/assets?kind=${kind}`, file,
    { headers: { 'content-type': file.type || 'application/octet-stream', 'x-filename': encodeURIComponent(file.name) } }),
  approve: (id: string, approved: boolean) => call('POST', `/api/v1/assets/${id}/approve`, { approved }),
  deleteAsset: (id: string) => call('DELETE', `/api/v1/assets/${id}`),

  audit: () => call<{ entries: { id: number; at: number; user_email: string | null; action: string; target: string | null; detail: string | null; ip: string }[] }>('GET', '/api/v1/audit?limit=200'),
  users: () => call<{ users: { id: number; email: string; name: string; role: User['role']; disabled: number; created_at: number }[] }>('GET', '/api/v1/users'),
  createUser: (u: { email: string; name: string; role: User['role']; password: string }) => call('POST', '/api/v1/users', u),
  updateUser: (id: number, patch: Record<string, unknown>) => call('PATCH', `/api/v1/users/${id}`, patch),
  deleteUser: (id: number) => call('DELETE', `/api/v1/users/${id}`),
};

export const api = (IS_GITHUB ? githubApi : serverApi) as typeof serverApi;

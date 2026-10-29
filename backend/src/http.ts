import type { IncomingMessage, ServerResponse } from 'node:http';

export class HttpError extends Error {
  constructor(public status: number, public code: string, message: string, public details?: unknown) { super(message); }
}

export interface Ctx {
  req: IncomingMessage;
  res: ServerResponse;
  url: URL;
  params: Record<string, string>;
  ip: string;
  requestId: string;
  /** Usuario autenticado del panel (si la ruta lo requiere). */
  user?: { id: number; email: string; name: string; role: Role };
  session?: { idHash: string; csrf: string };
}

export type Role = 'owner' | 'publisher' | 'editor' | 'viewer';
export type Handler = (ctx: Ctx) => Promise<void> | void;

export function sendJson(ctx: Ctx, status: number, body: unknown, headers: Record<string, string> = {}) {
  const data = JSON.stringify(body);
  ctx.res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers });
  ctx.res.end(data);
}

export async function readBody(req: IncomingMessage, maxBytes: number): Promise<Buffer> {
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > maxBytes) throw new HttpError(413, 'payload_too_large', `El cuerpo supera ${maxBytes} bytes`);
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of req) {
    total += (chunk as Buffer).length;
    if (total > maxBytes) throw new HttpError(413, 'payload_too_large', `El cuerpo supera ${maxBytes} bytes`);
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks);
}

export async function readJson<T = unknown>(ctx: Ctx, maxBytes = 512 * 1024): Promise<T> {
  const type = String(ctx.req.headers['content-type'] ?? '');
  if (!type.startsWith('application/json')) throw new HttpError(415, 'unsupported_media_type', 'Se esperaba application/json');
  const buf = await readBody(ctx.req, maxBytes);
  try { return JSON.parse(buf.toString('utf8')) as T; } catch { throw new HttpError(400, 'invalid_json', 'JSON no válido'); }
}

interface Route { method: string; pattern: RegExp; keys: string[]; handler: Handler }

/** Router mínimo con parámetros ":nombre". */
export class Router {
  private routes: Route[] = [];
  add(method: string, path: string, handler: Handler) {
    const keys: string[] = [];
    const pattern = new RegExp('^' + path.replace(/:([a-zA-Z]+)/g, (_, k: string) => { keys.push(k); return '([^/]+)'; }) + '$');
    this.routes.push({ method, pattern, keys, handler });
  }
  get(p: string, h: Handler) { this.add('GET', p, h); }
  post(p: string, h: Handler) { this.add('POST', p, h); }
  put(p: string, h: Handler) { this.add('PUT', p, h); }
  patch(p: string, h: Handler) { this.add('PATCH', p, h); }
  delete(p: string, h: Handler) { this.add('DELETE', p, h); }

  match(method: string, path: string): { handler: Handler; params: Record<string, string> } | 'method' | null {
    let pathMatched = false;
    for (const r of this.routes) {
      const m = r.pattern.exec(path);
      if (!m) continue;
      pathMatched = true;
      if (r.method !== method && !(method === 'HEAD' && r.method === 'GET')) continue;
      const params: Record<string, string> = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1] ?? ''); });
      return { handler: r.handler, params };
    }
    return pathMatched ? 'method' : null;
  }
}

/** Limitador por clave (IP, IP+email…) con ventana deslizante simple. */
export class RateLimiter {
  private hits = new Map<string, number[]>();
  constructor(private limit: number, private windowMs: number) {}
  check(key: string): boolean {
    const now = Date.now();
    const arr = (this.hits.get(key) ?? []).filter((t) => now - t < this.windowMs);
    if (arr.length >= this.limit) { this.hits.set(key, arr); return false; }
    arr.push(now);
    this.hits.set(key, arr);
    if (this.hits.size > 50_000) this.prune(now);
    return true;
  }
  reset(key: string) { this.hits.delete(key); }
  private prune(now: number) { for (const [k, v] of this.hits) if (!v.some((t) => now - t < this.windowMs)) this.hits.delete(k); }
}

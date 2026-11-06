// Funciones que faltan en los navegadores de TVs 2018–2020 (Chromium 53–69).
const g: any = typeof globalThis !== 'undefined' ? globalThis : (typeof window !== 'undefined' ? window : self);
if (typeof (g as any).globalThis === 'undefined') (g as any).globalThis = g;
if (typeof g.queueMicrotask !== 'function') g.queueMicrotask = (cb: () => void) => Promise.resolve().then(cb).catch((e) => setTimeout(() => { throw e; }));
if (!(Object as any).fromEntries) (Object as any).fromEntries = (it: Iterable<[string, unknown]>) => { const o: any = {}; for (const [k, v] of it as any) o[k] = v; return o; };
if (!(Array.prototype as any).flat) (Array.prototype as any).flat = function (this: any[], d = 1): any[] { return d > 0 ? this.reduce((a: any[], v) => a.concat(Array.isArray(v) ? (v as any).flat(d - 1) : v), []) : this.slice(); };
if (!(Array.prototype as any).flatMap) (Array.prototype as any).flatMap = function (this: any[], f: any) { return (this.map(f) as any).flat(); };
if (!(String.prototype as any).replaceAll) (String.prototype as any).replaceAll = function (this: string, a: string, b: string) { return this.split(a).join(b); };
if (!(String.prototype as any).padStart) (String.prototype as any).padStart = function (this: string, n: number, s = ' ') { let r = this; while (r.length < n) r = s + r; return r.slice(-Math.max(n, this.length)); };
if (!(Promise.prototype as any).finally) (Promise.prototype as any).finally = function (this: Promise<any>, f: () => void) { return this.then((v) => { f(); return v; }, (e) => { f(); throw e; }); };
if (!(Promise as any).allSettled) (Promise as any).allSettled = (ps: Promise<any>[]) => Promise.all(ps.map((p) => Promise.resolve(p).then((value) => ({ status: 'fulfilled', value }), (reason) => ({ status: 'rejected', reason }))));
if (!(Array.prototype as any).at) (Array.prototype as any).at = function (this: any[], i: number) { return this[i < 0 ? this.length + i : i]; };
if (!(Object as any).hasOwn) (Object as any).hasOwn = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);
if (typeof g.AbortController === 'undefined') {
  g.AbortController = class { signal: any = { aborted: false, addEventListener() {}, removeEventListener() {} }; abort() { this.signal.aborted = true; } };
}
export {};

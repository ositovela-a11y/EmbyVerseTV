// Si GitHub (ositovela-a11y.github.io/EmbyVerseTV) no responde o ya no tiene el archivo, se pide lo mismo a
// eversetv.com, que guarda su propia copia del diseño, las listas (bloqueos, activaciones) y la app de TV.
const PRIMARY = 'https://ositovela-a11y.github.io/EmbyVerseTV/';
const MIRROR = 'https://eversetv.com/';

export function alternate(url: string): string | null {
  return url.startsWith(PRIMARY) ? MIRROR + url.slice(PRIMARY.length) : null;
}

export function installMirror(): void {
  const w = window as any;
  if (!w.fetch || w.__evMirror) return;
  const orig: typeof fetch = w.fetch.bind(w);
  w.__evMirror = true;
  w.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : (input as Request).url;
    const alt = (!init?.method || init.method === 'GET') ? alternate(url) : null;
    if (!alt) return orig(input as any, init);
    try {
      const r = await orig(input as any, init);
      if (r.status < 500 && r.status !== 404) return r;
      try { const r2 = await orig(alt, init); return r2.ok ? r2 : r; } catch { return r; }
    } catch (e) {
      return orig(alt, init).catch(() => { throw e; });
    }
  };
}

// Diseño publicado desde tu panel (el mismo app.json que usa la app Android): colores, logo, textos,
// mensajes, suspensión de clientes, servidor del proveedor, fondos de temporada, evento destacado…
export type Palette = Record<string, string>;
export interface Message { id: string; title: string; body: string; startsAt?: string | null; endsAt?: string | null }
export interface Banner { id: string; title: string; subtitle?: string; imageUrl?: string | null; startsAt?: string | null; endsAt?: string | null }
export interface RemoteConfig {
  version: number;
  brand: { appName: string; tagline?: string; logo?: { variant?: string; url?: string } };
  theme: { palette: Palette; shape?: { cardRadius?: number; buttonRadius?: number }; background?: { gradientStart?: string; gradientEnd?: string } };
  texts: Record<string, string>;
  messages: Message[];
  banners: Banner[];
}

const FALLBACK: RemoteConfig = {
  version: 0,
  brand: { appName: 'EmbyVerse', tagline: 'Tu universo de entretenimiento' },
  theme: { palette: { primary: '#7BE52F', secondary: '#1E6BFF', accent: '#22D3EE', background: '#03070F', surface: '#0A1324', surfaceVariant: '#13213B',
    onBackground: '#EAF2FF', textSecondary: '#8FA3BF', buttonText: '#04110A', focusRing: '#22D3EE', error: '#FF5A6E' } },
  texts: {}, messages: [], banners: [],
};

declare const __CONFIG_URL__: string;
const KEY = 'ev.config';

export function cachedConfig(): RemoteConfig {
  try { const v = localStorage.getItem(KEY); if (v) return { ...FALLBACK, ...JSON.parse(v) }; } catch { /* */ }
  return FALLBACK;
}

export async function fetchConfig(): Promise<RemoteConfig | null> {
  try {
    const r = await fetch(`${__CONFIG_URL__}?t=${Math.floor(Date.now() / 60000)}`, { cache: 'no-store' });
    if (!r.ok) return null;
    const j = await r.json();
    const cfg: RemoteConfig = {
      version: j.version ?? 0,
      brand: j.brand ?? FALLBACK.brand,
      theme: { palette: { ...FALLBACK.theme.palette, ...(j.theme?.palette ?? {}) }, shape: j.theme?.shape, background: j.theme?.background },
      texts: j.texts ?? {}, messages: Array.isArray(j.messages) ? j.messages : [], banners: Array.isArray(j.banners) ? j.banners : [],
    };
    try { localStorage.setItem(KEY, JSON.stringify(cfg)); } catch { /* */ }
    return cfg;
  } catch { return null; }
}

const iso = (s?: string | null) => (s ? Date.parse(s) : NaN);
export const isActive = (m: { startsAt?: string | null; endsAt?: string | null }, now = Date.now()) =>
  !(iso(m.startsAt) > now) && !(iso(m.endsAt) <= now);

/** Colores del panel como variables CSS. */
export function applyTheme(cfg: RemoteConfig, overrides?: Partial<Palette>) {
  const p = { ...cfg.theme.palette, ...(overrides ?? {}) };
  const s = document.documentElement.style;
  const set = (k: string, v?: string) => v && s.setProperty(k, v);
  set('--primary', p.primary); set('--secondary', p.secondary); set('--accent', p.accent);
  set('--bg', p.background); set('--surface', p.surface); set('--surface2', p.surfaceVariant);
  set('--text', p.onBackground); set('--muted', p.textSecondary); set('--btn-text', p.buttonText ?? p.onPrimary); set('--focus', p.focusRing ?? p.accent);
  set('--error', p.error);
  set('--bg1', cfg.theme.background?.gradientStart ?? p.background); set('--bg2', cfg.theme.background?.gradientEnd ?? p.surface);
  if (cfg.theme.shape?.cardRadius != null) s.setProperty('--radius', `${cfg.theme.shape.cardRadius}px`);
}

/** Servidor del proveedor: el del panel, el de fábrica, o ninguno («el cliente escribe la URL»). */
declare const __DEFAULT_HOST__: string;
export function providerHost(cfg: RemoteConfig): string | null {
  if ((cfg.texts['provider.askHost'] ?? '').trim() === '1') return null;
  return (cfg.texts['provider.host'] ?? '').trim() || __DEFAULT_HOST__;
}

/** Suspensión remota (huella SHA-256 recortada del usuario, igual que en Android). */
export async function isBlocked(cfg: RemoteConfig, username: string): Promise<boolean> {
  const keys = Object.keys(cfg.texts).filter((k) => k === 'blocked.users' || k.startsWith('blocked.users.'));
  const set = new Set(keys.flatMap((k) => cfg.texts[k].split(/[,\s]+/)).map((x) => x.trim().toLowerCase()).filter((x) => x.length >= 12));
  if (!set.size) return false;
  return set.has(await evHash(username));
}

export async function evHash(user: string): Promise<string> { return hash16('ev:' + user.trim().toLowerCase()); }

/** SHA-256 (16 primeros caracteres hex) de un texto. */
export async function hash16(text: string): Promise<string> {
  const data = new TextEncoder().encode(text);
  const subtle = (crypto as any)?.subtle;
  if (subtle) {
    const d = await subtle.digest('SHA-256', data);
    return Array.from(new Uint8Array(d)).map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
  }
  return sha256Hex(data).slice(0, 16);
}

// SHA-256 en JS para TVs sin crypto.subtle (páginas sin https).
function sha256Hex(bytes: Uint8Array): string {
  const K = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
  const H = [0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19];
  const l = bytes.length; const withPad = ((l + 9 + 63) >> 6) << 6; const m = new Uint8Array(withPad); m.set(bytes); m[l] = 0x80;
  const dv = new DataView(m.buffer); dv.setUint32(withPad - 4, l * 8);
  const w = new Uint32Array(64);
  for (let o = 0; o < withPad; o += 64) {
    for (let i = 0; i < 16; i++) w[i] = dv.getUint32(o + i * 4);
    for (let i = 16; i < 64; i++) { const a = w[i - 15], b = w[i - 2]; const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3); const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10); w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0; }
    let [a, b, c, d, e, f, g, h] = H;
    for (let i = 0; i < 64; i++) {
      const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7)); const ch = (e & f) ^ (~e & g); const t1 = (h + S1 + ch + K[i] + w[i]) | 0;
      const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10)); const mj = (a & b) ^ (a & c) ^ (b & c); const t2 = (S0 + mj) | 0;
      h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
    }
    H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0; H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
  }
  return H.map((x) => (x >>> 0).toString(16).padStart(8, '0')).join('');
}

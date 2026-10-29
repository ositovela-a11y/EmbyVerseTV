/**
 * Validación de la configuración visual remota, esquema v1.
 *
 * MISMAS REGLAS que android-app/core/.../RemoteConfigParser.kt. Ambas implementaciones se prueban
 * contra los mismos fixtures (tests/fixtures/config). Si cambias una regla aquí, cámbiala allí.
 *
 *  - Campos desconocidos: se ignoran (compatibilidad hacia delante).
 *  - Campos ausentes: se completan con la configuración base.
 *  - Valores presentes pero inválidos: la configuración entera es inválida.
 *  - schemaVersion > 1: inválida para esta versión.
 *  - Tipos de sección desconocidos: aviso (la app los omite).
 *
 * Este módulo no depende de Node: lo usan el backend y el panel (vista previa en el navegador).
 */

export const SUPPORTED_SCHEMA = 1;
export const MAX_CONFIG_BYTES = 256 * 1024;

export const PALETTE_KEYS = [
  'primary', 'secondary', 'accent', 'background', 'surface', 'surfaceVariant', 'onBackground',
  'onSurface', 'textSecondary', 'onPrimary', 'buttonBackground', 'buttonText', 'focusRing', 'error',
] as const;
export type PaletteKey = (typeof PALETTE_KEYS)[number];
export type Palette = Record<PaletteKey, string>;

export const SECTION_TYPES = [
  'hero_banner', 'promo_banners', 'continue_watching', 'live_tv', 'movies', 'series',
  'recently_added', 'favorites', 'recommended',
] as const;
export type SectionType = (typeof SECTION_TYPES)[number];

export const LOGO_VARIANTS = ['bundled_full', 'bundled_planet', 'asset'] as const;
export const BACKGROUND_STYLES = ['solid', 'gradient', 'nebula'] as const;
export const FONT_FAMILIES = ['system', 'inter', 'poppins'] as const;
export const BUTTON_STYLES = ['filled', 'tonal', 'outlined'] as const;
export const NAV_STYLES = ['bottom_bar', 'side_rail', 'top_tabs'] as const;
export const LAYOUTS = ['hero', 'carousel', 'grid'] as const;
export const ASPECTS = ['poster', 'landscape', 'square'] as const;
export const SIZES = ['s', 'm', 'l'] as const;
export const PLACEMENTS = ['hero', 'promo'] as const;
/** Rutas internas autorizadas. Los banners nunca enlazan a URLs externas. */
export const ROUTES = ['home', 'live', 'movies', 'series', 'search', 'favorites', 'settings', 'sources'] as const;

export const LIMITS = {
  appName: [1, 40], tagline: [0, 80], themeName: [1, 40], sectionTitle: [0, 60], sectionSubtitle: [0, 100],
  bannerTitle: [1, 80], bannerSubtitle: [0, 160], text: 500, texts: 100, themes: 12, sections: [1, 30], banners: 20,
  accentChoices: 12, cardRadius: [0, 32], buttonRadius: [0, 32], bannerRadius: [0, 40], screenPadding: [8, 48],
  sectionGap: [8, 64], cardGap: [4, 32], fontScale: [0.8, 1.4], glowIntensity: [0, 1], maxItems: [1, 100],
} as const;

export interface SectionSpec {
  id: string;
  type: string;
  enabled: boolean;
  title: string;
  subtitle?: string | null;
  layout: (typeof LAYOUTS)[number];
  card: { aspect: (typeof ASPECTS)[number]; size: (typeof SIZES)[number] };
  maxItems: number;
}

export interface Banner {
  id: string;
  placement: (typeof PLACEMENTS)[number];
  title: string;
  subtitle?: string | null;
  imageUrl?: string | null;
  action: { route: (typeof ROUTES)[number] | null };
  startsAt?: string | null;
  endsAt?: string | null;
}

export interface RemoteConfig {
  schemaVersion: number;
  version: number;
  publishedAt?: string | null;
  minAppVersionCode: number;
  brand: { appName: string; logo: { variant: (typeof LOGO_VARIANTS)[number]; url?: string | null }; tagline?: string | null };
  theme: {
    palette: Palette;
    background: { style: (typeof BACKGROUND_STYLES)[number]; gradientStart: string; gradientEnd: string };
    typography: { family: (typeof FONT_FAMILIES)[number]; scale: number };
    shape: { cardRadius: number; buttonRadius: number; bannerRadius: number };
    spacing: { screenPadding: number; sectionGap: number; cardGap: number };
    glow: { enabled: boolean; intensity: number };
    buttonStyle: (typeof BUTTON_STYLES)[number];
    navigation: { style: (typeof NAV_STYLES)[number]; showLabels: boolean };
  };
  themes: { id: string; name: string; palette: Partial<Palette> }[];
  userCustomization: {
    allowThemeSelection: boolean;
    allowAccentColor: boolean;
    accentChoices: string[];
    allowCardSize: boolean;
    allowHideSections: boolean;
    allowReorderSections: boolean;
    lockedSections: string[];
  };
  home: { sections: SectionSpec[] };
  banners: Banner[];
  texts: Record<string, string>;
}

export interface ParseResult {
  ok: boolean;
  config?: RemoteConfig;
  errors: string[];
  warnings: string[];
}

const COLOR = /^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$/;
const ID = /^[a-z0-9_-]{1,32}$/;
const TEXT_KEY = /^[A-Za-z0-9_.]{1,64}$/;
const TYPE_TOKEN = /^[a-z_]{1,40}$/;
const ISO = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,9})?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$/;

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
const isObj = (v: unknown): v is Record<string, Json> => typeof v === 'object' && v !== null && !Array.isArray(v);
const has = (o: Record<string, Json>, k: string) => Object.prototype.hasOwnProperty.call(o, k);

/** Fecha ISO-8601 → epoch en segundos (mismo algoritmo que la app). */
export function parseIso(s: string): number | null {
  const m = ISO.exec(s.trim());
  if (!m) return null;
  const y = +m[1]!, mo = +m[2]!, d = +m[3]!, h = +(m[4] ?? 0), mi = +(m[5] ?? 0), sec = +(m[6] ?? 0);
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || sec > 59) return null;
  let epoch = Date.UTC(y, mo - 1, d, h, mi, sec) / 1000;
  const tz = m[7];
  if (tz && tz !== 'Z') {
    const digits = tz.slice(1).replace(':', '');
    const off = +digits.slice(0, 2) * 3600 + +digits.slice(2, 4) * 60;
    epoch -= tz[0] === '+' ? off : -off;
  }
  return epoch;
}

export function isHttpsUrl(s: string): boolean {
  if (!s.startsWith('https://') || /\s/.test(s) || s.length > 500) return false;
  try { return new URL(s).hostname.length > 0; } catch { return false; }
}

class V {
  errors: string[] = [];
  warnings: string[] = [];
  err(path: string, msg: string) { if (this.errors.length < 50) this.errors.push(`${path}: ${msg}`); }

  obj(o: Record<string, Json>, k: string, path: string): Record<string, Json> | undefined {
    const v = o[k];
    if (v === undefined || v === null) return undefined;
    if (isObj(v)) return v;
    this.err(`${path}.${k}`, 'debe ser un objeto');
    return undefined;
  }
  arr(o: Record<string, Json>, k: string, path: string): Json[] | undefined {
    const v = o[k];
    if (v === undefined || v === null) return undefined;
    if (Array.isArray(v)) return v;
    this.err(`${path}.${k}`, 'debe ser una lista');
    return undefined;
  }
  str(o: Record<string, Json>, k: string, path: string, min: number, max: number, nullable = false): string | undefined {
    if (!has(o, k)) return undefined;
    const v = o[k];
    if (v === null) { if (!nullable) this.err(`${path}.${k}`, 'no puede ser null'); return undefined; }
    if (typeof v !== 'string') { this.err(`${path}.${k}`, 'debe ser texto'); return undefined; }
    if (v.length < min || v.length > max) { this.err(`${path}.${k}`, `longitud debe estar entre ${min} y ${max}`); return undefined; }
    // eslint-disable-next-line no-control-regex
    if (/[\u0000-\u0009\u000B-\u001F]/.test(v)) { this.err(`${path}.${k}`, 'contiene caracteres de control'); return undefined; }
    return v;
  }
  int(o: Record<string, Json>, k: string, path: string, min: number, max: number): number | undefined {
    if (!has(o, k)) return undefined;
    const v = o[k];
    if (typeof v !== 'number' || !Number.isInteger(v)) { this.err(`${path}.${k}`, 'debe ser un entero'); return undefined; }
    if (v < min || v > max) { this.err(`${path}.${k}`, `debe estar entre ${min} y ${max}`); return undefined; }
    return v;
  }
  num(o: Record<string, Json>, k: string, path: string, min: number, max: number): number | undefined {
    if (!has(o, k)) return undefined;
    const v = o[k];
    if (typeof v !== 'number' || !Number.isFinite(v)) { this.err(`${path}.${k}`, 'debe ser un número'); return undefined; }
    if (v < min || v > max) { this.err(`${path}.${k}`, `debe estar entre ${min} y ${max}`); return undefined; }
    return v;
  }
  bool(o: Record<string, Json>, k: string, path: string): boolean | undefined {
    if (!has(o, k)) return undefined;
    const v = o[k];
    if (typeof v !== 'boolean') { this.err(`${path}.${k}`, 'debe ser true o false'); return undefined; }
    return v;
  }
  color(o: Record<string, Json>, k: string, path: string): string | undefined {
    const s = this.str(o, k, path, 4, 9);
    if (s === undefined) return undefined;
    if (!COLOR.test(s)) { this.err(`${path}.${k}`, 'color no válido (usa #RRGGBB o #AARRGGBB)'); return undefined; }
    return s.toUpperCase();
  }
  enumOf<T extends string>(o: Record<string, Json>, k: string, path: string, values: readonly T[]): T | undefined {
    const s = this.str(o, k, path, 1, 40);
    if (s === undefined) return undefined;
    if (!(values as readonly string[]).includes(s)) { this.err(`${path}.${k}`, `valor no permitido '${s}' (permitidos: ${values.join(', ')})`); return undefined; }
    return s as T;
  }
  https(o: Record<string, Json>, k: string, path: string): string | null | undefined {
    if (!has(o, k)) return undefined;
    if (o[k] === null) return null;
    const s = this.str(o, k, path, 9, 500, true);
    if (s === undefined) return undefined;
    if (!isHttpsUrl(s)) { this.err(`${path}.${k}`, 'debe ser una URL https válida'); return undefined; }
    return s;
  }
  iso(o: Record<string, Json>, k: string, path: string): { raw: string; epoch: number } | null | undefined {
    if (!has(o, k)) return undefined;
    if (o[k] === null) return null;
    const s = this.str(o, k, path, 10, 40, true);
    if (s === undefined) return undefined;
    const e = parseIso(s);
    if (e === null) { this.err(`${path}.${k}`, 'fecha ISO-8601 no válida'); return undefined; }
    return { raw: s, epoch: e };
  }
  palette(o: Record<string, Json> | undefined, path: string): Partial<Palette> {
    const out: Partial<Palette> = {};
    if (!o) return out;
    for (const k of PALETTE_KEYS) { const c = this.color(o, k, path); if (c) out[k] = c; }
    for (const k of Object.keys(o)) if (!(PALETTE_KEYS as readonly string[]).includes(k)) this.warnings.push(`${path}.${k}: clave de color desconocida, ignorada`);
    return out;
  }
}

/** Valida `input` (texto JSON u objeto) y devuelve la configuración efectiva completada con `base`. */
export function parseConfig(input: unknown, base: RemoteConfig): ParseResult {
  let root: unknown = input;
  if (typeof input === 'string') {
    if (new TextEncoder().encode(input).length > MAX_CONFIG_BYTES) return { ok: false, errors: [`La configuración supera ${MAX_CONFIG_BYTES / 1024} KB`], warnings: [] };
    try { root = JSON.parse(input); } catch { return { ok: false, errors: ['JSON no válido'], warnings: [] }; }
  } else if (new TextEncoder().encode(JSON.stringify(input) ?? '').length > MAX_CONFIG_BYTES) {
    return { ok: false, errors: [`La configuración supera ${MAX_CONFIG_BYTES / 1024} KB`], warnings: [] };
  }
  if (!isObj(root)) return { ok: false, errors: ['La raíz debe ser un objeto'], warnings: [] };
  const r = root;
  const v = new V();
  const b: RemoteConfig = structuredClone(base);

  if (!has(r, 'schemaVersion')) v.err('$.schemaVersion', 'es obligatorio');
  const schema = v.int(r, 'schemaVersion', '$', 1, 1000);
  if (schema !== undefined && schema > SUPPORTED_SCHEMA) {
    v.err('$.schemaVersion', `versión ${schema} no soportada (máx. ${SUPPORTED_SCHEMA})`);
    return { ok: false, errors: v.errors, warnings: v.warnings };
  }
  const version = v.int(r, 'version', '$', 0, 2 ** 31 - 1) ?? b.version;
  const publishedAt = v.str(r, 'publishedAt', '$', 10, 40, true) ?? b.publishedAt ?? null;
  const minApp = v.int(r, 'minAppVersionCode', '$', 1, 2 ** 31 - 1) ?? b.minAppVersionCode;

  // brand
  const brand = structuredClone(b.brand);
  const br = v.obj(r, 'brand', '$');
  if (br) {
    const name = v.str(br, 'appName', '$.brand', 1, 40);
    if (name !== undefined) brand.appName = name;
    if (has(br, 'tagline')) brand.tagline = v.str(br, 'tagline', '$.brand', 0, 80, true) ?? null;
    const lg = v.obj(br, 'logo', '$.brand');
    if (lg) {
      const variant = v.enumOf(lg, 'variant', '$.brand.logo', LOGO_VARIANTS);
      if (variant) brand.logo.variant = variant;
      const url = v.https(lg, 'url', '$.brand.logo');
      brand.logo.url = url ?? null;
      if (brand.logo.variant === 'asset' && !brand.logo.url) v.err('$.brand.logo.url', 'obligatorio cuando variant = asset');
      if (brand.logo.variant !== 'asset') delete brand.logo.url;
    }
  }

  // theme
  const theme = structuredClone(b.theme);
  const th = v.obj(r, 'theme', '$');
  if (th) {
    const p = '$.theme';
    Object.assign(theme.palette, v.palette(v.obj(th, 'palette', p), `${p}.palette`));
    const bg = v.obj(th, 'background', p);
    if (bg) {
      const st = v.enumOf(bg, 'style', `${p}.background`, BACKGROUND_STYLES); if (st) theme.background.style = st;
      const gs = v.color(bg, 'gradientStart', `${p}.background`); if (gs) theme.background.gradientStart = gs;
      const ge = v.color(bg, 'gradientEnd', `${p}.background`); if (ge) theme.background.gradientEnd = ge;
    }
    const ty = v.obj(th, 'typography', p);
    if (ty) {
      const f = v.enumOf(ty, 'family', `${p}.typography`, FONT_FAMILIES); if (f) theme.typography.family = f;
      const s = v.num(ty, 'scale', `${p}.typography`, 0.8, 1.4); if (s !== undefined) theme.typography.scale = s;
    }
    const sh = v.obj(th, 'shape', p);
    if (sh) {
      const a = v.int(sh, 'cardRadius', `${p}.shape`, 0, 32); if (a !== undefined) theme.shape.cardRadius = a;
      const bt = v.int(sh, 'buttonRadius', `${p}.shape`, 0, 32); if (bt !== undefined) theme.shape.buttonRadius = bt;
      const bn = v.int(sh, 'bannerRadius', `${p}.shape`, 0, 40); if (bn !== undefined) theme.shape.bannerRadius = bn;
    }
    const sp = v.obj(th, 'spacing', p);
    if (sp) {
      const a = v.int(sp, 'screenPadding', `${p}.spacing`, 8, 48); if (a !== undefined) theme.spacing.screenPadding = a;
      const bb = v.int(sp, 'sectionGap', `${p}.spacing`, 8, 64); if (bb !== undefined) theme.spacing.sectionGap = bb;
      const c = v.int(sp, 'cardGap', `${p}.spacing`, 4, 32); if (c !== undefined) theme.spacing.cardGap = c;
    }
    const gl = v.obj(th, 'glow', p);
    if (gl) {
      const e = v.bool(gl, 'enabled', `${p}.glow`); if (e !== undefined) theme.glow.enabled = e;
      const i = v.num(gl, 'intensity', `${p}.glow`, 0, 1); if (i !== undefined) theme.glow.intensity = i;
    }
    const bs = v.enumOf(th, 'buttonStyle', p, BUTTON_STYLES); if (bs) theme.buttonStyle = bs;
    const nv = v.obj(th, 'navigation', p);
    if (nv) {
      const s = v.enumOf(nv, 'style', `${p}.navigation`, NAV_STYLES); if (s) theme.navigation.style = s;
      const l = v.bool(nv, 'showLabels', `${p}.navigation`); if (l !== undefined) theme.navigation.showLabels = l;
    }
  }

  // themes
  let themes = b.themes;
  const tha = v.arr(r, 'themes', '$');
  if (tha) {
    if (tha.length > 12) v.err('$.themes', 'máximo 12 temas');
    const ids = new Set<string>();
    themes = [];
    tha.forEach((el, i) => {
      const p = `$.themes[${i}]`;
      if (!isObj(el)) { v.err(p, 'debe ser un objeto'); return; }
      const id = v.str(el, 'id', p, 1, 32);
      if (id === undefined) { if (!has(el, 'id')) v.err(`${p}.id`, 'es obligatorio'); return; }
      if (!ID.test(id)) v.err(`${p}.id`, 'solo a-z, 0-9, _ y -');
      if (ids.has(id)) v.err(`${p}.id`, `duplicado '${id}'`); ids.add(id);
      let name = v.str(el, 'name', p, 1, 40);
      if (name === undefined) { if (!has(el, 'name')) v.err(`${p}.name`, 'es obligatorio'); name = id; }
      themes.push({ id, name, palette: v.palette(v.obj(el, 'palette', p), `${p}.palette`) });
    });
  }

  // home.sections
  let sections = b.home.sections;
  const home = v.obj(r, 'home', '$');
  const sa = home ? v.arr(home, 'sections', '$.home') : undefined;
  if (sa) {
    if (sa.length < 1 || sa.length > 30) v.err('$.home.sections', 'debe tener entre 1 y 30 secciones');
    const ids = new Set<string>();
    sections = [];
    sa.forEach((el, i) => {
      const p = `$.home.sections[${i}]`;
      if (!isObj(el)) { v.err(p, 'debe ser un objeto'); return; }
      const id = v.str(el, 'id', p, 1, 32);
      const type = v.str(el, 'type', p, 1, 40);
      if (id === undefined || type === undefined) { v.err(p, 'id y type son obligatorios'); return; }
      if (!ID.test(id)) v.err(`${p}.id`, 'solo a-z, 0-9, _ y -');
      if (ids.has(id)) v.err(`${p}.id`, `duplicado '${id}'`); ids.add(id);
      if (!TYPE_TOKEN.test(type)) v.err(`${p}.type`, 'formato no válido');
      const known = (SECTION_TYPES as readonly string[]).includes(type);
      if (!known) v.warnings.push(`${p}.type: tipo '${type}' desconocido para esta versión de la app; se omitirá`);
      const layout = v.enumOf(el, 'layout', p, LAYOUTS) ?? (type === 'hero_banner' ? 'hero' : 'carousel');
      if (layout === 'hero' && known && type !== 'hero_banner') v.err(`${p}.layout`, "'hero' solo se permite en secciones hero_banner");
      const card: SectionSpec['card'] = { aspect: 'poster', size: 'm' };
      const c = v.obj(el, 'card', p);
      if (c) {
        const a = v.enumOf(c, 'aspect', `${p}.card`, ASPECTS); if (a) card.aspect = a;
        const s = v.enumOf(c, 'size', `${p}.card`, SIZES); if (s) card.size = s;
      }
      const subtitle = v.str(el, 'subtitle', p, 0, 100, true);
      sections.push({
        id, type, enabled: v.bool(el, 'enabled', p) ?? true, title: v.str(el, 'title', p, 0, 60) ?? '',
        ...(subtitle ? { subtitle } : {}), layout, card, maxItems: v.int(el, 'maxItems', p, 1, 100) ?? 20,
      });
    });
  }
  const sectionIds = new Set(sections.map((s) => s.id));

  // userCustomization
  const uc = structuredClone(b.userCustomization);
  const u = v.obj(r, 'userCustomization', '$');
  if (u) {
    const p = '$.userCustomization';
    for (const k of ['allowThemeSelection', 'allowAccentColor', 'allowCardSize', 'allowHideSections', 'allowReorderSections'] as const) {
      const x = v.bool(u, k, p); if (x !== undefined) uc[k] = x;
    }
    const ac = v.arr(u, 'accentChoices', p);
    if (ac) {
      if (ac.length > 12) v.err(`${p}.accentChoices`, 'máximo 12 colores');
      uc.accentChoices = [];
      ac.forEach((e, i) => { if (typeof e === 'string' && COLOR.test(e)) uc.accentChoices.push(e.toUpperCase()); else v.err(`${p}.accentChoices[${i}]`, 'color no válido'); });
    }
    const ls = v.arr(u, 'lockedSections', p);
    if (ls) {
      uc.lockedSections = [];
      ls.forEach((e, i) => {
        if (typeof e !== 'string') v.err(`${p}.lockedSections[${i}]`, 'debe ser texto');
        else if (!sectionIds.has(e)) v.err(`${p}.lockedSections[${i}]`, `la sección '${e}' no existe`);
        else uc.lockedSections.push(e);
      });
    } else uc.lockedSections = uc.lockedSections.filter((x) => sectionIds.has(x));
  } else uc.lockedSections = uc.lockedSections.filter((x) => sectionIds.has(x));

  // banners
  let banners = b.banners;
  const ba = v.arr(r, 'banners', '$');
  if (ba) {
    if (ba.length > 20) v.err('$.banners', 'máximo 20 banners');
    const ids = new Set<string>();
    banners = [];
    ba.forEach((el, i) => {
      const p = `$.banners[${i}]`;
      if (!isObj(el)) { v.err(p, 'debe ser un objeto'); return; }
      const id = v.str(el, 'id', p, 1, 32);
      if (id === undefined) { v.err(`${p}.id`, 'es obligatorio'); return; }
      if (!ID.test(id)) v.err(`${p}.id`, 'solo a-z, 0-9, _ y -');
      if (ids.has(id)) v.err(`${p}.id`, `duplicado '${id}'`); ids.add(id);
      const placement = v.enumOf(el, 'placement', p, PLACEMENTS) ?? 'promo';
      let title = v.str(el, 'title', p, 1, 80);
      if (title === undefined) { if (!has(el, 'title')) v.err(`${p}.title`, 'es obligatorio'); title = ''; }
      let route: Banner['action']['route'] = null;
      const act = v.obj(el, 'action', p);
      if (act) {
        const rs = v.str(act, 'route', `${p}.action`, 1, 32, true);
        if (rs !== undefined) {
          if ((ROUTES as readonly string[]).includes(rs)) route = rs as Banner['action']['route'];
          else v.err(`${p}.action.route`, `ruta interna no autorizada '${rs}'`);
        }
      }
      const s = v.iso(el, 'startsAt', p), e = v.iso(el, 'endsAt', p);
      if (s && e && e.epoch <= s.epoch) v.err(`${p}.endsAt`, 'debe ser posterior a startsAt');
      const sub = v.str(el, 'subtitle', p, 0, 160, true);
      banners.push({ id, placement, title, subtitle: sub ? sub : null, imageUrl: v.https(el, 'imageUrl', p) ?? null,
        action: { route }, startsAt: s?.raw ?? null, endsAt: e?.raw ?? null });
    });
  }

  // texts
  let texts = b.texts;
  const tx = v.obj(r, 'texts', '$');
  if (tx) {
    const entries = Object.entries(tx);
    if (entries.length > 100) v.err('$.texts', 'máximo 100 textos');
    texts = {};
    for (const [k, val] of entries) {
      if (!TEXT_KEY.test(k)) { v.err(`$.texts.${k}`, 'clave no válida'); continue; }
      if (typeof val !== 'string' || val.length > 500) { v.err(`$.texts.${k}`, 'debe ser texto de hasta 500 caracteres'); continue; }
      texts[k] = val;
    }
  }

  if (v.errors.length) return { ok: false, errors: v.errors, warnings: v.warnings };
  return {
    ok: true, errors: [], warnings: v.warnings,
    config: { schemaVersion: schema ?? 1, version, publishedAt, minAppVersionCode: minApp, brand, theme, themes,
      userCustomization: uc, home: { sections }, banners, texts },
  };
}

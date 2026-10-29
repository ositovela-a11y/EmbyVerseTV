/**
 * Combinación configuración del administrador + preferencias de un usuario.
 * MISMA LÓGICA que android-app/core/.../ConfigResolver.kt (la vista previa del panel la usa).
 */
import { parseIso, type Banner, type Palette, type RemoteConfig, type SectionSpec } from './schema.js';

export interface UserAppearance {
  themeId?: string | null;
  accentColor?: string | null;
  cardSize?: 's' | 'm' | 'l' | null;
  hiddenSections?: string[];
  sectionOrder?: string[];
}

export function readableOn(hex: string): string {
  const h = hex.length === 9 ? hex.slice(3) : hex.slice(1);
  const ch = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); };
  const l = 0.2126 * ch(parseInt(h.slice(0, 2), 16)) + 0.7152 * ch(parseInt(h.slice(2, 4), 16)) + 0.0722 * ch(parseInt(h.slice(4, 6), 16));
  return l > 0.179 ? '#04110A' : '#FFFFFF';
}

export function resolvePalette(cfg: RemoteConfig, prefs: UserAppearance): Palette {
  const uc = cfg.userCustomization;
  let p: Palette = { ...cfg.theme.palette };
  if (uc.allowThemeSelection && prefs.themeId) {
    const t = cfg.themes.find((x) => x.id === prefs.themeId);
    if (t) p = { ...p, ...t.palette } as Palette;
  }
  const a = prefs.accentColor?.toUpperCase();
  if (uc.allowAccentColor && a && uc.accentChoices.map((x) => x.toUpperCase()).includes(a)) {
    const on = readableOn(a);
    p = { ...p, primary: a, buttonBackground: a, focusRing: a, onPrimary: on, buttonText: on };
  }
  return p;
}

const KNOWN = new Set(['hero_banner', 'promo_banners', 'continue_watching', 'live_tv', 'movies', 'series', 'recently_added', 'favorites', 'recommended']);

export function resolveSections(cfg: RemoteConfig, prefs: UserAppearance): SectionSpec[] {
  const uc = cfg.userCustomization;
  const admin = cfg.home.sections.filter((s) => s.enabled && KNOWN.has(s.type));
  const locked = new Set(uc.lockedSections);
  const hidden = new Set(prefs.hiddenSections ?? []);
  const visible = admin.filter((s) => locked.has(s.id) || !uc.allowHideSections || !hidden.has(s.id));
  let ordered = visible;
  const order = prefs.sectionOrder ?? [];
  if (uc.allowReorderSections && order.length) {
    const rank = new Map(order.map((id, i) => [id, i] as const));
    const movable = visible.filter((s) => !locked.has(s.id))
      .sort((x, y) => (rank.get(x.id) ?? Infinity) - (rank.get(y.id) ?? Infinity) || admin.indexOf(x) - admin.indexOf(y));
    let i = 0;
    ordered = visible.map((s) => (locked.has(s.id) ? s : movable[i++]!));
  }
  if (uc.allowCardSize && prefs.cardSize) {
    const size = prefs.cardSize;
    ordered = ordered.map((s) => (s.layout === 'hero' ? s : { ...s, card: { ...s.card, size } }));
  }
  return ordered;
}

export function activeBanners(cfg: RemoteConfig, placement: Banner['placement'], nowEpoch: number): Banner[] {
  return cfg.banners.filter((b) => {
    if (b.placement !== placement) return false;
    const s = b.startsAt ? parseIso(b.startsAt) : null;
    const e = b.endsAt ? parseIso(b.endsAt) : null;
    return (s === null || nowEpoch >= s) && (e === null || nowEpoch < e);
  });
}

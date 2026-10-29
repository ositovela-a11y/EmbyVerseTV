import type { CSSProperties, ReactNode } from 'react';
import { activeBanners, resolvePalette, resolveSections, type UserAppearance } from '../../../backend/src/config/resolve';
import type { Banner, RemoteConfig, SectionSpec } from '../../../backend/src/config/schema';
import { css } from '../store';

export type Device = 'phone' | 'tablet' | 'tv';
export const DEVICES: Record<Device, { w: number; h: number; label: string }> = {
  phone: { w: 392, h: 820, label: 'Teléfono' },
  tablet: { w: 900, h: 640, label: 'Tableta' },
  tv: { w: 1280, h: 720, label: 'Android TV' },
};

// Contenido de muestra inventado (sin imágenes con derechos): pósters generados con degradados.
const MOVIES = ['Órbita silenciosa', 'La ciudad de cristal', 'Mareas', 'Último tren a Vega', 'Código Aurora', 'Los faros', 'Arena roja', 'Nébula', 'El taller del reloj', 'Verano sin mapa'];
const SERIES = ['Distrito Norte', 'Estación Polar', 'Los Alquimistas', 'Señal perdida', 'Casa Mirador', 'Capitanes'];
const CHANNELS = ['Noticias 24', 'Deportes Uno', 'Cine Clásico', 'Documental HD', 'Infantil', 'Música Live', 'Cocina+', 'Viajes'];

function hue(s: string) { let h = 0; for (const c of s) h = (h * 31 + c.charCodeAt(0)) % 360; return h; }

export function assetPreviewUrl(url: string | null | undefined, publicBase: string): string | null {
  if (!url) return null;
  // Las imágenes de la biblioteca se muestran desde este mismo servidor (la URL pública puede ser un CDN).
  const prefix = `${publicBase.replace(/\/$/, '')}/api/v1/public/assets/`;
  return url.startsWith(prefix) ? `/api/v1/public/assets/${url.slice(prefix.length)}` : url;
}

interface Props {
  config: RemoteConfig;
  device: Device;
  prefs: UserAppearance;
  selected: string | null;
  onSelect: (sectionId: string) => void;
  publicBase: string;
  now: number;
}

export function AppPreview({ config, device, prefs, selected, onSelect, publicBase, now }: Props) {
  const t = config.theme;
  const p = resolvePalette(config, prefs);
  const tv = device === 'tv';
  const sections = resolveSections(config, prefs);
  const font = t.typography.family === 'poppins' ? '"PoppinsPreview", system-ui' : t.typography.family === 'inter' ? '"InterPreview", system-ui' : 'system-ui, Roboto, sans-serif';
  const pad = tv ? Math.max(t.spacing.screenPadding, 40) : t.spacing.screenPadding;
  const scale = t.typography.scale * (tv ? 1.12 : 1);
  // Igual que la app: en TV y pantallas de 840 dp o más se usa navegación lateral.
  const navStyle = tv || device === 'tablet' ? 'side_rail' : t.navigation.style;
  const useRail = tv || navStyle === 'side_rail';

  const bg: CSSProperties = t.background.style === 'solid' ? { background: css(p.background) }
    : { background: `linear-gradient(180deg, ${css(t.background.gradientStart)}, ${css(t.background.gradientEnd)})` };
  const glowA = 0.18 * t.glow.intensity;
  const nebula = t.background.style === 'nebula' && t.glow.enabled
    ? `radial-gradient(circle at 85% 8%, ${alpha(p.primary, glowA)}, transparent 55%), radial-gradient(circle at 5% 75%, ${alpha(p.secondary, glowA * 1.2)}, transparent 60%)` : undefined;

  const vars = {
    '--pv-primary': css(p.primary), '--pv-secondary': css(p.secondary), '--pv-accent': css(p.accent),
    '--pv-bg': css(p.background), '--pv-surface': css(p.surface), '--pv-surface2': css(p.surfaceVariant),
    '--pv-on-bg': css(p.onBackground), '--pv-on-surface': css(p.onSurface), '--pv-muted': css(p.textSecondary),
    '--pv-btn': css(p.buttonBackground), '--pv-btn-text': css(p.buttonText), '--pv-focus': css(p.focusRing),
    '--pv-card-r': `${t.shape.cardRadius}px`, '--pv-btn-r': `${t.shape.buttonRadius}px`, '--pv-banner-r': `${t.shape.bannerRadius}px`,
    '--pv-pad': `${pad}px`, '--pv-gap': `${t.spacing.sectionGap}px`, '--pv-card-gap': `${t.spacing.cardGap}px`,
    '--pv-scale': String(scale), fontFamily: font,
  } as CSSProperties;

  const logo = config.brand.logo.variant === 'asset' && config.brand.logo.url ? assetPreviewUrl(config.brand.logo.url, publicBase)
    : config.brand.logo.variant === 'bundled_planet' ? '/brand/embyverse-icon.png' : '/brand/embyverse-logo.webp';

  const nav = ['Inicio', 'TV en vivo', 'Películas', 'Series', 'Buscar', 'Ajustes'];
  const navIcons = ['⌂', '▣', '▶', '▤', '⌕', '⚙'];

  const home = (
    <div className="pv-scroll" style={{ paddingBottom: 24 }}>
      <div className="pv-header" style={{ padding: `12px ${pad}px` }}>
        {logo && <img src={logo} alt="" className="pv-logo" style={{ height: tv ? 60 : 44 }} />}
        <div className="pv-greet">
          <div className="pv-t-title">{config.texts['home.greeting'] ?? 'Hola'}, Ana</div>
          {config.brand.tagline && <div className="pv-t-small pv-muted">{config.brand.tagline}</div>}
        </div>
        <span className="pv-icon">⌕</span><span className="pv-avatar">A</span><span className="pv-icon">⚙</span>
      </div>
      {sections.map((s) => (
        <PreviewSection key={s.id} s={s} config={config} tv={tv} selected={selected === s.id} onSelect={() => onSelect(s.id)} publicBase={publicBase} now={now} />
      ))}
      {sections.length === 0 && <div className="pv-empty">No hay secciones visibles en el inicio.</div>}
    </div>
  );

  return (
    <div className={`pv pv-${device}`} style={{ ...vars, ...bg, width: DEVICES[device].w, height: DEVICES[device].h }} aria-label="Vista previa de la app">
      <div className="pv-nebula" style={{ backgroundImage: nebula }} />
      {useRail ? (
        <div className="pv-row">
          <div className="pv-rail" style={{ background: alpha(p.surface, 0.92) }}>
            <img src="/brand/embyverse-icon.png" alt="" className="pv-rail-logo" />
            {nav.map((n, i) => (
              <div key={n} className={`pv-rail-item ${i === 0 ? 'on' : ''}`}>
                <span className="pv-rail-ico">{navIcons[i]}</span>{t.navigation.showLabels && <span>{n}</span>}
              </div>
            ))}
          </div>
          <div className="pv-main">{home}</div>
        </div>
      ) : navStyle === 'top_tabs' ? (
        <div className="pv-col">
          <div className="pv-tabs" style={{ background: css(p.surface) }}>
            {nav.map((n, i) => <span key={n} className={i === 0 ? 'on' : ''}>{t.navigation.showLabels ? n : navIcons[i]}</span>)}
          </div>
          <div className="pv-main">{home}</div>
        </div>
      ) : (
        <div className="pv-col">
          <div className="pv-main">{home}</div>
          <div className="pv-bottom" style={{ background: alpha(p.surface, 0.96) }}>
            {nav.map((n, i) => (
              <div key={n} className={`pv-bottom-item ${i === 0 ? 'on' : ''}`}>
                <span className="pv-bottom-ico">{navIcons[i]}</span>{t.navigation.showLabels && i === 0 && <span>{n}</span>}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function alpha(hex: string, a: number) {
  const h = hex.length === 9 ? hex.slice(3) : hex.slice(1);
  return `rgba(${parseInt(h.slice(0, 2), 16)}, ${parseInt(h.slice(2, 4), 16)}, ${parseInt(h.slice(4, 6), 16)}, ${a})`;
}

function cardWidth(s: SectionSpec, tv: boolean) {
  const base = s.card.aspect === 'poster' ? (tv ? 150 : 112) : s.card.aspect === 'landscape' ? (tv ? 264 : 208) : (tv ? 140 : 108);
  return base * ({ s: 0.8, m: 1, l: 1.3 } as const)[s.card.size];
}
const ratio = (s: SectionSpec) => (s.card.aspect === 'poster' ? 3 / 2 : s.card.aspect === 'landscape' ? 9 / 16 : 1);

function sample(s: SectionSpec): { title: string; sub?: string; live?: boolean; progress?: number }[] {
  switch (s.type) {
    case 'live_tv': return CHANNELS.map((c) => ({ title: c, sub: 'En directo', live: true }));
    case 'series': return SERIES.map((x, i) => ({ title: x, sub: `${2019 + i} · ★ ${(7 + i * 0.3).toFixed(1)}` }));
    case 'continue_watching': return [MOVIES[1]!, SERIES[0]!, MOVIES[4]!].map((x, i) => ({ title: x, sub: i === 1 ? 'T1 · E4' : undefined, progress: [0.35, 0.7, 0.15][i] }));
    case 'favorites': return [MOVIES[6]!, CHANNELS[2]!, SERIES[3]!, MOVIES[2]!].map((x) => ({ title: x }));
    case 'recently_added': return [...MOVIES.slice(5), ...SERIES.slice(2)].map((x) => ({ title: x, sub: 'Nuevo' }));
    case 'recommended': return [...MOVIES.slice(0, 4), ...SERIES.slice(0, 4)].map((x) => ({ title: x }));
    default: return MOVIES.map((x, i) => ({ title: x, sub: `${2015 + i}` }));
  }
}

function Poster({ title, live, w, h, progress }: { title: string; live?: boolean; w: number; h: number; progress?: number }) {
  const hu = hue(title);
  return (
    <div className="pv-card" style={{ width: w, height: h, background: live ? 'var(--pv-surface2)' : `linear-gradient(155deg, hsl(${hu} 55% 32%), hsl(${(hu + 60) % 360} 60% 14%))` }}>
      {live ? <span className="pv-chan">{title.split(' ')[0]}</span> : <span className="pv-poster-title">{title}</span>}
      {progress !== undefined && <span className="pv-progress"><i style={{ width: `${progress * 100}%` }} /></span>}
    </div>
  );
}

function PreviewSection({ s, config, tv, selected, onSelect, publicBase, now }: { s: SectionSpec; config: RemoteConfig; tv: boolean; selected: boolean; onSelect: () => void; publicBase: string; now: number }) {
  const wrap = (children: ReactNode) => (
    <div className={`pv-section ${selected ? 'is-selected' : ''}`} onClick={onSelect} role="button" tabIndex={0}
      onKeyDown={(e) => { if (e.key === 'Enter') onSelect(); }} title="Editar esta sección">
      {children}
    </div>
  );
  const header = (s.title || s.subtitle) && (
    <div className="pv-sec-head">
      <div>{s.title && <div className="pv-t-section">{s.title}</div>}{s.subtitle && <div className="pv-t-small pv-muted">{s.subtitle}</div>}</div>
      {['live_tv', 'movies', 'series', 'favorites'].includes(s.type) && <span className="pv-see">Ver todo ›</span>}
    </div>
  );

  if (s.type === 'hero_banner') {
    const banners = activeBanners(config, 'hero', now).slice(0, s.maxItems);
    const items: (Banner | { id: string; title: string; subtitle: string; imageUrl: null; action: { route: null } })[] =
      banners.length ? banners : [{ id: 'f', title: MOVIES[0]!, subtitle: 'Contenido destacado de tus fuentes', imageUrl: null, action: { route: null } }];
    const b = items[0]!;
    const img = assetPreviewUrl(b.imageUrl, publicBase);
    return wrap(
      <div style={{ padding: '0 var(--pv-pad)' }}>
        <div className="pv-hero" style={{ height: tv ? 300 : 210, backgroundImage: img ? `url("${img}")` : undefined }}>
          <div className="pv-hero-shade" />
          <div className="pv-hero-body">
            <div className="pv-t-hero">{b.title}</div>
            {b.subtitle && <div className="pv-t-body">{b.subtitle}</div>}
            <span className="pv-btn" data-style={config.theme.buttonStyle}>{b.action.route === 'sources' ? '+ Añadir fuente' : b.action.route ? 'Abrir' : '▶ Ver ahora'}</span>
          </div>
        </div>
        {items.length > 1 && <div className="pv-dots">{items.map((x, i) => <i key={x.id} className={i === 0 ? 'on' : ''} />)}</div>}
      </div>,
    );
  }

  if (s.type === 'promo_banners') {
    const promos = activeBanners(config, 'promo', now).slice(0, s.maxItems);
    if (!promos.length) return wrap(<div className="pv-placeholder">{s.title || 'Banners promocionales'}: ningún banner activo ahora</div>);
    const w = cardWidth({ ...s, card: { ...s.card, aspect: 'landscape' } }, tv) * 1.3;
    return wrap(<>{header}<div className="pv-row-cards">{promos.map((b) => {
      const img = assetPreviewUrl(b.imageUrl, publicBase);
      return (
        <div key={b.id} className="pv-promo" style={{ width: w, height: w * 9 / 16, backgroundImage: img ? `url("${img}")` : undefined }}>
          <div className="pv-promo-body"><b>{b.title}</b>{b.subtitle && <span>{b.subtitle}</span>}</div>
        </div>
      );
    })}</div></>);
  }

  const items = sample(s).slice(0, s.maxItems);
  const w = cardWidth(s, tv), h = w * ratio(s);
  const cards = items.map((it) => (
    <div key={it.title} className="pv-item" style={{ width: w }}>
      <Poster title={it.title} live={it.live} w={w} h={h} progress={it.progress} />
      <div className="pv-t-card">{it.title}</div>
      {it.sub && <div className="pv-t-small pv-muted">{it.sub}</div>}
    </div>
  ));
  return wrap(<>{header}{s.layout === 'grid' ? <div className="pv-grid">{cards}</div> : <div className="pv-row-cards">{cards}</div>}</>);
}

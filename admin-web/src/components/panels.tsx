import { useState } from 'react';
import type { Banner, PaletteKey, RemoteConfig, SectionSpec, Notice as ClientMessage } from '../../../backend/src/config/schema';
import { ROUTES, SECTION_TYPES } from '../../../backend/src/config/schema';
import type { Asset } from '../api';
import { assetPreviewUrl } from '../preview/AppPreview';
import { contrast, type Editor } from '../store';
import { ColorField, Group, Notice, Segmented, Select, Slider, TextField, Toggle } from './ui';

export interface PanelProps { ed: Editor; canEdit: boolean; assets: Asset[]; publicBase: string; selected: string | null; setSelected: (id: string | null) => void; onUpload: (f: File, kind: Asset['kind']) => Promise<Asset | null> }

const SECTION_NAMES: Record<string, string> = {
  hero_banner: 'Banner principal', promo_banners: 'Banners promocionales', continue_watching: 'Continuar viendo',
  live_tv: 'TV en vivo', movies: 'Películas', series: 'Series', recently_added: 'Novedades (contenido nuevo)',
  favorites: 'Favoritos', recommended: 'Recomendaciones',
};
const ROUTE_NAMES: Record<string, string> = {
  home: 'Inicio', live: 'TV en vivo', movies: 'Películas', series: 'Series', search: 'Buscar',
  favorites: 'Favoritos', settings: 'Ajustes', sources: 'Añadir/gestionar fuentes',
};

// ---------------------------------------------------------------- IDENTIDAD
export function IdentityPanel({ ed, canEdit, assets, publicBase, onUpload }: PanelProps) {
  const c = ed.config;
  const logos = assets.filter((a) => a.kind === 'logo');
  const approved = logos.filter((a) => a.approved);
  const variant = c.brand.logo.variant;
  return (
    <>
      <Group title="Marca" hint="Nombre y lema que aparecen en el inicio y en la pantalla de bienvenida.">
        <TextField label="Nombre comercial" value={c.brand.appName} max={40} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.brand.appName = v; }, 'appName')} />
        <TextField label="Lema" value={c.brand.tagline ?? ''} max={80} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.brand.tagline = v || null; }, 'tagline')} />
      </Group>
      <Group title="Servidor del proveedor" hint="Ya queda puesto en la app: el cliente solo escribe usuario y contraseña. Si lo cambias, las apps instaladas se pasan solas al nuevo servidor y actualizan sus listas (sin APK nueva).">
        <TextField label="Servidor (host)" value={c.texts['provider.host'] ?? ''} max={200} placeholder="https://krnosmx.com:443" disabled={!canEdit}
          onChange={(v) => ed.update((d) => { const x = v.trim(); if (x) d.texts['provider.host'] = x; else delete d.texts['provider.host']; }, 'host')} />
        {c.texts['provider.host'] && !/^https?:\/\/[^\s/]+/i.test(c.texts['provider.host']) && <Notice kind="error">Debe empezar por http:// o https://</Notice>}
        {!c.texts['provider.host'] && <p className="hint">Vacío = el de la APK (https://krnosmx.com:443).</p>}
      </Group>
      <Group title="Guía de canales (EPG)" hint="La app ya usa la guía de tu proveedor. Aquí puedes AÑADIR guías XMLTV extra (hasta 3, una por línea): se emparejan por id y, si no coincide, por el NOMBRE del canal. Así los canales que llegan sin guía muestran su programación. Se aplica en la siguiente actualización de listas de cada cliente.">
        <TextField label="URL de guías XMLTV extra (.xml o .xml.gz)" value={c.texts['provider.epgUrl'] ?? ''} max={500} multiline placeholder="https://…/guia.xml.gz" disabled={!canEdit}
          onChange={(v) => ed.update((d) => { const x = v.trim(); if (x) d.texts['provider.epgUrl'] = x; else delete d.texts['provider.epgUrl']; }, 'epg')} />
        {c.texts['provider.epgUrl'] && c.texts['provider.epgUrl'].split(/[\s,]+/).some((u) => u && !/^https?:\/\//i.test(u)) && <Notice kind="error">Cada URL debe empezar por http:// o https://</Notice>}
      </Group>
      <Group title="Logotipo" hint="Solo se pueden usar el logotipo oficial integrado o variantes aprobadas por un propietario en Recursos.">
        <div className="choice-grid">
          {([['bundled_full', 'Logotipo completo', '/brand/embyverse-logo.webp'], ['bundled_planet', 'Solo planeta', '/brand/embyverse-icon.png']] as const).map(([v, label, src]) => (
            <button key={v} type="button" disabled={!canEdit} className={`choice ${variant === v ? 'on' : ''}`} onClick={() => ed.update((d) => { d.brand.logo = { variant: v }; })}>
              <img src={src} alt="" /><span>{label}</span>
            </button>
          ))}
          {approved.map((a) => (
            <button key={a.id} type="button" disabled={!canEdit} className={`choice ${variant === 'asset' && c.brand.logo.url === a.url ? 'on' : ''}`}
              onClick={() => ed.update((d) => { d.brand.logo = { variant: 'asset', url: a.url }; })}>
              <img src={a.previewUrl} alt="" /><span>{a.filename}</span>
            </button>
          ))}
        </div>
        {canEdit && (
          <label className="btn upload-logo">Subir mi logotipo
            <input type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={async (e) => {
              const f = e.target.files?.[0]; e.target.value = ''; if (!f) return;
              const a = await onUpload(f, 'logo'); if (a && a.approved) ed.update((d) => { d.brand.logo = { variant: 'asset', url: a.url }; });
            }} />
          </label>
        )}
        <p className="hint">PNG cuadrado (512×512 o más) con fondo transparente u oscuro. El logotipo y el nombre también cambian el ícono y el nombre de la APK (se genera una APK nueva al publicar).</p>
        {logos.length > approved.length && <p className="hint">{logos.length - approved.length} variante(s) pendiente(s) de aprobación en Recursos.</p>}
        {variant === 'asset' && c.brand.logo.url && !approved.some((a) => a.url === c.brand.logo.url) && (
          <Notice kind="error">El logotipo seleccionado ya no está aprobado. <img src={assetPreviewUrl(c.brand.logo.url, publicBase) ?? ''} alt="" height={20} /></Notice>
        )}
      </Group>
      <Group title="Tipografía">
        <div className="choice-grid">
          {([['poppins', 'Poppins', 'PoppinsPreview'], ['inter', 'Inter', 'InterPreview'], ['system', 'Sistema', 'system-ui']] as const).map(([v, label, fam]) => (
            <button key={v} type="button" disabled={!canEdit} className={`choice type-choice ${c.theme.typography.family === v ? 'on' : ''}`} onClick={() => ed.update((d) => { d.theme.typography.family = v; })}>
              <span className="type-sample" style={{ fontFamily: fam }}>Aa</span><span>{label}</span>
            </button>
          ))}
        </div>
        <Slider label="Escala del texto" value={c.theme.typography.scale} min={0.8} max={1.4} step={0.05} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.typography.scale = v; }, 'scale')} />
      </Group>
      <Group title="Fondo">
        <Segmented value={c.theme.background.style} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.background.style = v; })}
          options={[{ value: 'solid', label: 'Sólido' }, { value: 'gradient', label: 'Degradado' }, { value: 'nebula', label: 'Nebulosa' }]} />
        {c.theme.background.style !== 'solid' && (
          <div className="color-grid">
            <ColorField label="Inicio del degradado" value={c.theme.background.gradientStart} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.background.gradientStart = v; }, 'gs')} />
            <ColorField label="Fin del degradado" value={c.theme.background.gradientEnd} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.background.gradientEnd = v; }, 'ge')} />
          </div>
        )}
        <Toggle label="Iluminación neón" hint="Brillo suave en el fondo y alrededor del elemento enfocado" checked={c.theme.glow.enabled} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.glow.enabled = v; })} />
        {c.theme.glow.enabled && <Slider label="Intensidad" value={c.theme.glow.intensity} min={0} max={1} step={0.05} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.glow.intensity = v; }, 'glow')} />}
      </Group>
    </>
  );
}

// ---------------------------------------------------------------- COLORES Y TEMAS
const COLOR_GROUPS: { title: string; keys: [PaletteKey, string][] }[] = [
  { title: 'Marca', keys: [['primary', 'Principal'], ['secondary', 'Secundario'], ['accent', 'Acento']] },
  { title: 'Superficies', keys: [['background', 'Fondo'], ['surface', 'Superficie'], ['surfaceVariant', 'Superficie elevada']] },
  { title: 'Texto', keys: [['onBackground', 'Texto principal'], ['onSurface', 'Texto en superficies'], ['textSecondary', 'Texto secundario']] },
  { title: 'Botones y foco', keys: [['buttonBackground', 'Fondo del botón'], ['buttonText', 'Texto del botón'], ['onPrimary', 'Texto sobre principal'], ['focusRing', 'Anillo de foco (TV)'], ['error', 'Error']] },
];

function ContrastBadge({ a, b }: { a: string; b: string }) {
  const r = contrast(a, b);
  return <span className={`contrast ${r >= 4.5 ? 'good' : r >= 3 ? 'fair' : 'bad'}`} title="Contraste WCAG">{r.toFixed(1)}:1</span>;
}

export function ColorsPanel({ ed, canEdit }: PanelProps) {
  const c = ed.config;
  const pal = c.theme.palette;
  const [themeIdx, setThemeIdx] = useState<number | null>(null);
  const contrastFor: Partial<Record<PaletteKey, [string, string]>> = {
    onBackground: [pal.onBackground, pal.background], textSecondary: [pal.textSecondary, pal.background],
    buttonText: [pal.buttonText, pal.buttonBackground], onSurface: [pal.onSurface, pal.surface],
  };
  return (
    <>
      {COLOR_GROUPS.map((g) => (
        <Group key={g.title} title={g.title}>
          <div className="color-grid">
            {g.keys.map(([k, label]) => (
              <ColorField key={k} label={label} value={pal[k]} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.palette[k] = v; }, `pal-${k}`)}
                note={contrastFor[k] ? <ContrastBadge a={contrastFor[k]![0]} b={contrastFor[k]![1]} /> : undefined} />
            ))}
          </div>
        </Group>
      ))}
      <Group title="Temas que puede elegir el usuario" hint="Cada tema sustituye solo los colores que indiques. El primero es el original."
        actions={canEdit && c.themes.length < 12 ? <button className="btn-ghost" onClick={() => ed.update((d) => { d.themes.push({ id: `tema_${Date.now().toString(36).slice(-4)}`, name: 'Nuevo tema', palette: {} }); })}>Añadir tema</button> : undefined}>
        <ul className="list">
          {c.themes.map((t, i) => (
            <li key={t.id} className={themeIdx === i ? 'open' : ''}>
              <button className="list-row" onClick={() => setThemeIdx(themeIdx === i ? null : i)}>
                <span className="swatches">{(['primary', 'secondary', 'accent', 'background'] as PaletteKey[]).map((k) => <i key={k} style={{ background: (t.palette[k] ?? pal[k]).replace(/^#(..)(......)$/, '#$2') }} />)}</span>
                <span className="grow">{t.name}</span><span className="hint">{Object.keys(t.palette).length} cambios</span>
              </button>
              {themeIdx === i && (
                <div className="list-detail">
                  <TextField label="Nombre" value={t.name} max={40} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.themes[i]!.name = v; }, `tn-${i}`)} />
                  <div className="color-grid">
                    {(['primary', 'secondary', 'accent', 'background', 'surface', 'buttonBackground', 'buttonText'] as PaletteKey[]).map((k) => (
                      <div key={k} className="override">
                        <ColorField label={k} value={t.palette[k] ?? pal[k]} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.themes[i]!.palette[k] = v; }, `tp-${i}-${k}`)} />
                        {t.palette[k] && canEdit && <button className="link" onClick={() => ed.update((d) => { delete d.themes[i]!.palette[k]; })}>Usar el original</button>}
                      </div>
                    ))}
                  </div>
                  {canEdit && i > 0 && <button className="btn-danger" onClick={() => { ed.update((d) => { d.themes.splice(i, 1); }); setThemeIdx(null); }}>Eliminar tema</button>}
                </div>
              )}
            </li>
          ))}
        </ul>
      </Group>
    </>
  );
}

// ---------------------------------------------------------------- CONSTRUCTOR DE INICIO
export function HomeBuilderPanel({ ed, canEdit, selected, setSelected }: PanelProps) {
  const sections = ed.config.home.sections;
  const locked = new Set(ed.config.userCustomization.lockedSections);
  const [drag, setDrag] = useState<number | null>(null);
  const [adding, setAdding] = useState<string>('movies');

  const move = (from: number, to: number) => ed.update((d) => {
    const [x] = d.home.sections.splice(from, 1);
    d.home.sections.splice(to, 0, x!);
  });
  const set = (i: number, f: (s: SectionSpec) => void, key?: string) => ed.update((d) => f(d.home.sections[i]!), key);

  return (
    <Group title="Secciones del inicio" hint="Arrastra para reordenar. Haz clic en una sección de la vista previa para editarla aquí.">
      <ol className="sections">
        {sections.map((s, i) => {
          const open = selected === s.id;
          const known = (SECTION_TYPES as readonly string[]).includes(s.type);
          return (
            <li key={s.id} className={`sec ${open ? 'open' : ''} ${!s.enabled ? 'off' : ''} ${drag === i ? 'dragging' : ''}`}
              draggable={canEdit} onDragStart={() => setDrag(i)} onDragEnd={() => setDrag(null)}
              onDragOver={(e) => { e.preventDefault(); }} onDrop={() => { if (drag !== null && drag !== i) move(drag, i); setDrag(null); }}>
              <div className="sec-row">
                <span className="grip" aria-hidden>⋮⋮</span>
                <button className="sec-title" onClick={() => setSelected(open ? null : s.id)} aria-expanded={open}>
                  <b>{s.title || SECTION_NAMES[s.type] || s.type}</b>
                  <span className="hint">{SECTION_NAMES[s.type] ?? `Tipo desconocido (${s.type})`} · {s.layout === 'grid' ? 'cuadrícula' : s.layout === 'hero' ? 'destacado' : 'carrusel'}{locked.has(s.id) ? ' · bloqueada' : ''}</span>
                </button>
                <div className="sec-tools">
                  <button aria-label="Subir" disabled={!canEdit || i === 0} onClick={() => move(i, i - 1)}>↑</button>
                  <button aria-label="Bajar" disabled={!canEdit || i === sections.length - 1} onClick={() => move(i, i + 1)}>↓</button>
                  <input type="checkbox" role="switch" aria-label="Mostrar sección" checked={s.enabled} disabled={!canEdit} onChange={(e) => set(i, (x) => { x.enabled = e.target.checked; })} />
                </div>
              </div>
              {open && (
                <div className="sec-edit">
                  {!known && <Notice kind="warn">Este tipo no existe en esta versión del panel; la app lo omitirá.</Notice>}
                  <TextField label="Título" value={s.title} max={60} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.title = v; }, `st-${i}`)} />
                  <TextField label="Subtítulo" value={s.subtitle ?? ''} max={100} disabled={!canEdit} onChange={(v) => set(i, (x) => { if (v) x.subtitle = v; else delete x.subtitle; }, `ss-${i}`)} />
                  {s.type !== 'hero_banner' && (
                    <>
                      <Segmented label="Distribución" value={s.layout === 'grid' ? 'grid' : 'carousel'} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.layout = v; })}
                        options={[{ value: 'carousel', label: 'Carrusel' }, { value: 'grid', label: 'Cuadrícula' }]} />
                      <Segmented label="Proporción de tarjeta" value={s.card.aspect} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.card.aspect = v; })}
                        options={[{ value: 'poster', label: 'Póster 2:3' }, { value: 'landscape', label: 'Panorámica 16:9' }, { value: 'square', label: 'Cuadrada' }]} />
                      <Segmented label="Tamaño de tarjeta" value={s.card.size} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.card.size = v; })}
                        options={[{ value: 's', label: 'Pequeña' }, { value: 'm', label: 'Mediana' }, { value: 'l', label: 'Grande' }]} />
                    </>
                  )}
                  <Slider label="Máximo de elementos" value={s.maxItems} min={1} max={100} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.maxItems = v; }, `sm-${i}`)} />
                  <Toggle label="Bloquear para los usuarios" hint="El usuario no podrá ocultarla ni moverla" checked={locked.has(s.id)} disabled={!canEdit}
                    onChange={(v) => ed.update((d) => { const l = new Set(d.userCustomization.lockedSections); if (v) l.add(s.id); else l.delete(s.id); d.userCustomization.lockedSections = [...l]; })} />
                  {canEdit && sections.length > 1 && (
                    <button className="btn-danger" onClick={() => { ed.update((d) => { d.home.sections.splice(i, 1); d.userCustomization.lockedSections = d.userCustomization.lockedSections.filter((x) => x !== s.id); }); setSelected(null); }}>Quitar sección</button>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ol>
      {canEdit && sections.length < 30 && (
        <div className="add-row">
          <Select label="Añadir sección predefinida" value={adding} onChange={setAdding} options={SECTION_TYPES.map((t) => ({ value: t, label: SECTION_NAMES[t]! }))} />
          <button className="btn" onClick={() => {
            const base = adding.replace(/_/g, '-').slice(0, 20);
            let id = base, n = 2;
            while (sections.some((s) => s.id === id)) id = `${base}-${n++}`;
            ed.update((d) => { d.home.sections.push({ id, type: adding, enabled: true, title: SECTION_NAMES[adding]!, layout: adding === 'hero_banner' ? 'hero' : 'carousel', card: { aspect: adding === 'live_tv' || adding === 'continue_watching' ? 'landscape' : 'poster', size: 'm' }, maxItems: 20 }); });
            setSelected(id);
          }}>Añadir</button>
        </div>
      )}
    </Group>
  );
}

// ---------------------------------------------------------------- APARIENCIA
export function AppearancePanel({ ed, canEdit }: PanelProps) {
  const t = ed.config.theme;
  const u = (f: (d: RemoteConfig) => void, k: string) => ed.update(f, k);
  return (
    <>
      <Group title="Bordes" hint="Radio de las esquinas en píxeles de densidad independiente (dp).">
        <Slider label="Tarjetas" value={t.shape.cardRadius} min={0} max={32} unit=" dp" disabled={!canEdit} onChange={(v) => u((d) => { d.theme.shape.cardRadius = v; }, 'cr')} />
        <Slider label="Botones" value={t.shape.buttonRadius} min={0} max={32} unit=" dp" disabled={!canEdit} onChange={(v) => u((d) => { d.theme.shape.buttonRadius = v; }, 'br')} />
        <Slider label="Banners" value={t.shape.bannerRadius} min={0} max={40} unit=" dp" disabled={!canEdit} onChange={(v) => u((d) => { d.theme.shape.bannerRadius = v; }, 'bnr')} />
      </Group>
      <Group title="Espaciado" hint="En Android TV el margen lateral mínimo es 40 dp para respetar la zona segura de los televisores.">
        <Slider label="Margen lateral" value={t.spacing.screenPadding} min={8} max={48} unit=" dp" disabled={!canEdit} onChange={(v) => u((d) => { d.theme.spacing.screenPadding = v; }, 'sp')} />
        <Slider label="Separación entre secciones" value={t.spacing.sectionGap} min={8} max={64} unit=" dp" disabled={!canEdit} onChange={(v) => u((d) => { d.theme.spacing.sectionGap = v; }, 'sg')} />
        <Slider label="Separación entre tarjetas" value={t.spacing.cardGap} min={4} max={32} unit=" dp" disabled={!canEdit} onChange={(v) => u((d) => { d.theme.spacing.cardGap = v; }, 'cg')} />
      </Group>
      <Group title="Botones">
        <Segmented value={t.buttonStyle} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.buttonStyle = v; })}
          options={[{ value: 'filled', label: 'Relleno' }, { value: 'tonal', label: 'Tonal' }, { value: 'outlined', label: 'Contorno' }]} />
      </Group>
      <Group title="Menú de navegación" hint="En Android TV y tabletas grandes la app usa siempre el menú lateral, más cómodo con el mando.">
        <Segmented value={t.navigation.style} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.navigation.style = v; })}
          options={[{ value: 'bottom_bar', label: 'Barra inferior' }, { value: 'side_rail', label: 'Lateral' }, { value: 'top_tabs', label: 'Pestañas superiores' }]} />
        <Toggle label="Mostrar nombres en el menú" checked={t.navigation.showLabels} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.theme.navigation.showLabels = v; })} />
      </Group>
    </>
  );
}

// ---------------------------------------------------------------- BANNERS Y NOVEDADES
function toLocalInput(iso?: string | null) {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const z = new Date(d.getTime() - d.getTimezoneOffset() * 60000);
  return z.toISOString().slice(0, 16);
}

export function BannersPanel({ ed, canEdit, assets, publicBase, onUpload }: PanelProps) {
  const banners = ed.config.banners;
  const [open, setOpen] = useState<number | null>(banners.length ? 0 : null);
  const images = assets.filter((a) => a.kind !== 'logo');
  const set = (i: number, f: (b: Banner) => void, key?: string) => ed.update((d) => f(d.banners[i]!), key);
  const now = Date.now() / 1000;
  return (
    <Group title="Banners y novedades" hint="Solo aparecen en la pantalla de inicio: nunca en el reproductor ni en las listas de canales."
      actions={canEdit && banners.length < 20 ? <button className="btn-ghost" onClick={() => { ed.update((d) => { d.banners.push({ id: `banner-${Date.now().toString(36).slice(-5)}`, placement: 'promo', title: 'Nuevo banner', subtitle: null, imageUrl: null, action: { route: null }, startsAt: null, endsAt: null }); }); setOpen(banners.length); }}>Añadir banner</button> : undefined}>
      {banners.length === 0 && <p className="hint">No hay banners. Añade uno para destacar novedades en el inicio.</p>}
      <ul className="list">
        {banners.map((b, i) => {
          const s = b.startsAt ? Date.parse(b.startsAt) / 1000 : null, e = b.endsAt ? Date.parse(b.endsAt) / 1000 : null;
          const state = e !== null && now >= e ? 'Finalizado' : s !== null && now < s ? 'Programado' : 'Activo';
          return (
            <li key={b.id} className={open === i ? 'open' : ''}>
              <button className="list-row" onClick={() => setOpen(open === i ? null : i)}>
                <span className="thumb" style={{ backgroundImage: b.imageUrl ? `url("${assetPreviewUrl(b.imageUrl, publicBase)}")` : undefined }} />
                <span className="grow"><b>{b.title || 'Sin título'}</b><span className="hint">{b.placement === 'hero' ? 'Banner principal' : 'Promocional'}</span></span>
                <span className={`pill pill-${state === 'Activo' ? 'ok' : state === 'Programado' ? 'info' : 'muted'}`}>{state}</span>
              </button>
              {open === i && (
                <div className="list-detail">
                  <Segmented label="Ubicación" value={b.placement} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.placement = v; })}
                    options={[{ value: 'hero', label: 'Banner principal' }, { value: 'promo', label: 'Fila promocional' }]} />
                  <TextField label="Título" value={b.title} max={80} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.title = v; }, `bt-${i}`)} />
                  <TextField label="Texto" value={b.subtitle ?? ''} max={160} multiline disabled={!canEdit} onChange={(v) => set(i, (x) => { x.subtitle = v || null; }, `bs-${i}`)} />
                  <div className="field">
                    <label>Imagen</label>
                    <div className="image-pick">
                      <button type="button" className={`img-opt ${!b.imageUrl ? 'on' : ''}`} disabled={!canEdit} onClick={() => set(i, (x) => { x.imageUrl = null; })}>Degradado de marca</button>
                      {images.map((a) => (
                        <button key={a.id} type="button" className={`img-opt ${b.imageUrl === a.url ? 'on' : ''}`} disabled={!canEdit} title={a.filename}
                          style={{ backgroundImage: `url("${a.previewUrl}")` }} onClick={() => set(i, (x) => { x.imageUrl = a.url; })} aria-label={a.filename} />
                      ))}
                      {canEdit && (
                        <label className="img-opt upload">Subir…
                          <input type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={async (ev) => {
                            const f = ev.target.files?.[0]; if (!f) return;
                            const a = await onUpload(f, 'banner'); if (a) set(i, (x) => { x.imageUrl = a.url; });
                          }} />
                        </label>
                      )}
                    </div>
                    <p className="hint">Recomendado 1920 × 1080 (principal) o 1280 × 720 (promocional). PNG, JPEG o WebP, máx. 3 MB.</p>
                  </div>
                  <Select label="Al pulsar, abrir" value={(b.action.route ?? 'none') as string} disabled={!canEdit}
                    onChange={(v) => set(i, (x) => { x.action.route = v === 'none' ? null : (v as Banner['action']['route']); })}
                    options={[{ value: 'none', label: 'Nada (solo informativo)' }, ...ROUTES.map((r) => ({ value: r, label: ROUTE_NAMES[r]! }))]} />
                  <div className="two">
                    <div className="field"><label>Publicar desde</label><input type="datetime-local" value={toLocalInput(b.startsAt)} disabled={!canEdit}
                      onChange={(ev) => set(i, (x) => { x.startsAt = ev.target.value ? new Date(ev.target.value).toISOString() : null; })} /></div>
                    <div className="field"><label>Retirar el</label><input type="datetime-local" value={toLocalInput(b.endsAt)} disabled={!canEdit}
                      onChange={(ev) => set(i, (x) => { x.endsAt = ev.target.value ? new Date(ev.target.value).toISOString() : null; })} /></div>
                  </div>
                  {canEdit && (
                    <div className="row-actions">
                      <button className="btn-ghost" disabled={i === 0} onClick={() => { ed.update((d) => { const [x] = d.banners.splice(i, 1); d.banners.splice(i - 1, 0, x!); }); setOpen(i - 1); }}>Mover antes</button>
                      <button className="btn-danger" onClick={() => { ed.update((d) => { d.banners.splice(i, 1); }); setOpen(null); }}>Eliminar banner</button>
                    </div>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </Group>
  );
}

// ---------------------------------------------------------------- MENSAJES A CLIENTES
export function MessagesPanel({ ed, canEdit }: PanelProps) {
  const msgs = ed.config.messages ?? [];
  const [open, setOpen] = useState<number | null>(msgs.length ? 0 : null);
  const set = (i: number, f: (m: ClientMessage) => void, key?: string) => ed.update((d) => { d.messages ??= []; f(d.messages[i]!); }, key);
  const now = Date.now() / 1000;
  return (
    <Group title="Mensajes a clientes" hint="Aparecen una sola vez en la app de cada cliente (mantenimiento, promociones, avisos). Si editas la app de un vendedor, solo la verán sus clientes."
      actions={canEdit && msgs.length < 10 ? <button className="btn-ghost" onClick={() => { ed.update((d) => { (d.messages ??= []).push({ id: `msg-${Date.now().toString(36).slice(-6)}`, title: 'Aviso', body: 'Escribe aquí tu mensaje.', startsAt: null, endsAt: null }); }); setOpen(msgs.length); }}>Añadir mensaje</button> : undefined}>
      {msgs.length === 0 && <p className="hint">No hay mensajes. Añade uno para avisar algo a tus clientes al abrir la app.</p>}
      <ul className="list">
        {msgs.map((m, i) => {
          const s = m.startsAt ? Date.parse(m.startsAt) / 1000 : null, e = m.endsAt ? Date.parse(m.endsAt) / 1000 : null;
          const state = e !== null && now >= e ? 'Finalizado' : s !== null && now < s ? 'Programado' : 'Activo';
          return (
            <li key={m.id} className={open === i ? 'open' : ''}>
              <button className="list-row" onClick={() => setOpen(open === i ? null : i)}>
                <span className="grow"><b>{m.title || 'Sin título'}</b><span className="hint">{m.body.slice(0, 60)}</span></span>
                <span className={`pill pill-${state === 'Activo' ? 'ok' : state === 'Programado' ? 'info' : 'muted'}`}>{state}</span>
              </button>
              {open === i && (
                <div className="list-detail">
                  <TextField label="Título" value={m.title} max={80} disabled={!canEdit} onChange={(v) => set(i, (x) => { x.title = v; }, `mt-${i}`)} />
                  <TextField label="Mensaje" value={m.body} max={400} multiline disabled={!canEdit} onChange={(v) => set(i, (x) => { x.body = v; }, `mb-${i}`)} />
                  <div className="two">
                    <div className="field"><label>Mostrar desde</label><input type="datetime-local" value={toLocalInput(m.startsAt)} disabled={!canEdit}
                      onChange={(ev) => set(i, (x) => { x.startsAt = ev.target.value ? new Date(ev.target.value).toISOString() : null; })} /></div>
                    <div className="field"><label>Dejar de mostrar el</label><input type="datetime-local" value={toLocalInput(m.endsAt)} disabled={!canEdit}
                      onChange={(ev) => set(i, (x) => { x.endsAt = ev.target.value ? new Date(ev.target.value).toISOString() : null; })} /></div>
                  </div>
                  <p className="hint">Cada cliente lo ve una vez. Para volver a mostrarlo, crea un mensaje nuevo.</p>
                  {canEdit && (
                    <div className="row-actions">
                      <button className="btn-danger" onClick={() => { ed.update((d) => { d.messages?.splice(i, 1); }); setOpen(null); }}>Eliminar mensaje</button>
                    </div>
                  )}
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </Group>
  );
}

// ---------------------------------------------------------------- PERSONALIZACIÓN DEL USUARIO
export function UserOptionsPanel({ ed, canEdit }: PanelProps) {
  const uc = ed.config.userCustomization;
  const t = (k: 'allowThemeSelection' | 'allowAccentColor' | 'allowCardSize' | 'allowHideSections' | 'allowReorderSections', v: boolean) => ed.update((d) => { d.userCustomization[k] = v; });
  return (
    <>
      <Group title="Qué puede personalizar cada usuario" hint="Las preferencias se guardan en cada dispositivo y por perfil. Nunca modifican la configuración de otros usuarios ni anulan las secciones bloqueadas.">
        <Toggle label="Elegir tema" checked={uc.allowThemeSelection} disabled={!canEdit} onChange={(v) => t('allowThemeSelection', v)} />
        <Toggle label="Cambiar el color de acento" checked={uc.allowAccentColor} disabled={!canEdit} onChange={(v) => t('allowAccentColor', v)} />
        <Toggle label="Elegir el tamaño de las tarjetas" checked={uc.allowCardSize} disabled={!canEdit} onChange={(v) => t('allowCardSize', v)} />
        <Toggle label="Ocultar secciones" checked={uc.allowHideSections} disabled={!canEdit} onChange={(v) => t('allowHideSections', v)} />
        <Toggle label="Reordenar secciones" checked={uc.allowReorderSections} disabled={!canEdit} onChange={(v) => t('allowReorderSections', v)} />
      </Group>
      {uc.allowAccentColor && (
        <Group title="Colores de acento disponibles" hint="Máximo 12. El usuario solo puede elegir entre estos."
          actions={canEdit && uc.accentChoices.length < 12 ? <button className="btn-ghost" onClick={() => ed.update((d) => { d.userCustomization.accentChoices.push('#FFFFFF'); })}>Añadir color</button> : undefined}>
          <div className="color-grid">
            {uc.accentChoices.map((c, i) => (
              <div key={i} className="override">
                <ColorField label={`Opción ${i + 1}`} value={c} disabled={!canEdit} onChange={(v) => ed.update((d) => { d.userCustomization.accentChoices[i] = v; }, `ac-${i}`)} />
                {canEdit && <button className="link" onClick={() => ed.update((d) => { d.userCustomization.accentChoices.splice(i, 1); })}>Quitar</button>}
              </div>
            ))}
          </div>
        </Group>
      )}
    </>
  );
}

// ---------------------------------------------------------------- TEXTOS
const KNOWN_TEXTS: [string, string][] = [
  ['home.greeting', 'Saludo del inicio'], ['home.emptySources', 'Mensaje cuando no hay fuentes'], ['support.contact', 'Contacto de soporte (Ajustes › Información)'],
];

export function TextsPanel({ ed, canEdit }: PanelProps) {
  const texts = ed.config.texts;
  const [newKey, setNewKey] = useState('');
  const extra = Object.keys(texts).filter((k) => !KNOWN_TEXTS.some(([x]) => x === k));
  const set = (k: string, v: string) => ed.update((d) => { if (v) d.texts[k] = v; else delete d.texts[k]; }, `tx-${k}`);
  return (
    <Group title="Textos de la app" hint="Si un texto se deja vacío, la app usa el texto integrado en el idioma del usuario.">
      {KNOWN_TEXTS.map(([k, label]) => <TextField key={k} label={label} value={texts[k] ?? ''} max={500} multiline={k !== 'home.greeting'} disabled={!canEdit} onChange={(v) => set(k, v)} />)}
      {extra.map((k) => <TextField key={k} label={k} value={texts[k] ?? ''} max={500} disabled={!canEdit} onChange={(v) => set(k, v)} />)}
      {canEdit && (
        <div className="add-row">
          <TextField label="Nueva clave (para versiones futuras de la app)" value={newKey} max={64} placeholder="ej. home.footer" onChange={setNewKey} />
          <button className="btn" disabled={!/^[A-Za-z0-9_.]{1,64}$/.test(newKey) || newKey in texts} onClick={() => { set(newKey, ' '); setNewKey(''); }}>Añadir</button>
        </div>
      )}
    </Group>
  );
}

import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../errors';
import { currentTarget, githubApi, linksFor, setTarget, type Vendor } from '../api-github';
import { Group, Notice } from './ui';
import { fixImage } from '../image-fix';
import { VENDOR_THEMES } from '../vendor-themes';

/**
 * Selector de la app que se está editando, separado en dos: «App de clientes» (la principal, la que usan
 * tus clientes directos) y «Apps de vendedores» (cada vendedor con su propia marca). Cambiar recarga el estudio.
 */
export function TargetSelect({ vendors }: { vendors: Vendor[] }) {
  const cur = currentTarget();
  const go = (slug: string) => { setTarget(slug); location.reload(); };
  return (
    <div className="target-switch" role="group" aria-label="App que se edita">
      <button className={cur === '' ? 'on' : ''} onClick={() => cur !== '' && go('')}>App de clientes</button>
      <label className={cur !== '' ? 'on' : ''}>
        <span>Apps de vendedores</span>
        <select value={cur} aria-label="Vendedor" onChange={(e) => go(e.target.value)} disabled={vendors.length === 0 && !cur}>
          <option value="" disabled>{vendors.length ? 'Elige un vendedor…' : 'Sin vendedores'}</option>
          {vendors.map((v) => <option key={v.slug} value={v.slug}>{v.name}</option>)}
          {cur && !vendors.some((v) => v.slug === cur) && <option value={cur}>{cur}</option>}
        </select>
      </label>
    </div>
  );
}

/** Franja que deja claro qué app se está editando (y quién verá los cambios al publicar). */
export function EditingBanner({ vendors }: { vendors: Vendor[] }) {
  const cur = currentTarget();
  const name = vendors.find((v) => v.slug === cur)?.name ?? cur;
  return cur
    ? <div className="editing-banner vendor">Estás editando la <b>app del vendedor {name}</b>. Al publicar, solo sus clientes ven los cambios (al abrir la app o al conectarse a internet).</div>
    : <div className="editing-banner clients">Estás editando la <b>app de tus clientes</b>. Al publicar, todos tus clientes ven los cambios al abrir la app o al conectarse a internet, sin descargar nada.</div>;
}

/** Acorta un enlace con is.gd o v.gd (gratis, sin cuenta). JSONP porque no permiten peticiones directas desde el navegador. */
function shortenWith(host: string, url: string): Promise<string> {
  return new Promise((resolve, reject) => {
    const cb = `evShort${Date.now()}${Math.floor(Math.random() * 1000)}`;
    const w = window as unknown as Record<string, unknown>;
    const s = document.createElement('script');
    const timer = setTimeout(() => { done(); reject(new Error(`${host} no respondió`)); }, 12_000);
    const done = () => { clearTimeout(timer); delete w[cb]; s.remove(); };
    w[cb] = (r: { shorturl?: string; errormessage?: string }) => { done(); if (r.shorturl) resolve(r.shorturl); else reject(new Error(`${host}: ${r.errormessage || 'no creó el enlace'}`)); };
    s.onerror = () => { done(); reject(new Error(`No se pudo conectar con ${host}`)); };
    s.src = `https://${host}/create.php?format=json&callback=${cb}&url=${encodeURIComponent(url)}`;
    document.head.appendChild(s);
  });
}

/**
 * Intenta varias combinaciones: algunos acortadores rechazan los enlaces que terminan en «.apk»,
 * así que también se prueba con la página de descarga (/a/?v=…), que baja la APK sola.
 */
async function shorten(urls: string[]): Promise<string> {
  const errors: string[] = [];
  for (const host of ['is.gd', 'v.gd']) for (const u of urls) {
    try { return await shortenWith(host, u); } catch (e) { errors.push(e instanceof Error ? e.message : String(e)); }
  }
  throw new Error(`No se pudo crear el código corto (${[...new Set(errors)].slice(0, 2).join(' · ')}). Usa el enlace largo o go.aftvnews.com.`);
}

/** ¿Ya está publicada la APK? (la de un vendedor nuevo tarda unos 5–15 minutos en compilarse). */
function useApkReady(url: string): 'checking' | 'ready' | 'building' | 'unknown' {
  const [st, setSt] = useState<'checking' | 'ready' | 'building' | 'unknown'>('checking');
  useEffect(() => {
    let alive = true; let t: ReturnType<typeof setTimeout> | undefined;
    const check = async () => {
      try {
        const r = await fetch(url, { method: 'HEAD', cache: 'no-store' });
        if (!alive) return;
        if (r.ok) { setSt('ready'); return; }
        setSt(r.status === 404 ? 'building' : 'unknown');
      } catch { if (alive) setSt('unknown'); return; }
      t = setTimeout(check, 30_000);
    };
    void check();
    return () => { alive = false; if (t) clearTimeout(t); };
  }, [url]);
  return st;
}

const SHORT_KEY = (slug: string) => `ev.studio.short:${slug || 'principal'}`;

function Links({ slug }: { slug: string }) {
  const l = linksFor(slug);
  const copy = (t: string) => navigator.clipboard?.writeText(t);
  const [short, setShort] = useState<string>(() => { try { return localStorage.getItem(SHORT_KEY(slug)) || ''; } catch { return ''; } });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const ready = useApkReady(l.apk);
  const make = async () => {
    setBusy(true); setErr('');
    try {
      const s = (await shorten([l.apk, `https://${l.short}`])).replace(/^https?:\/\//, '');
      setShort(s); try { localStorage.setItem(SHORT_KEY(slug), s); } catch { /* */ }
    } catch (e) { setErr(e instanceof Error ? e.message : 'Error'); }
    finally { setBusy(false); }
  };
  return (
    <div className="vendor-links">
      {ready === 'building' && <span className="hint" style={{ color: '#b54708' }}>⏳ Su APK se está compilando (unos 5–15 minutos después de crearla o de cambiar su nombre o logotipo). Esta página se actualiza sola; mientras tanto el enlace de descarga todavía no funciona.</span>}
      {ready === 'ready' && <span className="hint" style={{ color: '#067647' }}>✓ APK lista para descargar</span>}
      <span><b>Código para Downloader:</b>{' '}
        {short ? <><code className="short-code">{short}</code> <button className="link" onClick={() => copy(short)}>Copiar</button> <button className="link" onClick={() => { setShort(''); try { localStorage.removeItem(SHORT_KEY(slug)); } catch { /* */ } }}>Crear otro</button></>
          : <button className="btn" disabled={busy || ready === 'building'} onClick={() => void make()}>{busy ? 'Creando…' : ready === 'building' ? 'Espera a que termine la APK' : 'Generar código para Downloader'}</button>}
      </span>
      {err && <span className="hint" style={{ color: '#b42318' }}>{err}</span>}
      {short && <span className="hint">Tus clientes escriben <b>{short}</b> en Downloader y se instala la app. Siempre baja la versión más reciente; después se actualiza sola dentro de la app.</span>}
      <span><b>Enlace largo:</b> <code>{l.short}</code> <button className="link" onClick={() => copy(l.short)}>Copiar</button></span>
      <span><b>APK:</b> <a href={l.apk} target="_blank" rel="noreferrer">{l.apk.split('/').pop()}</a></span>
      <span><b>Página de descarga:</b> <a href={l.web} target="_blank" rel="noreferrer">{l.web.replace('https://', '')}</a></span>
      <span className="hint">¿Prefieres un código numérico de Downloader? Pégale el enlace largo en <a href="https://go.aftvnews.com/" target="_blank" rel="noreferrer">go.aftvnews.com</a> (pide resolver un captcha, por eso no se puede hacer automático).</span>
    </div>
  );
}

export function VendorsPanel({ vendors, reload }: { vendors: Vendor[]; reload: () => void }) {
  const [name, setName] = useState('');
  const [mode, setMode] = useState<'mine' | 'ask'>('mine');
  const [logo, setLogo] = useState<File | null>(null);
  const [logoPrev, setLogoPrev] = useState('');
  const [logoNote, setLogoNote] = useState('');
  const [themeId, setThemeId] = useState(VENDOR_THEMES[1]!.id);
  const themeOf = (id: string) => VENDOR_THEMES.find((x) => x.id === id) ?? VENDOR_THEMES[0]!;
  const [colors, setColors] = useState(() => { const p = themeOf(VENDOR_THEMES[1]!.id).palette; return { primary: p.primary!, accent: p.accent!, background: p.background! }; });
  const pickTheme = (id: string) => { setThemeId(id); const p = themeOf(id).palette; setColors({ primary: p.primary!, accent: p.accent!, background: p.background! }); };
  const pickLogo = async (f: File | undefined) => {
    setLogoNote(''); if (!f) return;
    try {
      const r = await fixImage(f, 'logo');
      setLogo(r.file); setLogoNote(r.changed ? r.note : 'Imagen lista.');
      setLogoPrev((old) => { if (old) URL.revokeObjectURL(old); return URL.createObjectURL(r.file); });
    } catch (e) { setLogo(null); setLogoPrev(''); setLogoNote(e instanceof Error ? e.message : 'No se pudo abrir la imagen.'); }
  };
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'error' | 'info'; text: string } | null>(null);
  const cur = currentTarget();
  const run = async (f: () => Promise<unknown>, ok: string) => {
    setBusy(true); setMsg(null);
    try { await f(); setMsg({ kind: 'ok', text: ok }); reload(); }
    catch (e) { setMsg({ kind: 'error', text: e instanceof ApiError ? e.message : 'No se pudo completar' }); }
    finally { setBusy(false); }
  };
  const create = (e: FormEvent) => {
    e.preventDefault();
    void run(async () => { const v = await githubApi.createVendor(name, { askHost: mode === 'ask', logo, colors, themeId }); setName(''); setLogo(null); setLogoPrev(''); setLogoNote(''); return v; },
      `Vendedor creado ${mode === 'ask' ? '(sus clientes escribirán URL, usuario y contraseña)' : '(con tu servidor: sus clientes solo escriben usuario y contraseña)'}. Su APK estará lista en unos 5–15 minutos: abajo verás cuando esté lista. Elige «Editar su app» para cambiar su logotipo y colores.`);
  };
  return (
    <div className="panel-stack">
      <Group title="Apps de vendedores" hint="Cada vendedor tiene su propia app: nombre, logotipo, colores, banners y textos que tú decides. Su APK se instala junto a las demás (no se pisan) y se actualiza sola cuando publicas su diseño.">
        {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
        <form className="vendor-new" onSubmit={create}>
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={30} placeholder="Nombre de la marca del vendedor (ej. Juan TV)" aria-label="Nombre del vendedor" />
          <button className="btn" disabled={busy || name.trim().length < 2}>{busy ? 'Creando…' : 'Crear app de vendedor'}</button>
        </form>
        <div className="vendor-themes" role="radiogroup" aria-label="Tema de la app">
          <b>1. Elige un tema</b> <span className="hint">Cambia colores, fondo, letra, bordes y la forma del menú: así su app no se ve igual a la tuya.</span>
          <div className="vt-grid">
            {VENDOR_THEMES.map((th) => (
              <button type="button" key={th.id} className={`vt ${themeId === th.id ? 'on' : ''}`} onClick={() => pickTheme(th.id)} aria-pressed={themeId === th.id}
                style={{ background: th.background.style === 'solid' ? th.palette.background : `linear-gradient(150deg, ${th.background.gradientStart}, ${th.background.gradientEnd})`, borderRadius: Math.min(th.radius.card, 16) }}>
                <span className="vt-name" style={{ color: th.palette.onBackground, fontFamily: th.family === 'poppins' ? 'Poppins, sans-serif' : th.family === 'inter' ? 'Inter, sans-serif' : 'system-ui' }}>{th.name}</span>
                <span className="vt-cards">{[0, 1, 2].map((i) => <i key={i} style={{ background: th.palette.surfaceVariant, borderRadius: th.radius.card / 2, outline: i === 0 ? `2px solid ${th.palette.accent}` : 'none' }} />)}</span>
                <span className="vt-btn" style={{ background: th.button === 'outlined' ? 'transparent' : th.palette.primary, color: th.button === 'outlined' ? th.palette.primary : th.palette.buttonText, border: `2px solid ${th.palette.primary}`, borderRadius: th.radius.button }}>Reproducir</span>
                <span className="vt-desc" style={{ color: th.palette.textSecondary }}>{th.desc}</span>
              </button>
            ))}
          </div>
        </div>
        <b style={{ display: 'block', marginTop: 14 }}>2. Logotipo y ajuste de colores (opcional)</b>
        <div className="vendor-brand">
          <div className="vendor-logo-pick">
            <div className="vendor-logo-box" style={{ background: colors.background }}>{logoPrev ? <img src={logoPrev} alt="Logotipo" /> : <span>Sin logotipo</span>}</div>
            <label className="btn-ghost">{logo ? 'Cambiar logotipo' : 'Subir logotipo'}
              <input type="file" accept="image/*" hidden onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; void pickLogo(f); }} />
            </label>
            {logoNote && <span className="hint">{logoNote}</span>}
            {!logo && <span className="hint" style={{ color: '#b54708' }}>Sin logotipo, la app usa uno con las iniciales del nombre (nunca el de EmbyVerse).</span>}
          </div>
          <div className="vendor-colors">
            {([['primary', 'Color principal (botones)'], ['accent', 'Color de acento (foco y detalles)'], ['background', 'Fondo']] as const).map(([k, label]) => (
              <label key={k}><input type="color" value={colors[k]} onChange={(e) => setColors((c) => ({ ...c, [k]: e.target.value }))} /> <span>{label}</span></label>
            ))}
            <div className="vendor-preview" style={{ background: colors.background }}>
              <b style={{ color: colors.accent }}>{name.trim() || 'Nombre del vendedor'}</b>
              <span style={{ background: colors.primary }} className="vp-btn">Reproducir</span>
            </div>
          </div>
        </div>
        <fieldset className="vendor-mode">
          <legend>3. Servidor de la app del vendedor</legend>
          <label><input type="radio" name="vmode" checked={mode === 'mine'} onChange={() => setMode('mine')} /><span><b>Con mi servidor</b> — sus clientes solo escriben usuario y contraseña (usa el servidor de tu app principal).</span></label>
          <label><input type="radio" name="vmode" checked={mode === 'ask'} onChange={() => setMode('ask')} /><span><b>Sin servidor</b> — la app no trae URL: sus clientes escriben URL, usuario y contraseña (para vendedores con otro servicio).</span></label>
          <span className="hint">Puedes cambiarlo después: «Editar su app» → Identidad → «Cómo entra el cliente».</span>
        </fieldset>
      </Group>
      <Group title="Tus apps" hint="«Editar su app» abre su diseño en el estudio; al publicar solo cambia esa app.">
        <div className={`vendor ${cur === '' ? 'on' : ''}`}>
          <div className="vendor-head"><b>App principal</b>{cur === '' ? <span className="pill pill-ok">Editando</span> : <button className="btn-ghost" onClick={() => { setTarget(''); location.reload(); }}>Editar su app</button>}</div>
          <Links slug="" />
        </div>
        {vendors.length === 0 && <p className="hint">Todavía no hay vendedores.</p>}
        {vendors.map((v) => (
          <div key={v.slug} className={`vendor ${cur === v.slug ? 'on' : ''}`}>
            <div className="vendor-head">
              <b>{v.name}</b><span className="hint">id: {v.slug}</span>
              {cur === v.slug ? <span className="pill pill-ok">Editando</span> : <button className="btn-ghost" onClick={() => { setTarget(v.slug); location.reload(); }}>Editar su app</button>}
              <button className="link danger" disabled={busy} onClick={() => { if (confirm(`¿Eliminar la app de ${v.name}? Su APK dejará de actualizarse.`)) void run(() => githubApi.deleteVendor(v.slug), `${v.name} eliminado`); }}>Eliminar</button>
            </div>
            <Links slug={v.slug} />
          </div>
        ))}
      </Group>
      <p className="hint">Pasos para una app nueva: 1) escribe el nombre, elige un tema, sube su logotipo y elige el servidor · 2) «Crear app de vendedor» (se publica sola) · 3) si quieres más cambios, «Editar su app» y «Publicar» · 4) cuando aparezca «✓ APK lista» (5–15 minutos), pulsa «Generar código para Downloader» y entrégaselo a tu vendedor. Cada versión nueva le llega sola a sus clientes.</p>
    </div>
  );
}

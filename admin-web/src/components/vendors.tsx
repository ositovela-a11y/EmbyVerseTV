import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../errors';
import { currentTarget, githubApi, linksFor, setTarget, type Vendor } from '../api-github';
import { Group, Notice } from './ui';
import { fixImage } from '../image-fix';
import { VENDOR_THEMES } from '../vendor-themes';
import { closestTheme, logoColors, type BrandColors } from '../logo-colors';
import { removeLogoBackground } from '../logo-cutout';

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
  throw new Error(`No se pudo crear el código corto (${[...new Set(errors)].slice(0, 2).join(' · ')}). Usa el código de AFTVnews de arriba.`);
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

/** «aftv.news/9698305», «go.aftvnews.com/9698305» o «9698305» → «9698305». */
export function normDlCode(raw: string): string | null {
  const m = /(?:^|\/)(\d{4,9})\/?$/.exec(raw.trim()) ?? /^\s*(\d{4,9})\s*$/.exec(raw);
  return m ? m[1]! : null;
}

function Links({ slug }: { slug: string }) {
  const l = linksFor(slug);
  const longUrl = `https://${l.short}`;
  const copy = (t: string) => navigator.clipboard?.writeText(t);
  const [short, setShort] = useState<string>(() => { try { return localStorage.getItem(SHORT_KEY(slug)) || ''; } catch { return ''; } });
  const [code, setCode] = useState<string | null>(null); // código numérico de Downloader guardado (para todos tus equipos)
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(''); const [ok, setOk] = useState('');
  const ready = useApkReady(l.apk);
  useEffect(() => { void githubApi.downloaderCodes().then((c) => setCode(c[slug] ?? '')).catch(() => setCode('')); }, [slug]);
  const make = async () => {
    setBusy(true); setErr('');
    try {
      const s = (await shorten([longUrl, l.apk])).replace(/^https?:\/\//, '');
      setShort(s); try { localStorage.setItem(SHORT_KEY(slug), s); } catch { /* */ }
    } catch (e) { setErr(e instanceof Error ? e.message : 'Error'); }
    finally { setBusy(false); }
  };
  const saveCode = async (raw: string | null) => {
    const c = raw === null ? null : normDlCode(raw);
    if (raw !== null && !c) { setErr('Ese código no es válido. Copia el número que te dio AFTVnews (por ejemplo 9698305 o aftv.news/9698305).'); return; }
    setBusy(true); setErr(''); setOk('');
    try { await githubApi.setDownloaderCode(slug, c); setCode(c ?? ''); setDraft(''); setOk(c ? `✓ Código ${c} guardado.` : 'Código quitado.'); }
    catch (e) { setErr(e instanceof Error ? e.message : 'No se pudo guardar.'); } finally { setBusy(false); }
  };
  return (
    <div className="vendor-links">
      {ready === 'building' && <span className="hint" style={{ color: '#b54708' }}>⏳ Su APK se está compilando (unos 5–15 minutos después de crearla o de cambiar su nombre o logotipo). Esta página se actualiza sola; mientras tanto el enlace de descarga todavía no funciona.</span>}
      {ready === 'ready' && <span className="hint" style={{ color: '#067647' }}>✓ APK lista para descargar</span>}
      <span><b>Código de Downloader:</b>{' '}
        {code ? <><code className="short-code dl-code">{code}</code> <button className="link" onClick={() => copy(code)}>Copiar</button> <button className="link danger" disabled={busy} onClick={() => void saveCode(null)}>Quitar</button></>
          : code === '' ? <span className="hint">todavía no tiene.</span> : <span className="hint">cargando…</span>}
      </span>
      {code === '' && (
        <div className="dl-steps">
          <span className="hint"><b>Cómo sacar el código numérico (1 minuto):</b> 1) pulsa «Copiar enlace y abrir AFTVnews» · 2) en la página de AFTVnews pega el enlace, resuelve el captcha y pulsa crear · 3) copia el número que te da y pégalo aquí. AFTVnews no permite hacerlo automático (pide el captcha).</span>
          <div className="dl-row">
            <button className="btn" disabled={ready === 'building'} onClick={() => { void copy(longUrl); window.open('https://go.aftvnews.com/', '_blank', 'noopener'); }}>{ready === 'building' ? 'Espera a que termine la APK' : 'Copiar enlace y abrir AFTVnews'}</button>
            <input className="input" value={draft} onChange={(e) => setDraft(e.target.value)} placeholder="Pega el código, ej. 9698305" aria-label="Código de Downloader" inputMode="numeric" style={{ maxWidth: 220 }} />
            <button className="btn" disabled={busy || !draft.trim()} onClick={() => void saveCode(draft)}>Guardar código</button>
          </div>
        </div>
      )}
      {code && <span className="hint">Tus clientes abren Downloader, escriben <b>{code}</b> y se instala la app. Siempre baja la versión más reciente.</span>}
      {ok && <span className="hint" style={{ color: '#067647' }}>{ok}</span>}
      <span><b>Código corto sin captcha (alternativo):</b>{' '}
        {short ? <><code className="short-code">{short}</code> <button className="link" onClick={() => copy(short)}>Copiar</button> <button className="link" onClick={() => { setShort(''); try { localStorage.removeItem(SHORT_KEY(slug)); } catch { /* */ } }}>Crear otro</button></>
          : <button className="btn-ghost" disabled={busy || ready === 'building'} onClick={() => void make()}>{busy ? 'Creando…' : 'Crear con is.gd'}</button>}
      </span>
      {err && <span className="hint" style={{ color: '#b42318' }}>{err}</span>}
      <span><b>Enlace largo (el que va en AFTVnews):</b> <code>{l.short}</code> <button className="link" onClick={() => copy(longUrl)}>Copiar</button></span>
      <span><b>APK:</b> <a href={l.apk} target="_blank" rel="noreferrer">{l.apk.split('/').pop()}</a></span>
      <span><b>Página de descarga:</b> <a href={l.web} target="_blank" rel="noreferrer">{l.web.replace('https://', '')}</a></span>
      <span><b>App para PC (Windows):</b> <code>{l.pc.replace('https://', '')}</code> <button className="link" onClick={() => copy(l.pc)}>Copiar</button>
        {' '}<a href={l.pcExe} target="_blank" rel="noreferrer">descargar instalador</a></span>
      <span className="hint">La app de PC es de pago: el cliente o vendedor te manda el código que aparece al abrirla y tú lo activas en «Activaciones». Se compila sola unos 10 minutos después de crear el vendedor o cambiar su nombre o logotipo.</span>
    </div>
  );
}

/** Tarjetas de temas (con vista previa de colores, tarjetas y botón). */
function ThemeGrid({ value, onPick }: { value: string | null; onPick: (id: string) => void }) {
  return (
    <div className="vt-grid">
      {VENDOR_THEMES.map((th) => (
        <button type="button" key={th.id} className={`vt ${value === th.id ? 'on' : ''}`} onClick={() => onPick(th.id)} aria-pressed={value === th.id}
          style={{ background: th.background.style === 'solid' ? th.palette.background : `linear-gradient(150deg, ${th.background.gradientStart}, ${th.background.gradientEnd})`, borderRadius: Math.min(th.radius.card, 16) }}>
          <span className="vt-name" style={{ color: th.palette.onBackground, fontFamily: th.family === 'poppins' ? 'Poppins, sans-serif' : th.family === 'inter' ? 'Inter, sans-serif' : 'system-ui' }}>{th.name}</span>
          <span className="vt-cards">{[0, 1, 2].map((i) => <i key={i} style={{ background: th.palette.surfaceVariant, borderRadius: th.radius.card / 2, outline: i === 0 ? `2px solid ${th.palette.accent}` : 'none' }} />)}</span>
          <span className="vt-btn" style={{ background: th.button === 'outlined' ? 'transparent' : th.palette.primary, color: th.button === 'outlined' ? th.palette.primary : th.palette.buttonText, border: `2px solid ${th.palette.primary}`, borderRadius: th.radius.button }}>Reproducir</span>
          <span className="vt-desc" style={{ color: th.palette.textSecondary }}>{th.desc}</span>
        </button>
      ))}
    </div>
  );
}

/**
 * Editor rápido de una app ya creada (la tuya o la de un vendedor): cambiar el tema con el que arranca,
 * sus colores y su logotipo (se le quita el fondo solo). Se publica al guardar y le llega a sus clientes.
 */
function AppLookEditor({ slug, name, onClose }: { slug: string; name: string; onClose: () => void }) {
  const [loaded, setLoaded] = useState(false);
  const [themeId, setThemeId] = useState<string | null>(null);
  const [colors, setColors] = useState<BrandColors>({ primary: '#7BE52F', accent: '#22D3EE', background: '#02050C' });
  const [curLogo, setCurLogo] = useState<string | null>(null);
  const [orig, setOrig] = useState<File | null>(null);       // logotipo tal cual lo subió
  const [cut, setCut] = useState<File | null>(null);         // logotipo sin fondo
  const [useCut, setUseCut] = useState(true);
  const [prev, setPrev] = useState(''); const [note, setNote] = useState('');
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ kind: 'ok' | 'error'; text: string } | null>(null);
  useEffect(() => {
    void githubApi.appLook(slug).then((l) => { setThemeId(l.themeId); setColors(l.colors); setCurLogo(l.logo); setLoaded(true); })
      .catch((e) => setMsg({ kind: 'error', text: e instanceof Error ? e.message : 'No se pudo leer la app.' }));
  }, [slug]);
  const logoFile = orig ? (useCut && cut ? cut : orig) : null;
  useEffect(() => {
    if (!logoFile) return;
    const u = URL.createObjectURL(logoFile); setPrev(u);
    return () => URL.revokeObjectURL(u);
  }, [logoFile]);
  const pickTheme = (id: string) => { setThemeId(id); const p = VENDOR_THEMES.find((t) => t.id === id)!.palette; setColors({ primary: p.primary!, accent: p.accent!, background: p.background! }); setDirty(true); };
  const pickLogo = async (f: File | undefined) => {
    if (!f) return; setNote('Preparando el logotipo…'); setMsg(null);
    try {
      const r0 = await fixImage(f, 'logo');
      const c = await removeLogoBackground(r0.file).catch(() => null);
      setOrig(r0.file); setCut(c?.removed || (c && c.file !== r0.file) ? c.file : null); setUseCut(true);
      const lc = await logoColors(c?.file ?? r0.file).catch(() => null);
      if (lc) { setThemeId(closestTheme(VENDOR_THEMES, lc.primary).id); setColors(lc); }
      setNote([c?.note, lc ? '🎨 Colores tomados del logotipo (puedes ajustarlos o elegir otro tema).' : ''].filter(Boolean).join(' '));
      setDirty(true);
    } catch (e) { setNote(e instanceof Error ? e.message : 'No se pudo abrir la imagen.'); }
  };
  const save = async () => {
    setBusy(true); setMsg(null);
    try {
      const r = await githubApi.restyleApp(slug, { themeId, colors, logo: logoFile });
      setMsg({ kind: 'ok', text: `✓ Publicado (versión ${r.version}). Sus clientes lo ven al abrir la app.${slug && logoFile ? ' El ícono de su APK se actualiza en unos 10 minutos.' : ''}` });
      setDirty(false); setOrig(null); setCut(null);
      if (currentTarget() === slug) setTimeout(() => location.reload(), 1500); // el estudio estaba abierto en esta app
    } catch (e) { setMsg({ kind: 'error', text: e instanceof Error ? e.message : 'No se pudo publicar.' }); }
    finally { setBusy(false); }
  };
  return (
    <div className="app-look" aria-label={`Tema y logotipo de ${name}`}>
      {!loaded && !msg && <span className="hint">Cargando…</span>}
      {loaded && <>
        <b>Tema con el que arranca la app</b> <span className="hint">Colores, fondo, letra, bordes y forma del menú. Puedes cambiarlo cuando quieras.</span>
        <ThemeGrid value={themeId} onPick={pickTheme} />
        <div className="vendor-brand">
          <div className="vendor-logo-pick">
            <div className="vendor-logo-box checker" style={{ background: colors.background }}>
              {prev && logoFile ? <img src={prev} alt="Logotipo nuevo" /> : curLogo ? <img src={curLogo} alt="Logotipo actual" /> : <span>Sin logotipo</span>}
            </div>
            <label className="btn-ghost">{curLogo || orig ? 'Cambiar logotipo' : 'Subir logotipo'}
              <input type="file" accept="image/*" hidden aria-label="Logotipo" onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ''; void pickLogo(f); }} />
            </label>
            {orig && cut && <label className="hint"><input type="checkbox" checked={useCut} onChange={(e) => setUseCut(e.target.checked)} /> Quitar el fondo (desmarca si prefieres la imagen original)</label>}
            {note && <span className="hint">{note}</span>}
          </div>
          <div className="vendor-colors">
            {([['primary', 'Color principal (botones)'], ['accent', 'Color de acento (foco y detalles)'], ['background', 'Fondo']] as const).map(([k, label]) => (
              <label key={k}><input type="color" value={colors[k]} onChange={(e) => { setColors((c) => ({ ...c, [k]: e.target.value })); setDirty(true); }} /> <span>{label}</span></label>
            ))}
            <div className="vendor-preview" style={{ background: colors.background }}>
              {(prev && logoFile) || curLogo ? <img src={prev && logoFile ? prev : curLogo!} alt="" style={{ height: 40 }} /> : null}
              <b style={{ color: colors.accent }}>{name}</b>
              <span style={{ background: colors.primary }} className="vp-btn">Reproducir</span>
            </div>
          </div>
        </div>
        <div className="dl-row">
          <button className="btn" disabled={busy || !dirty || !themeId} onClick={() => void save()}>{busy ? 'Publicando…' : 'Guardar y publicar'}</button>
          <button className="btn-ghost" onClick={onClose}>Cerrar</button>
          {!themeId && <span className="hint">Elige un tema para poder guardar.</span>}
        </div>
      </>}
      {msg && <Notice kind={msg.kind}>{msg.text}</Notice>}
    </div>
  );
}

export function VendorsPanel({ vendors, reload }: { vendors: Vendor[]; reload: () => void }) {
  const [name, setName] = useState('');
  const [mode, setMode] = useState<'mine' | 'ask'>('mine');
  const [welcome, setWelcome] = useState('');
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
      const r0 = await fixImage(f, 'logo');
      const cut = await removeLogoBackground(r0.file).catch(() => null);
      const r = cut ? { ...r0, file: cut.file, note: `${r0.changed ? r0.note + ' ' : ''}${cut.note}`, changed: true } : r0;
      setLogo(r.file);
      // Colores de la app tomados del logotipo (se pueden ajustar abajo).
      const lc = await logoColors(r.file).catch(() => null);
      if (lc) {
        setThemeId(closestTheme(VENDOR_THEMES, lc.primary).id); setColors(lc);
        setLogoNote(`${r.changed ? r.note + ' ' : ''}🎨 Colores tomados del logotipo: la app usará estos colores (puedes ajustarlos).`);
      } else setLogoNote(r.changed ? r.note : 'Imagen lista. El logotipo casi no tiene color: se quedan los colores del tema.');
      setLogoPrev((old) => { if (old) URL.revokeObjectURL(old); return URL.createObjectURL(r.file); });
    } catch (e) { setLogo(null); setLogoPrev(''); setLogoNote(e instanceof Error ? e.message : 'No se pudo abrir la imagen.'); }
  };
  const [look, setLook] = useState<string | null>(null); // app abierta en el editor rápido de tema y logotipo
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
    void run(async () => { const v = await githubApi.createVendor(name, { askHost: mode === 'ask', logo, colors, themeId, welcome }); setName(''); setWelcome(''); setLogo(null); setLogoPrev(''); setLogoNote(''); return v; },
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
          <ThemeGrid value={themeId} onPick={pickTheme} />
        </div>
        <b style={{ display: 'block', marginTop: 14 }}>2. Logotipo: la app toma sus colores automáticamente (puedes ajustarlos)</b>
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
        <label className="vendor-welcome">
          <b>4. Mensaje de bienvenida (opcional)</b>
          <input className="input" value={welcome} onChange={(e) => setWelcome(e.target.value)} maxLength={80} aria-label="Mensaje de bienvenida"
            placeholder={`Bienvenido a ${name.trim() || 'su app'}`} />
          <span className="hint">Aparece al abrir la app. Escribe {'{app}'} para poner el nombre de la app. Después se cambia en «Editar su app» → Textos.</span>
        </label>
      </Group>
      <Group title="Tus apps" hint="«Editar su app» abre su diseño en el estudio; al publicar solo cambia esa app.">
        <div className={`vendor ${cur === '' ? 'on' : ''}`}>
          <div className="vendor-head"><b>App principal</b>{cur === '' ? <span className="pill pill-ok">Editando</span> : <button className="btn-ghost" onClick={() => { setTarget(''); location.reload(); }}>Editar su app</button>}
            <button className="btn-ghost" onClick={() => setLook(look === '' ? null : '')} aria-expanded={look === ''}>🎨 Tema y logotipo</button></div>
          {look === '' && <AppLookEditor slug="" name="App principal" onClose={() => setLook(null)} />}
          <Links slug="" />
        </div>
        {vendors.length === 0 && <p className="hint">Todavía no hay vendedores.</p>}
        {vendors.map((v) => (
          <div key={v.slug} className={`vendor ${cur === v.slug ? 'on' : ''}`}>
            <div className="vendor-head">
              <b>{v.name}</b><span className="hint">id: {v.slug}</span>
              {cur === v.slug ? <span className="pill pill-ok">Editando</span> : <button className="btn-ghost" onClick={() => { setTarget(v.slug); location.reload(); }}>Editar su app</button>}
              <button className="btn-ghost" onClick={() => setLook(look === v.slug ? null : v.slug)} aria-expanded={look === v.slug}>🎨 Tema y logotipo</button>
              <button className="link danger" disabled={busy} onClick={() => { if (confirm(`¿Eliminar la app de ${v.name}? Su APK dejará de actualizarse.`)) void run(() => githubApi.deleteVendor(v.slug), `${v.name} eliminado`); }}>Eliminar</button>
            </div>
            {look === v.slug && <AppLookEditor slug={v.slug} name={v.name} onClose={() => setLook(null)} />}
            <Links slug={v.slug} />
          </div>
        ))}
      </Group>
      <p className="hint">Pasos para una app nueva: 1) escribe el nombre, elige un tema, sube su logotipo y elige el servidor · 2) «Crear app de vendedor» (se publica sola) · 3) si quieres más cambios, «Editar su app» y «Publicar» · 4) cuando aparezca «✓ APK lista» (5–15 minutos), saca su código de Downloader con AFTVnews (botón «Copiar enlace y abrir AFTVnews»), pégalo y entrégaselo a tu vendedor. Cada versión nueva le llega sola a sus clientes.</p>
    </div>
  );
}

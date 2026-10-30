import { useCallback, useEffect, useLayoutEffect, useRef, useState, type FormEvent } from 'react';
import { createRoot } from 'react-dom/client';
import type { UserAppearance } from '../../backend/src/config/resolve';
import type { RemoteConfig } from '../../backend/src/config/schema';
import { api, ApiError, IS_GITHUB, type Asset, type User, type Validation } from './api';
import { AccountPanel, AssetsPanel, AuditPanel, PublishDialog, UsersPanel, VersionsPanel } from './components/manage';
import { AppearancePanel, BannersPanel, ColorsPanel, HomeBuilderPanel, IdentityPanel, TextsPanel, UserOptionsPanel, type PanelProps } from './components/panels';
import { Notice, Segmented } from './components/ui';
import { AppPreview, DEVICES, type Device } from './preview/AppPreview';
import { useEditor, type Editor } from './store';
import { TargetSelect, VendorsPanel } from './components/vendors';
import { currentTarget, githubApi, type Vendor } from './api-github';
import './styles.css';

type View = 'identity' | 'colors' | 'home' | 'appearance' | 'banners' | 'user' | 'texts' | 'assets' | 'versions' | 'users' | 'audit' | 'account' | 'vendors';
const DESIGN_VIEWS: { id: View; label: string }[] = [
  { id: 'home', label: 'Inicio' }, { id: 'identity', label: 'Identidad' }, { id: 'colors', label: 'Colores y temas' },
  { id: 'appearance', label: 'Formas y menú' }, { id: 'banners', label: 'Banners' }, { id: 'user', label: 'Opciones del usuario' }, { id: 'texts', label: 'Textos' },
];

function GitHubLogin({ onDone }: { onDone: (u: User) => void }) {
  const [tok, setTok] = useState('');
  const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null);
    try { onDone(await api.login('', tok)); } catch (err) { setError(err instanceof ApiError ? err.message : 'No se pudo conectar con GitHub'); } finally { setBusy(false); }
  };
  return (
    <main className="login">
      <form onSubmit={submit} className="login-card">
        <img src="/brand/embyverse-logo.webp" alt="EmbyVerse" className="login-logo" />
        <h1>Design Studio</h1>
        <p className="hint">Cambia nombre, logo, colores, inicio y banners de tu app y tu web. Se entra con un <b>token de GitHub</b> (solo se guarda en este navegador).</p>
        <ol className="hint gh-steps">
          <li>Abre <a href="https://github.com/settings/personal-access-tokens/new" target="_blank" rel="noreferrer">crear token de GitHub</a>.</li>
          <li>Nombre: <i>Design Studio</i>. Caducidad: la que quieras.</li>
          <li><b>Repository access</b> → <i>Only select repositories</i> → <b>EmbyVerseTV</b>.</li>
          <li><b>Permissions</b> → <i>Contents</i> → <b>Read and write</b>.</li>
          <li><b>Generate token</b>, cópialo y pégalo aquí.</li>
        </ol>
        {error && <Notice kind="error">{error}</Notice>}
        <div className="field"><label htmlFor="tok">Token de GitHub</label><input id="tok" type="password" autoComplete="off" required value={tok} onChange={(e) => setTok(e.target.value)} placeholder="github_pat_…" /></div>
        <button className="btn btn-wide" disabled={busy}>{busy ? 'Comprobando…' : 'Entrar'}</button>
      </form>
    </main>
  );
}

function Login({ onDone }: { onDone: (u: User) => void }) {
  if (IS_GITHUB) return <GitHubLogin onDone={onDone} />;
  const [email, setEmail] = useState(''); const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null); const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true); setError(null);
    try { onDone(await api.login(email, password)); } catch (err) { setError(err instanceof ApiError ? err.message : 'No se pudo conectar con el servidor'); } finally { setBusy(false); }
  };
  return (
    <main className="login">
      <form onSubmit={submit} className="login-card">
        <img src="/brand/embyverse-logo.webp" alt="EmbyVerse" className="login-logo" />
        <h1>Design Studio</h1>
        <p className="hint">Acceso privado para el equipo que gestiona el diseño de la app.</p>
        {error && <Notice kind="error">{error}</Notice>}
        <div className="field"><label htmlFor="email">Email</label><input id="email" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} /></div>
        <div className="field"><label htmlFor="pw">Contraseña</label><input id="pw" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} /></div>
        <button className="btn btn-wide" disabled={busy}>{busy ? 'Entrando…' : 'Entrar'}</button>
      </form>
    </main>
  );
}

function PreviewCanvas({ ed, device, setDevice, selected, onSelect, publicBase }: { ed: Editor; device: Device; setDevice: (d: Device) => void; selected: string | null; onSelect: (id: string) => void; publicBase: string }) {
  const box = useRef<HTMLDivElement>(null);
  const [scale, setScale] = useState(1);
  const [asUser, setAsUser] = useState<UserAppearance>({});
  const [now, setNow] = useState(() => Date.now() / 1000);
  useEffect(() => { const t = setInterval(() => setNow(Date.now() / 1000), 30_000); return () => clearInterval(t); }, []);
  useLayoutEffect(() => {
    const el = box.current; if (!el) return;
    const fit = () => { const d = DEVICES[device]; setScale(Math.min(1, (el.clientWidth - 48) / d.w, (el.clientHeight - 32) / d.h)); };
    fit(); const ro = new ResizeObserver(fit); ro.observe(el); return () => ro.disconnect();
  }, [device]);
  const uc = ed.config.userCustomization;
  const d = DEVICES[device];
  return (
    <section className="canvas" aria-label="Vista previa">
      <div className="canvas-bar">
        <Segmented value={device} onChange={setDevice} options={(Object.keys(DEVICES) as Device[]).map((k) => ({ value: k, label: DEVICES[k].label }))} />
        <div className="as-user">
          <span className="hint">Ver como usuario:</span>
          <select aria-label="Tema del usuario" value={asUser.themeId ?? ''} disabled={!uc.allowThemeSelection} onChange={(e) => setAsUser({ ...asUser, themeId: e.target.value || null })}>
            <option value="">Diseño original</option>{ed.config.themes.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
          <select aria-label="Tamaño de tarjetas del usuario" value={asUser.cardSize ?? ''} disabled={!uc.allowCardSize} onChange={(e) => setAsUser({ ...asUser, cardSize: (e.target.value || null) as UserAppearance['cardSize'] })}>
            <option value="">Tarjetas según diseño</option><option value="s">Pequeñas</option><option value="m">Medianas</option><option value="l">Grandes</option>
          </select>
          {uc.allowAccentColor && <span className="accent-dots">{uc.accentChoices.map((c) => (
            <button key={c} aria-label={`Acento ${c}`} className={asUser.accentColor === c ? 'on' : ''} style={{ background: c.length === 9 ? `#${c.slice(3)}` : c }} onClick={() => setAsUser({ ...asUser, accentColor: asUser.accentColor === c ? null : c })} />
          ))}</span>}
        </div>
      </div>
      <div className="canvas-stage" ref={box}>
        <div className="device-frame" data-device={device} style={{ width: d.w * scale, height: d.h * scale }}>
          <div style={{ transform: `scale(${scale})`, transformOrigin: 'top left', width: d.w, height: d.h }}>
            <AppPreview config={ed.config} device={device} prefs={asUser} selected={selected} onSelect={onSelect} publicBase={publicBase} now={now} />
          </div>
        </div>
        <p className="canvas-caption">{d.label} · {d.w}×{d.h} dp · {Math.round(scale * 100)} %</p>
      </div>
    </section>
  );
}

function Studio({ user, onLogout }: { user: User; onLogout: () => void }) {
  const [loaded, setLoaded] = useState<{ config: RemoteConfig; baseVersion: number; latestVersion: number; publicBase: string } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  // El aviso vive aquí para sobrevivir a la recarga del editor tras publicar.
  const [toast, setToast] = useState<{ kind: 'ok' | 'error' | 'info'; text: string } | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const flash = useCallback((kind: 'ok' | 'error' | 'info', text: string) => {
    setToast({ kind, text }); clearTimeout(timer.current); timer.current = setTimeout(() => setToast(null), 4500);
  }, []);
  const reloadDraft = useCallback(async () => {
    try { const d = await api.draft(); setLoaded({ config: d.config, baseVersion: d.baseVersion, latestVersion: d.latestVersion, publicBase: d.publicBaseUrl }); }
    catch (e) { setLoadError(e instanceof ApiError ? e.message : 'No se pudo cargar el borrador'); }
  }, []);
  useEffect(() => { void reloadDraft(); }, [reloadDraft]);
  if (loadError) return <main className="center"><Notice kind="error">{loadError}</Notice></main>;
  if (!loaded) return <main className="center"><p className="hint">Cargando el estudio…</p></main>;
  return (
    <>
      <StudioLoaded key={`${loaded.latestVersion}-${loaded.baseVersion}-${JSON.stringify(loaded.config).length}`} user={user} loaded={loaded} reloadDraft={reloadDraft} onLogout={onLogout} flash={flash} />
      {toast && <div className={`toast toast-${toast.kind}`} role="status">{toast.text}</div>}
    </>
  );
}

function StudioLoaded({ user, loaded, reloadDraft, onLogout, flash }: { user: User; loaded: { config: RemoteConfig; baseVersion: number; latestVersion: number; publicBase: string }; reloadDraft: () => Promise<void>; onLogout: () => void; flash: (k: 'ok' | 'error' | 'info', t: string) => void }) {
  const ed = useEditor(loaded.config);
  const [view, setView] = useState<View>('home');
  const [device, setDevice] = useState<Device>('phone');
  const [selected, setSelected] = useState<string | null>(null);
  const [assets, setAssets] = useState<Asset[]>([]);
  const [publishing, setPublishing] = useState<Validation | null | false>(false);
  const [saving, setSaving] = useState(false);
  const canEdit = user.permissions.includes('config:edit');
  const canPublish = user.permissions.includes('config:publish');

  const loadAssets = useCallback(() => { api.assets().then((r) => setAssets(r.assets)).catch(() => {}); }, []);
  useEffect(loadAssets, [loadAssets]);
  const [vendors, setVendors] = useState<Vendor[]>([]);
  const loadVendors = useCallback(() => { if (IS_GITHUB) githubApi.vendors().then(setVendors).catch(() => {}); }, []);
  useEffect(loadVendors, [loadVendors]);
  const editing = IS_GITHUB && currentTarget() ? vendors.find((v) => v.slug === currentTarget())?.name ?? currentTarget() : null;

  const upload = async (f: File, kind: Asset['kind']) => {
    try { const r = await api.upload(f, kind); loadAssets(); return r.asset; }
    catch (e) { flash('error', e instanceof ApiError ? e.message : 'No se pudo subir la imagen'); return null; }
  };

  const save = useCallback(async () => {
    if (!canEdit) return null;
    setSaving(true);
    try { const r = await api.saveDraft(ed.config); ed.markSaved(ed.config); flash(r.validation.ok ? 'ok' : 'info', r.validation.ok ? 'Borrador guardado' : 'Borrador guardado con errores: no se podrá publicar hasta corregirlos'); return r.validation; }
    catch (e) { flash('error', e instanceof ApiError ? e.message : 'No se pudo guardar'); return null; }
    finally { setSaving(false); }
  }, [canEdit, ed]);

  const openPublish = async () => {
    setPublishing(null);
    try { const v = ed.dirty ? await api.validate(ed.config) : (await api.draft()).validation; setPublishing(v); }
    catch (e) { setPublishing(false); flash('error', e instanceof ApiError ? e.message : 'No se pudo validar'); }
  };

  const publish = async (note: string) => {
    if (ed.dirty && !(await save())) return;
    try {
      const r = await api.publish(note).catch(async (e) => {
        if (e instanceof ApiError && e.code === 'stale_draft' && confirm(`${e.message}\n\n¿Publicar de todos modos este borrador?`)) return api.publish(note, true);
        throw e;
      });
      setPublishing(false); flash('ok', IS_GITHUB ? `Publicada la versión ${r.version}. Llega a la app y la web en 1–2 minutos (al abrirlas). Si cambiaste el nombre o el logo, en unos 10 minutos habrá una APK nueva.` : `Publicada la versión ${r.version}`); await reloadDraft();
    } catch (e) { flash('error', e instanceof ApiError ? e.message : 'No se pudo publicar'); }
  };

  useEffect(() => {
    const k = (e: KeyboardEvent) => {
      const mod = e.ctrlKey || e.metaKey;
      if (!mod) return;
      const target = e.target as HTMLElement;
      if (e.key.toLowerCase() === 's') { e.preventDefault(); void save(); return; }
      if (['INPUT', 'TEXTAREA'].includes(target.tagName) && (target as HTMLInputElement).type !== 'range' && (target as HTMLInputElement).type !== 'checkbox') return;
      if (e.key.toLowerCase() === 'z') { e.preventDefault(); if (e.shiftKey) ed.redo(); else ed.undo(); }
      if (e.key.toLowerCase() === 'y') { e.preventDefault(); ed.redo(); }
    };
    window.addEventListener('keydown', k);
    const leave = (e: BeforeUnloadEvent) => { if (ed.dirty) e.preventDefault(); };
    window.addEventListener('beforeunload', leave);
    return () => { window.removeEventListener('keydown', k); window.removeEventListener('beforeunload', leave); };
  }, [ed, save]);

  const isDesign = DESIGN_VIEWS.some((v) => v.id === view);
  const props: PanelProps = { ed, canEdit, assets, publicBase: loaded.publicBase, selected, setSelected, onUpload: upload };
  const errors = ed.validation.errors;
  const manage: { id: View; label: string; show: boolean }[] = [
    { id: 'vendors', label: 'Vendedores', show: IS_GITHUB },
    { id: 'assets', label: 'Recursos', show: user.permissions.includes('assets:read') },
    { id: 'versions', label: 'Versiones', show: true },
    { id: 'users', label: 'Usuarios', show: user.permissions.includes('users:manage') },
    { id: 'audit', label: 'Auditoría', show: user.permissions.includes('audit:read') },
    { id: 'account', label: 'Mi cuenta', show: !IS_GITHUB },
  ];

  return (
    <div className="studio">
      <header className="topbar">
        <div className="brand"><img src="/brand/embyverse-icon.png" alt="" /><span>EmbyVerse <b>Design Studio</b></span></div>
        <div className="status">
          {IS_GITHUB && <TargetSelect vendors={vendors} />}
          {editing && <span className="pill pill-warn">App de {editing}</span>}
          {loaded.latestVersion > 0 ? <span className="pill pill-ok">En la app: versión {loaded.latestVersion}</span> : <span className="pill pill-muted">Sin publicar: la app usa el diseño integrado</span>}
          {ed.dirty ? <span className="pill pill-warn">Cambios sin guardar</span> : <span className="hint">Borrador guardado</span>}
          {!loaded.publicBase.startsWith('https://') && <span className="pill pill-warn" title="Configura PUBLIC_BASE_URL con https en el servidor">Las imágenes subidas no se pueden publicar: el servidor no tiene URL pública https</span>}
        </div>
        <div className="actions">
          <button className="icon-btn" onClick={ed.undo} disabled={!ed.canUndo} title="Deshacer (Ctrl+Z)" aria-label="Deshacer">↶</button>
          <button className="icon-btn" onClick={ed.redo} disabled={!ed.canRedo} title="Rehacer (Ctrl+Shift+Z)" aria-label="Rehacer">↷</button>
          {canEdit && <button className="btn-ghost" disabled={!ed.dirty} onClick={() => { if (confirm('¿Descartar los cambios no guardados?')) void reloadDraft(); }}>Descartar cambios</button>}
          {canEdit && <button className="btn" disabled={saving || !ed.dirty} onClick={() => void save()}>{saving ? 'Guardando…' : 'Guardar borrador'}</button>}
          {canPublish && <button className="btn-publish" onClick={() => void openPublish()}>Publicar…</button>}
          <button className="btn-ghost" onClick={async () => { await api.logout().catch(() => {}); onLogout(); }}>Salir</button>
        </div>
      </header>

      <nav className="sidenav" aria-label="Secciones del estudio">
        <span className="nav-group">Diseño</span>
        {DESIGN_VIEWS.map((v) => <button key={v.id} className={view === v.id ? 'on' : ''} onClick={() => setView(v.id)}>{v.label}</button>)}
        <span className="nav-group">Gestión</span>
        {manage.filter((m) => m.show).map((v) => <button key={v.id} className={view === v.id ? 'on' : ''} onClick={() => setView(v.id)}>{v.label}</button>)}
      </nav>

      <main className={`workspace ${isDesign ? 'with-canvas' : ''}`}>
        <div className="inspector">
          {!canEdit && isDesign && <Notice kind="info">Tu rol es de solo lectura: puedes ver el diseño pero no modificarlo.</Notice>}
          {view === 'identity' && <IdentityPanel {...props} />}
          {view === 'colors' && <ColorsPanel {...props} />}
          {view === 'home' && <HomeBuilderPanel {...props} />}
          {view === 'appearance' && <AppearancePanel {...props} />}
          {view === 'banners' && <BannersPanel {...props} />}
          {view === 'user' && <UserOptionsPanel {...props} />}
          {view === 'texts' && <TextsPanel {...props} />}
          {view === 'assets' && <AssetsPanel user={user} assets={assets} reload={loadAssets} onUpload={upload} />}
          {view === 'versions' && <VersionsPanel user={user} latest={loaded.latestVersion} onRestored={() => void reloadDraft()} onRolledBack={() => void reloadDraft()} />}
          {view === 'users' && <UsersPanel me={user} />}
          {view === 'audit' && <AuditPanel />}
          {view === 'account' && <AccountPanel me={user} />}
          {view === 'vendors' && <VendorsPanel vendors={vendors} reload={loadVendors} />}
          {isDesign && (
            <div className={`validation ${errors.length ? 'has-errors' : ''}`} role="status">
              {errors.length === 0 ? 'Configuración válida' : <details><summary>{errors.length} problema(s) impiden publicar</summary><ul>{errors.map((e) => <li key={e}>{e}</li>)}</ul></details>}
            </div>
          )}
        </div>
        {isDesign && <PreviewCanvas ed={ed} device={device} setDevice={setDevice} selected={selected} onSelect={(id) => { setSelected(id); setView('home'); }} publicBase={loaded.publicBase} />}
      </main>

      {publishing !== false && <PublishDialog validation={publishing} dirty={ed.dirty} onClose={() => setPublishing(false)} onPublish={publish} />}
    </div>
  );
}

function App() {
  const [user, setUser] = useState<User | null | undefined>(undefined);
  useEffect(() => { api.me().then(setUser).catch(() => setUser(null)); }, []);
  if (user === undefined) return <main className="center"><p className="hint">Cargando…</p></main>;
  if (user === null) return <Login onDone={setUser} />;
  return <Studio user={user} onLogout={() => setUser(null)} />;
}

createRoot(document.getElementById('root')!).render(<App />);

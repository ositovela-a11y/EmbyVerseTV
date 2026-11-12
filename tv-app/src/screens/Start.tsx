import { useEffect, useState } from 'react';
import { Fx, Logo, useApp } from '../ui';
import { isVendorApp, providerHost } from '../config';
import { clientFor, xtreamFromM3uUrl } from '../m3u';
import { store, type Account } from '../store';

/** Entrada «universo» (~8 s): viaje entre estrellas, galaxia, logo y bienvenida. */
export function Splash({ onDone }: { onDone: () => void }) {
  const { config } = useApp();
  const msgs = [config.brand.tagline || 'Tu universo de entretenimiento', 'Películas, series y TV en vivo', 'Preparando tu universo…'];
  const [i, setI] = useState(0);
  useEffect(() => {
    if (store.prefs().intro) { try { const a = new Audio('intro.ogg'); a.volume = 0.6; a.play().catch(() => {}); } catch { /* */ } }
    const t = setInterval(() => setI((x) => Math.min(x + 1, msgs.length - 1)), 1900);
    const end = setTimeout(onDone, store.prefs().lite ? 2500 : 7800);
    return () => { clearInterval(t); clearTimeout(end); };
  }, []);
  return (
    <div className="splash" onClick={onDone}>
      <div className="stars s1" /><div className="stars s2" /><div className="stars s3" />
      <div className="galaxy" />
      <div className="orbit o1"><i /></div><div className="orbit o2"><i /></div>
      <Logo className="splash-logo" />
      <h1>Bienvenido a {config.brand.appName}</h1>
      <p key={i} className="splash-msg">{msgs[i]}</p>
      {isVendorApp(config) && <p className="splash-credit" style={{ position: 'absolute', bottom: 48, width: '100%', textAlign: 'center', opacity: 0.6, fontSize: 22 }}>App diseñada por el equipo de EmbyVerse</p>}
    </div>
  );
}

/** Iniciar sesión: con el servidor ya puesto (solo usuario y contraseña) o pidiendo la URL. */
export function Login({ onLogged }: { onLogged: () => void }) {
  const { config } = useApp();
  const fixed = providerHost(config);
  const [host, setHost] = useState(fixed ?? '');
  const [user, setUser] = useState(''); const [pass, setPass] = useState('');
  const [busy, setBusy] = useState(false); const [err, setErr] = useState('');
  // Dos formas de entrar: cuenta (usuario y contraseña) o lista M3U (enlace o, en la PC, archivo).
  const [mode, setMode] = useState<'account' | 'm3u'>('account');
  const [list, setList] = useState('');
  const desk = (window as any).evDesktop;
  const submitM3u = async (src: string) => {
    setErr('');
    const s = src.trim();
    if (!/^https?:\/\//i.test(s) && !s.startsWith('file:')) { setErr('Pega el enlace de tu lista (empieza con http:// o https://).'); return; }
    setBusy(true);
    try {
      // «get.php?username=…&password=…» es una cuenta: se usa completa (fichas, guía, temporadas).
      const asXtream = s.startsWith('http') ? xtreamFromM3uUrl(s) : null;
      const acc: Account = asXtream ?? { host: '', username: '', password: '', m3u: s };
      const r = await clientFor(acc).login();
      store.setAccount({ ...acc, expiresAt: r.expiresAt }); onLogged();
    } catch (e: any) { setErr(e?.message ?? 'No se pudo abrir la lista.'); } finally { setBusy(false); }
  };
  const openFile = async () => { try { const f = await desk?.pickListFile?.(); if (f) await submitM3u(`file:${f}`); } catch (e: any) { setErr(e?.message ?? 'No se pudo abrir el archivo.'); } };
  const submit = async () => {
    if (mode === 'm3u') { await submitM3u(list); return; }
    setErr('');
    const h = (fixed ?? host).trim();
    if (!h || !/^https?:\/\//i.test(h)) { setErr('Escribe la dirección del servidor (empieza con http:// o https://).'); return; }
    if (!user.trim() || !pass) { setErr('Escribe tu usuario y contraseña.'); return; }
    setBusy(true);
    try {
      const acc = { host: h, username: user.trim(), password: pass };
      const r = await clientFor(acc).login();
      store.setAccount({ ...acc, expiresAt: r.expiresAt }); onLogged();
    } catch (e: any) { setErr(e?.message ?? 'No se pudo iniciar sesión.'); } finally { setBusy(false); }
  };
  const field = (id: string, label: string, v: string, set: (s: string) => void, type = 'text', def = false) => (
    <Fx className="field" def={def} onClick={() => (document.getElementById(id) as HTMLInputElement)?.focus()}>
      <label>{label}</label>
      <input id={id} type={type} value={v} onChange={(e) => set(e.target.value)} autoComplete="off"
        onKeyDown={(e) => { const k = e.keyCode; if (k === 13 || k === 40 || k === 38 || k === 10009 || k === 461 || k === 27) { (e.target as HTMLInputElement).blur(); if (k !== 13) e.preventDefault(); return; } e.stopPropagation(); }} />
    </Fx>
  );
  return (
    <div className="screen login">
      <Logo className="login-logo" />
      <h1>Inicia sesión</h1>
      <p className="muted">{mode === 'm3u' ? 'Pega el enlace de tu lista M3U. Si es de tipo «get.php?username=…», se usa como cuenta completa.' : fixed ? 'Xtream Codes: escribe el usuario y contraseña que te dio tu proveedor.' : 'Xtream Codes: escribe la dirección (URL) del servidor, usuario y contraseña que te dio tu proveedor.'}</p>
      <div className="login-tabs" data-fx-group="">
        <Fx className={`chip ${mode === 'account' ? 'on' : ''}`} onClick={() => { setMode('account'); setErr(''); }} id="tab-xtream">Xtream Codes</Fx>
        <Fx className={`chip ${mode === 'm3u' ? 'on' : ''}`} onClick={() => { setMode('m3u'); setErr(''); }} id="tab-m3u">Lista M3U</Fx>
      </div>
      {mode === 'account' ? (
        <div className="login-form" data-fx-group="">
          {!fixed && field('f-host', 'Servidor (URL)', host, setHost, 'url', true)}
          {field('f-user', 'Usuario', user, setUser, 'text', !!fixed)}
          {field('f-pass', 'Contraseña', pass, setPass, 'password')}
          <Fx className="btn primary big" onClick={submit}>{busy ? 'Conectando…' : 'Entrar'}</Fx>
        </div>
      ) : (
        <div className="login-form" data-fx-group="">
          {field('f-m3u', 'Enlace de la lista (M3U / M3U8)', list, setList, 'url', true)}
          <Fx className="btn primary big" onClick={submit} id="m3u-open">{busy ? 'Cargando lista…' : 'Abrir lista'}</Fx>
          {desk?.pickListFile && <Fx className="btn big" onClick={openFile} id="m3u-file">Abrir archivo .m3u de la PC</Fx>}
        </div>
      )}
      {err && <div className="error-box">{err}</div>}
    </div>
  );
}

export function Suspended() {
  const { config } = useApp();
  const wa = (config.texts['support.whatsapp'] ?? '').replace(/\D/g, '');
  return (
    <div className="screen suspended" data-fx-layer="">
      <Logo className="login-logo" />
      <h1>Servicio suspendido</h1>
      <p>{config.texts['blocked.message']?.trim() || 'Tu servicio está suspendido. Comunícate con tu proveedor para reactivarlo.'}</p>
      {wa && <p className="muted">WhatsApp: +{wa}</p>}
      <Fx className="btn" def onClick={() => location.reload()}>Reintentar</Fx>
    </div>
  );
}

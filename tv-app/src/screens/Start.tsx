import { useEffect, useState } from 'react';
import { Fx, Logo, useApp } from '../ui';
import { providerHost } from '../config';
import { Xtream } from '../xtream';
import { store } from '../store';

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
  const submit = async () => {
    setErr('');
    const h = (fixed ?? host).trim();
    if (!h || !/^https?:\/\//i.test(h)) { setErr('Escribe la dirección del servidor (empieza con http:// o https://).'); return; }
    if (!user.trim() || !pass) { setErr('Escribe tu usuario y contraseña.'); return; }
    setBusy(true);
    try {
      const acc = { host: h, username: user.trim(), password: pass };
      const r = await new Xtream(acc).login();
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
      <p className="muted">{fixed ? 'Escribe el usuario y contraseña que te dio tu proveedor.' : 'Escribe la dirección (URL), usuario y contraseña que te dio tu proveedor.'}</p>
      <div className="login-form" data-fx-group="">
        {!fixed && field('f-host', 'Servidor (URL)', host, setHost, 'url', true)}
        {field('f-user', 'Usuario', user, setUser, 'text', !!fixed)}
        {field('f-pass', 'Contraseña', pass, setPass, 'password')}
        <Fx className="btn primary big" onClick={submit}>{busy ? 'Conectando…' : 'Entrar'}</Fx>
      </div>
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

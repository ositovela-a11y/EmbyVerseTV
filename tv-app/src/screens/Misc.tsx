import { checkLicense, deviceCode, licenseRequired, type LicState } from '../license';
import { lgDevSupported, lgDevLast, lgDevError, renewLgDevMode } from '../lgdev';
import { useEffect, useState } from 'react';
import { Card, Fx, Img, Row, Spinner, useApp, useLoad } from '../ui';
import { search, liveIn, client, clearCache } from '../catalog';
import { store, type Profile } from '../store';
import { SearchBox } from './Live';
import { deviceInfo, platformName } from '../platform';
import { getEngine } from '../player';
import type { EpgItem } from '../xtream';

declare const __VERSION__: string;

export function Search() {
  const app = useApp();
  const [q, setQ] = useState('');
  const [deb, setDeb] = useState('');
  useEffect(() => { const t = setTimeout(() => setDeb(q), 400); return () => clearTimeout(t); }, [q]);
  const r = useLoad(async () => { if (deb.trim().length < 2) return null; const res = await search(deb); if (res.live.length + res.movies.length + res.series.length) store.addSearch(deb); return res; }, [deb, app.profile.id]);
  const recent = store.searches();
  return (
    <div className="screen search">
      <div className="lib-head"><h1>Buscar</h1></div>
      <SearchBox def value={q} onChange={setQ} placeholder="Película, serie, canal o género (p. ej. «terror»)" />
      {!q && recent.length > 0 && <Row title="Búsquedas recientes">{recent.map((s) => <Fx key={s} className="chip" onClick={() => setQ(s)}>{s}</Fx>)}</Row>}
      {r.loading && deb && <Spinner label="Buscando…" />}
      {r.data && r.data.live.length + r.data.movies.length + r.data.series.length === 0 && <div className="empty">Sin resultados para «{deb}».</div>}
      {r.data && r.data.live.length > 0 && <Row title="Canales">{r.data.live.map((c) => <Card key={c.id} wide title={c.name} image={c.logo} onClick={() => app.play({ kind: 'live', id: c.id, title: c.name, image: c.logo })} />)}</Row>}
      {r.data && r.data.movies.length > 0 && <Row title="Películas">{r.data.movies.map((m) => <Card key={m.id} title={m.name} image={m.poster} sub={m.year ? String(m.year) : undefined} onClick={() => app.go({ name: 'movie', id: m.id })} />)}</Row>}
      {r.data && r.data.series.length > 0 && <Row title="Series">{r.data.series.map((m) => <Card key={m.id} title={m.name} image={m.poster} onClick={() => app.go({ name: 'show', id: m.id })} />)}</Row>}
    </div>
  );
}

export function Favorites() {
  const app = useApp();
  const [favs, setFavs] = useState(() => store.favs(app.profile.id));
  const [ordering, setOrdering] = useState(false);
  const move = (i: number, d: number) => { const l = favs.slice(); const j = i + d; if (j < 0 || j >= l.length) return; [l[i], l[j]] = [l[j], l[i]]; store.setFavs(app.profile.id, l); setFavs(l); };
  const open = (f: typeof favs[number]) => f.kind === 'live' ? app.play({ kind: 'live', id: f.id, title: f.title, image: f.image }) : app.go(f.kind === 'movie' ? { name: 'movie', id: f.id } : { name: 'show', id: f.id });
  return (
    <div className="screen library">
      <div className="lib-head"><h1>Favoritos</h1><div className="grow" />{favs.length > 1 && <Fx className="btn" onClick={() => setOrdering(!ordering)}>{ordering ? 'Listo' : 'Ordenar'}</Fx>}</div>
      {favs.length === 0 && <div className="empty">Aún no tienes favoritos. Márcalos con ♡ en las fichas o en el reproductor.</div>}
      {ordering ? <div className="order-list">{favs.map((f, i) => <div key={f.kind + f.id} className="order-item"><b>{i + 1}</b><span>{f.title}</span>
        <Fx className="btn small" def={i === 0} onClick={() => move(i, -1)}>▲</Fx><Fx className="btn small" onClick={() => move(i, 1)}>▼</Fx></div>)}</div>
        : <div className="grid" data-fx-group="">{favs.map((f, i) => <Card key={f.kind + f.id} def={i === 0} wide={f.kind === 'live'} title={f.title} image={f.image} onClick={() => open(f)} />)}</div>}
    </div>
  );
}

export function Guide() {
  const app = useApp();
  const ch = useLoad(async () => (await liveIn()).slice(0, 40), [app.profile.id]);
  const [epg, setEpg] = useState<Record<string, EpgItem[]>>({});
  useEffect(() => {
    let alive = true;
    (async () => { for (const c of ch.data ?? []) { if (!alive) return; const e = await client().shortEpg(c.id, 5); if (alive) setEpg((p) => ({ ...p, [c.id]: e })); } })();
    return () => { alive = false; };
  }, [ch.data]);
  const fmt = (ms: number) => new Date(ms).toLocaleTimeString('es-MX', { hour: 'numeric', minute: '2-digit' });
  return (
    <div className="screen guide">
      <div className="lib-head"><h1>Guía de TV</h1></div>
      {ch.loading && <Spinner />}
      <div className="guide-list">
        {(ch.data ?? []).map((c, i) => (
          <div key={c.id} className="guide-row" data-fx-group="">
            <Fx className="guide-ch" def={i === 0} onClick={() => app.play({ kind: 'live', id: c.id, title: c.name, image: c.logo, list: ch.data!.map((x) => ({ id: x.id, title: x.name, image: x.logo, num: x.num })) })}>
              <Img src={c.logo} alt={c.name} className="ch-logo" fit="contain" /><span>{c.name}</span></Fx>
            {(epg[c.id] ?? []).map((e, j) => <Fx key={j} className={`guide-prog ${e.start <= Date.now() && e.end > Date.now() ? 'now' : ''}`}
              onClick={() => app.play({ kind: 'live', id: c.id, title: c.name, image: c.logo })}><small>{fmt(e.start)}</small><b>{e.title}</b></Fx>)}
            {epg[c.id] && epg[c.id].length === 0 && <span className="muted">Sin guía</span>}
          </div>
        ))}
      </div>
    </div>
  );
}

export function Settings() {
  const app = useApp();
  const [p, setP] = useState(store.prefs());
  const upd = (v: Partial<typeof p>) => { store.setPrefs(v); setP(store.prefs()); };
  const acc = store.account();
  const [speed, setSpeed] = useState<string>('');
  const measure = async () => {
    setSpeed('Midiendo… (unos 10 segundos)');
    try {
      const t0 = performance.now(); const r = await fetch(`https://speed.cloudflare.com/__down?bytes=30000000&t=${Date.now()}`);
      const reader = r.body!.getReader(); let bytes = 0;
      while (performance.now() - t0 < 9000) { const { done, value } = await reader.read(); if (done) break; bytes += value!.length; }
      try { reader.cancel(); } catch { /* */ }
      const mbps = (bytes * 8) / 1e6 / ((performance.now() - t0) / 1000);
      setSpeed(`${mbps.toFixed(1)} Mbps · ${mbps >= 25 ? 'alcanza para 4K' : mbps >= 12 ? 'alcanza para Full HD' : mbps >= 6 ? 'alcanza para HD' : 'alcanza para SD; para HD se recomiendan 8 Mbps'}`);
    } catch { setSpeed('No se pudo medir. Revisa tu conexión.'); }
  };
  const wa = (app.config.texts['support.whatsapp'] ?? '').replace(/\D/g, '');
  const [pinFor, setPinFor] = useState(false);
  const saveProfile = (v: Partial<Profile>) => { store.setProfiles(store.profiles().map((x) => (x.id === app.profile.id ? { ...x, ...v } : x))); app.refreshProfile(); };
  const Toggle = ({ label, v, k }: { label: string; v: boolean; k: keyof typeof p }) => <Fx className="setting" onClick={() => upd({ [k]: !v } as any)}><span>{label}</span><b className={v ? 'on' : ''}>{v ? 'Sí' : 'No'}</b></Fx>;
  const Choice = ({ label, opts, v, k }: { label: string; opts: string[]; v: number; k: keyof typeof p }) => <Fx className="setting" onClick={() => upd({ [k]: (v + 1) % opts.length } as any)}><span>{label}</span><b>{opts[v] ?? opts[0]}</b></Fx>;
  return (
    <div className="screen settings">
      <div className="lib-head"><h1>Ajustes</h1></div>
      <div className="settings-cols">
        <div className="settings-list" data-fx-group="">
          <Fx className="setting" def onClick={() => { clearCache(); app.toast('Listas actualizadas'); app.go({ name: 'home' }); }}><span>⟳ Actualizar listas</span><b>Ahora</b></Fx>
          <Fx className="setting" onClick={() => app.go({ name: 'profiles' })}><span>👤 Perfiles</span><b>{app.profile.avatar} {app.profile.name}</b></Fx>
          <Fx className="setting" onClick={() => app.go({ name: 'profiles' })}><span>🧸 Perfil infantil de «{app.profile.name}»</span><b>{app.profile.kids ? 'Sí' : 'No'}</b></Fx>
          <Fx className="setting" onClick={() => setPinFor(true)}><span>🔒 PIN del perfil</span><b>{app.profile.pin ? 'Cambiar / quitar' : 'Crear'}</b></Fx>
          {app.profile.kids && <Fx className="setting" onClick={() => saveProfile({ kidsMinutes: [0, 30, 60, 90, 120, 180][([0, 30, 60, 90, 120, 180].indexOf(app.profile.kidsMinutes ?? 0) + 1) % 6] })}>
            <span>⏱ Tiempo diario infantil</span><b>{app.profile.kidsMinutes ? `${app.profile.kidsMinutes} min` : 'Sin límite'}</b></Fx>}
          <Fx className="setting" onClick={() => saveProfile({ blockAdult: !app.profile.blockAdult })}><span>🚫 Ocultar contenido para adultos</span><b className={app.profile.blockAdult ? 'on' : ''}>{app.profile.blockAdult ? 'Sí' : 'No'}</b></Fx>
          <Fx className="setting" onClick={() => saveProfile({ audio: ({ es: 'en', en: '', '': 'es' } as any)[app.profile.audio ?? 'es'] })}><span>🔊 Audio preferido</span><b>{({ es: 'Español latino', en: 'Inglés', '': 'Original' } as any)[app.profile.audio ?? 'es']}</b></Fx>
          <Fx className="setting" onClick={() => saveProfile({ subs: ({ '': 'es', es: 'en', en: '' } as any)[app.profile.subs ?? ''] })}><span>💬 Subtítulos preferidos</span><b>{({ '': 'Apagados', es: 'Español', en: 'Inglés' } as any)[app.profile.subs ?? '']}</b></Fx>
          <Choice label="Tamaño de subtítulos" opts={['Chico', 'Normal', 'Grande', 'Muy grande']} v={p.subSize} k="subSize" />
          <Choice label="Fondo de subtítulos" opts={['Sin fondo', 'Semitransparente', 'Negro']} v={p.subBg} k="subBg" />
          <Toggle label="Subtítulos en amarillo" v={p.subYellow} k="subYellow" />
          <Choice label="Formato de pantalla" opts={['Ajustar', 'Llenar', 'Zoom', '16:9', '4:3']} v={p.aspect} k="aspect" />
          <Fx className="setting" onClick={() => { upd({ style: p.style === 'top' ? 'side' : 'top' }); app.refreshProfile(); }}><span>🧭 Estilo de menú</span><b>{p.style === 'top' ? 'Barra superior' : 'Menú lateral'}</b></Fx>
          <Toggle label="Abrir en el último canal" v={p.lastChannelStart} k="lastChannelStart" />
          <Toggle label="Sonidos de navegación" v={p.navSounds} k="navSounds" />
          <Toggle label="Protector de pantalla (5 min)" v={p.screensaver} k="screensaver" />
          <Toggle label="Intro con sonido" v={p.intro} k="intro" />
          <Toggle label="Modo para equipos lentos" v={p.lite} k="lite" />
          {lgDevSupported() && <Fx className="setting" onClick={() => renewLgDevMode().then((ok) => { app.toast(ok ? 'Modo desarrollador renovado por 50 h' : `No se pudo renovar. ${lgDevError()}`); setP(store.prefs()); })}>
            <span>🛠 Modo desarrollador LG</span><b>{lgDevLast() ? `Renovado ${new Date(lgDevLast()).toLocaleString('es-MX', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })} · Renovar` : 'Renovar ahora'}</b></Fx>}
          {licenseRequired(app.config.texts) && <LicenseRow />}
          <Fx className="setting" onClick={measure}><span>📶 Prueba de velocidad</span><b>{speed || 'Medir'}</b></Fx>
          {wa && <Fx className="setting" onClick={() => app.toast(`Escríbenos por WhatsApp: +${wa}\nReporte: EmbyVerse TV ${__VERSION__} · ${deviceInfo()} · usuario ${acc?.username ?? '-'}`)}><span>💬 Ayuda por WhatsApp</span><b>+{wa}</b></Fx>}
          <Fx className="setting danger" onClick={() => { getEngine().stop(); store.setAccount(null); clearCache(); location.reload(); }}><span>⎋ Cerrar sesión</span><b /></Fx>
        </div>
        {pinFor && <PinPad title={app.profile.pin ? 'Nuevo PIN (4 dígitos). Escribe el mismo de antes para quitarlo' : 'Crea un PIN de 4 dígitos'}
          onCancel={() => setPinFor(false)} onDone={(v) => { saveProfile({ pin: app.profile.pin === v ? null : v }); setPinFor(false); app.toast(app.profile.pin === v ? 'PIN quitado' : 'PIN guardado'); }} />}
        <div className="about">
          <h3>Acerca de</h3>
          <p>EmbyVerse TV {__VERSION__} · {platformName}</p>
          <p className="muted">{deviceInfo()}</p>
          {acc && <p className="muted">Cuenta: {acc.username}{acc.expiresAt ? ` · vence ${new Date(acc.expiresAt).toLocaleDateString('es-MX')}` : ''}</p>}
          <p className="muted">Diseño v{app.config.version}</p>
        </div>
      </div>
    </div>
  );
}

const AVATARS = ['🦁', '🐼', '🦊', '🐯', '🐸', '🐵', '🐱', '🐶', '🦄', '🐧', '🚀', '⚽', '🎮', '🎬', '👑', '🌟'];

export function Profiles({ onPick }: { onPick?: () => void }) {
  const app = useApp();
  const [list, setList] = useState(() => store.profiles());
  const [pin, setPin] = useState<{ p: Profile; v: string } | null>(null);
  const pick = (p: Profile) => {
    const cur = store.activeProfile();
    if (cur.kids && cur.pin && cur.id !== p.id && !pin) { setPin({ p, v: '' }); return; }
    store.setActiveProfile(p.id); clearCache(); app.refreshProfile(); onPick ? onPick() : app.go({ name: 'home' });
  };
  const add = (kids: boolean) => {
    const p: Profile = { id: `p${Date.now()}`, name: kids ? 'Peques' : `Perfil ${list.length + 1}`, avatar: kids ? '🦄' : AVATARS[list.length % AVATARS.length], kids, blockAdult: kids };
    const l = [...list, p]; store.setProfiles(l); setList(l);
  };
  const toggleKids = (p: Profile) => { const l = list.map((x) => (x.id === p.id ? { ...x, kids: !x.kids, avatar: !x.kids ? '🦄' : x.avatar } : x)); store.setProfiles(l); setList(l); app.refreshProfile(); };
  return (
    <div className="screen profiles">
      <h1>¿Quién está viendo?</h1>
      <div className="profile-row" data-fx-group="">
        {list.map((p, i) => <Fx key={p.id} className="profile" def={i === 0} onClick={() => pick(p)}><span className="avatar">{p.avatar}</span><b>{p.name}</b>{p.kids && <small>Infantil</small>}</Fx>)}
        {list.length < 6 && <Fx className="profile add" onClick={() => add(false)}><span className="avatar">＋</span><b>Agregar perfil</b></Fx>}
        {list.length < 6 && <Fx className="profile add" onClick={() => add(true)}><span className="avatar">🧸</span><b>Perfil infantil</b></Fx>}
      </div>
      {list.length > 1 && <div className="row-scroll" data-fx-group="">{list.map((p) => <Fx key={p.id} className="chip" onClick={() => toggleKids(p)}>{p.name}: {p.kids ? 'Infantil ✓' : 'Normal'}</Fx>)}</div>}
      {pin && <PinPad title="PIN para salir del perfil infantil" onCancel={() => setPin(null)} onDone={(v) => { if (v === store.activeProfile().pin) { const p = pin.p; setPin(null); store.setActiveProfile(p.id); clearCache(); app.refreshProfile(); app.go({ name: 'home' }); } else app.toast('PIN incorrecto'); }} />}
    </div>
  );
}

export function PinPad({ title, onDone, onCancel }: { title: string; onDone: (pin: string) => void; onCancel: () => void }) {
  const [v, setV] = useState('');
  const press = (d: string) => { const n = (v + d).slice(0, 4); setV(n); if (n.length === 4) { onDone(n); setV(''); } };
  return (
    <div className="dialog" data-fx-layer="">
      <div className="dialog-box"><h2>{title}</h2><div className="pin-dots">{[0, 1, 2, 3].map((i) => <i key={i} className={i < v.length ? 'on' : ''} />)}</div>
        <div className="pinpad">{['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', '⌫'].map((d, i) => d ? <Fx key={i} className="key" def={d === '5'} onClick={() => d === '⌫' ? setV(v.slice(0, -1)) : press(d)}>{d}</Fx> : <span key={i} />)}</div>
        <Fx className="btn" onClick={onCancel}>Cancelar</Fx></div>
    </div>
  );
}


/** Ajustes: código del equipo y estado de la activación. */
function LicenseRow() {
  const app = useApp();
  const [st, setSt] = useState<LicState | null>(null);
  useEffect(() => { checkLicense(app.config.texts).then(setSt).catch(() => {}); }, []);
  const label = !st ? 'Revisando…' : st.state === 'active' ? (st.exp ? `Activo hasta ${st.exp}` : 'Activo de por vida') : st.state === 'trial' ? `Prueba: quedan ${st.daysLeft} días` : st.state === 'expired' ? `Venció el ${st.exp}` : 'Sin activar';
  return <Fx className="setting" onClick={() => checkLicense(app.config.texts).then(setSt)}><span>🔑 Código del equipo: <b>{deviceCode()}</b></span><b>{label}</b></Fx>;
}

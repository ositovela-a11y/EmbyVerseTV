import './polyfills';
import './styles.css';
import { createRoot } from 'react-dom/client';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Ctx, Fx, Logo, Clock, type AppCtx, type PlayRequest, type Route } from './ui';
import { applyTheme, cachedConfig, fetchConfig, isActive, isBlocked, type RemoteConfig } from './config';
import { store, type Profile } from './store';
import { Xtream } from './xtream';
import { setClient, newestMovies } from './catalog';
import { KEY, isKey, registerKeys, exitApp } from './platform';
import * as nav from './nav';
import { dispatchKeys } from './keys';
import { Home } from './screens/Home';
import { Live } from './screens/Live';
import { Library, MovieDetail, ShowDetail, Saga } from './screens/Library';
import { Search, Favorites, Guide, Settings, Profiles } from './screens/Misc';
import { Player } from './screens/Player';
import { Splash, Login, Suspended } from './screens/Start';
import { Season, seasonFor } from './season';

const MENU: { r: Route['name']; icon: string; label: string }[] = [
  { r: 'home', icon: '⌂', label: 'Inicio' }, { r: 'live', icon: '📺', label: 'TV en vivo' }, { r: 'movies', icon: '🎬', label: 'Películas' },
  { r: 'series', icon: '🎞', label: 'Series' }, { r: 'search', icon: '🔍', label: 'Buscar' }, { r: 'favorites', icon: '❤', label: 'Favoritos' }, { r: 'settings', icon: '⚙', label: 'Ajustes' },
];

const KIDS_PALETTE = { primary: '#FFC93C', secondary: '#FF5FA2', accent: '#3DDCFF', background: '#1D3FB8', surface: '#2C57D9', surfaceVariant: '#3D6BF0', onBackground: '#FFFFFF', textSecondary: '#D9E4FF', buttonText: '#2A1A00', focusRing: '#FFF176' };

function App() {
  const [config, setConfig] = useState<RemoteConfig>(cachedConfig);
  const [phase, setPhase] = useState<'splash' | 'login' | 'profiles' | 'app' | 'suspended'>('splash');
  const [stack, setStack] = useState<Route[]>([{ name: 'home' }]);
  const [profile, setProfile] = useState<Profile>(() => store.activeProfile());
  const [playing, setPlaying] = useState<PlayRequest | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ id: string; title: string; body: string } | null>(null);
  const [saver, setSaver] = useState(false);
  const idle = useRef(Date.now());
  const route = stack[stack.length - 1];

  // Diseño del panel: al abrir y cada 10 min (mensajes nuevos sin reiniciar la app).
  useEffect(() => {
    registerKeys();
    const load = () => fetchConfig().then((c) => c && setConfig(c));
    load(); const i = setInterval(load, 10 * 60_000); return () => clearInterval(i);
  }, []);
  useEffect(() => {
    applyTheme(config, profile.kids ? KIDS_PALETTE : undefined);
    document.body.classList.toggle('kids', !!profile.kids); document.body.classList.toggle('lite', store.prefs().lite);
    // Estilo de menú: el que elija el cliente o el «estilo predeterminado» del panel (por app o vendedor).
    const st = store.prefs().style || ({ style_cristal: 'top', style_purpura: 'top', style_verde_neon: 'top', style_naranja: 'top', style_oro: 'top' } as Record<string, string>)[config.texts['style.default'] ?? ''] || 'side';
    document.body.classList.toggle('nav-top', st === 'top');
  }, [config, profile, stack]);

  // Suspensión de clientes desde el panel.
  useEffect(() => {
    const acc = store.account();
    if (acc) isBlocked(config, acc.username).then((b) => { if (b) { store.wipe(); store.setBlocked(true); setPhase('suspended'); } else if (store.blockedFlag() && config.version > 0) store.setBlocked(false); });
    else if (store.blockedFlag()) setPhase('suspended');
  }, [config]);

  // Mensajes del administrador: aviso una vez por TV, revisado cada minuto.
  useEffect(() => {
    const check = () => { if (notice) return; const seen = store.seenMessages(); const m = config.messages.find((x) => isActive(x) && !seen.includes(x.id)); if (m && phase === 'app') setNotice(m); };
    check(); const i = setInterval(check, 60_000); return () => clearInterval(i);
  }, [config, phase, notice]);

  const afterSplash = useCallback(() => {
    if (store.blockedFlag()) { setPhase('suspended'); return; }
    const acc = store.account();
    if (!acc) { setPhase('login'); return; }
    setClient(new Xtream(acc));
    newestMovies(1).catch(() => {}); // calienta el catálogo
    setPhase(store.profiles().length > 1 ? 'profiles' : 'app');
  }, []);

  useEffect(() => {
    if (phase !== 'app') return;
    const p = store.prefs();
    if (p.lastChannelStart) { const last = store.lastLive(profile.id)[0]; if (last) setPlaying({ kind: 'live', id: last, title: 'Último canal' }); }
  }, [phase === 'app']);

  const ctx: AppCtx = useMemo(() => ({
    config, profile,
    go: (r) => setStack((s) => (['home', 'live', 'movies', 'series', 'search', 'favorites', 'settings'].includes(r.name) ? [r] : [...s, r])),
    back: () => setStack((s) => (s.length > 1 ? s.slice(0, -1) : s)),
    play: (p) => setPlaying(p),
    toast: (m) => { setToast(m); setTimeout(() => setToast(null), 5000); },
    refreshProfile: () => setProfile(store.activeProfile()),
  }), [config, profile]);

  // Foco inicial al cambiar de pantalla.
  useEffect(() => { const t = setTimeout(() => nav.focusFirst(), 60); return () => clearTimeout(t); }, [phase, stack.length, route, playing == null, notice]);

  // Teclas: primero la capa activa (reproductor, diálogos); luego flechas, OK y Atrás.
  useEffect(() => {
    const sounds = { move: new Audio('nav_move.wav'), select: new Audio('nav_select.wav') };
    sounds.move.volume = 0.25; sounds.select.volume = 0.3;
    const h = (e: KeyboardEvent) => {
      idle.current = Date.now();
      if (saver) { setSaver(false); e.preventDefault(); return; }
      const t = e.target as HTMLElement;
      if (t && t.tagName === 'INPUT' && ![13, 38, 40, 10009, 461, 27].includes(e.keyCode)) return;
      if (dispatchKeys(e)) { e.preventDefault(); return; }
      const k = e.keyCode;
      const snd = store.prefs().navSounds && !store.prefs().lite;
      if (k === KEY.LEFT || k === KEY.RIGHT || k === KEY.UP || k === KEY.DOWN) {
        e.preventDefault();
        const moved = nav.move(k === KEY.LEFT ? 'left' : k === KEY.RIGHT ? 'right' : k === KEY.UP ? 'up' : 'down');
        if (moved && snd) { try { sounds.move.currentTime = 0; sounds.move.play().catch(() => {}); } catch { /* */ } }
        return;
      }
      if (k === KEY.ENTER) { const f = nav.focused(); if (f) { e.preventDefault(); if (snd) { try { sounds.select.currentTime = 0; sounds.select.play().catch(() => {}); } catch { /* */ } } f.click(); } return; }
      if (isKey(k, KEY.BACK)) {
        e.preventDefault();
        if (notice) { store.markSeen(notice.id); setNotice(null); return; }
        if (stack.length > 1) { setStack((s) => s.slice(0, -1)); return; }
        if (route.name !== 'home') { setStack([{ name: 'home' }]); return; }
        exitApp();
      }
    };
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, [stack, route, notice, saver]);

  // Protector de pantalla tras 5 minutos sin usar el control (no durante el video).
  useEffect(() => {
    const i = setInterval(() => { if (!playing && phase === 'app' && store.prefs().screensaver && Date.now() - idle.current > 5 * 60_000) setSaver(true); }, 30_000);
    return () => clearInterval(i);
  }, [playing, phase]);

  const season = seasonFor(config.texts, !!profile.kids);
  let body: JSX.Element;
  if (phase === 'splash') body = <Splash onDone={afterSplash} />;
  else if (phase === 'suspended') body = <Suspended />;
  else if (phase === 'login') body = <Login onLogged={() => { setClient(new Xtream(store.account()!)); setPhase('app'); }} />;
  else if (phase === 'profiles') body = <Profiles onPick={() => { setProfile(store.activeProfile()); setPhase('app'); }} />;
  else body = (
    <div className="shell">
      <nav className="menu" data-fx-group="">
        <Logo className="menu-logo" />
        {MENU.map((m) => <Fx key={m.r} className={`menu-item ${route.name === m.r ? 'sel' : ''}`} onClick={() => setStack([{ name: m.r } as Route])}><span className="mi">{m.icon}</span><span className="ml">{m.label}</span></Fx>)}
        <div className="menu-foot"><span className="avatar sm">{profile.avatar}</span><Clock /></div>
      </nav>
      <main className="content" key={stack.length + route.name}>{screenFor(route)}</main>
    </div>
  );

  return (
    <Ctx.Provider value={ctx}>
      <div className={`app ${playing ? 'is-playing' : ''}`}>
        {!playing && season && !store.prefs().lite && <Season kind={season} />}
        <div style={{ display: playing ? 'none' : 'block' }}>{body}</div>
        {playing && <Player key={playing.kind + playing.id} req={playing} onClose={() => setPlaying(null)} />}
        {notice && !playing && <div className="dialog" data-fx-layer=""><div className="dialog-box"><h2>📢 {notice.title}</h2><p>{notice.body}</p>
          <Fx className="btn primary" def onClick={() => { store.markSeen(notice.id); setNotice(null); }}>Entendido</Fx></div></div>}
        {saver && <Screensaver />}
        {toast && <div className="toast">{toast}</div>}
      </div>
    </Ctx.Provider>
  );
}

function screenFor(r: Route): JSX.Element {
  switch (r.name) {
    case 'home': return <Home />;
    case 'live': return <Live cat={r.cat} />;
    case 'movies': return <Library kind="movies" cat={r.cat} />;
    case 'series': return <Library kind="series" cat={r.cat} />;
    case 'movie': return <MovieDetail id={r.id} />;
    case 'show': return <ShowDetail id={r.id} />;
    case 'search': return <Search />;
    case 'favorites': return <Favorites />;
    case 'settings': return <Settings />;
    case 'saga': return <Saga k={r.key} />;
    case 'profiles': return <Profiles />;
    case 'guide': return <Guide />;
  }
}

function Screensaver() {
  const [items, setItems] = useState<{ name: string; poster?: string }[]>([]);
  const [i, setI] = useState(0);
  useEffect(() => { newestMovies(20).then((l) => setItems(l.filter((m) => m.poster))); const t = setInterval(() => setI((x) => x + 1), 9000); return () => clearInterval(t); }, []);
  const m = items.length ? items[i % items.length] : null;
  return <div className="saver">{m && <img key={i} className="saver-img kenburns" src={m.poster} alt="" />}<div className="saver-fade" />{m && <h2>{m.name}</h2>}<div className="saver-clock"><Clock /><small>Presiona cualquier botón para volver</small></div><Logo className="saver-logo" /></div>;
}

createRoot(document.getElementById('root')!).render(<App />);

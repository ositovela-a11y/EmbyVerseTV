import { useEffect, useRef, useState } from 'react';
import { Clock, Fx, Img, useApp } from '../ui';
import type { PlayRequest } from '../ui';
import { getEngine, qualityLabel, type Track } from '../player';
import { client } from '../catalog';
import { store } from '../store';
import { pushKeys } from '../keys';
import { KEY, isKey, isTizen } from '../platform';
import { focusFirst } from '../nav';
import type { EpgItem } from '../xtream';
import { PinPad } from './Misc';

export function Player({ req, onClose }: { req: PlayRequest; onClose: () => void }) {
  const app = useApp();
  const eng = getEngine();
  const [item, setItem] = useState(req);
  const [ui, setUi] = useState(true);
  const [state, setState] = useState<'loading' | 'playing' | 'buffering' | 'error' | 'ended'>('loading');
  const [err, setErr] = useState('');
  const [pos, setPos] = useState(0); const [dur, setDur] = useState(0);
  const [panel, setPanel] = useState<null | 'options' | 'channels'>(null);
  const [digits, setDigits] = useState('');
  const [zapName, setZapName] = useState('');
  const [epg, setEpg] = useState<EpgItem[]>([]);
  const [tracks, setTracks] = useState<Track[]>([]);
  const [quality, setQuality] = useState('');
  const [upNext, setUpNext] = useState<number | null>(null);
  const [kidsLock, setKidsLock] = useState(false);
  const [fav, setFav] = useState(false);
  const prefs = store.prefs();
  const [aspect, setAspect] = useState(prefs.aspect);
  const prevLive = useRef<PlayRequest | null>(null);
  const triedAlt = useRef(false);
  const hideT = useRef<any>(null); const zapT = useRef<any>(null); const digT = useRef<any>(null);
  const live = item.kind === 'live';

  const url = (it: PlayRequest, alt = false) => {
    const x = client();
    if (it.kind === 'live') return x.liveUrl(it.id, (isTizen ? !alt : alt) ? 'ts' : 'm3u8');
    if (it.kind === 'movie') return x.movieUrl(it.id, it.ext);
    return x.episodeUrl(it.id, it.ext);
  };

  const showUi = () => { setUi(true); clearTimeout(hideT.current); hideT.current = setTimeout(() => setUi(false), 4500); };

  const start = (it: PlayRequest, alt = false) => {
    setState('loading'); setErr(''); setUpNext(null); setQuality(''); setTracks([]);
    triedAlt.current = alt;
    eng.on((e, d) => {
      if (e === 'playing' || e === 'ready') { setState('playing'); setTracks(eng.tracks()); applyLanguage(); }
      else if (e === 'buffering') setState('buffering');
      else if (e === 'ended') setState('ended');
      else if (e === 'error') {
        if (!triedAlt.current) { start(it, true); return; } // otro formato (HLS ↔ .ts) antes de rendirse
        setState('error'); setErr(d ?? '');
      }
    });
    eng.load(url(it, alt), { live: it.kind === 'live', startMs: it.startMs });
    eng.setAspect(aspect);
    if (it.kind === 'live') { store.pushLive(app.profile.id, it.id); client().shortEpg(it.id, 3).then(setEpg); }
    setFav(store.isFav(app.profile.id, it.kind === 'live' ? 'live' : it.kind === 'movie' ? 'movie' : 'series', it.kind === 'episode' ? it.seriesId ?? it.id : it.id));
    showUi();
  };

  const applyLanguage = () => {
    const p = app.profile; const t = eng.tracks();
    const want = (p.audio ?? 'es') === 'es' ? /^(es|spa|esp|lat)/i : p.audio === 'en' ? /^(en|eng)/i : null;
    if (want) { const a = t.find((x) => x.kind === 'audio' && want.test(x.lang ?? x.label)); if (a) eng.selectTrack(a, 'audio'); }
    if (p.subs) { const re = p.subs === 'es' ? /^(es|spa)/i : /^(en|eng)/i; const s = t.find((x) => x.kind === 'text' && re.test(x.lang ?? x.label)); if (s) eng.selectTrack(s, 'text'); }
  };

  useEffect(() => { start(item); return () => { saveProgress(); eng.stop(); }; }, []);

  // Posición, calidad, progreso guardado y límite diario del perfil infantil.
  useEffect(() => {
    let ticks = 0;
    const i = setInterval(() => {
      setPos(eng.position()); setDur(eng.duration()); setQuality(qualityLabel(eng.videoHeight()));
      if (++ticks % 10 === 0) saveProgress();
      if (ticks % 60 === 0 && app.profile.kids && (app.profile.kidsMinutes ?? 0) > 0 && !eng.paused()) {
        store.addKidsMinute(app.profile.id);
        if (store.kidsUsed(app.profile.id) >= (app.profile.kidsMinutes ?? 0)) { eng.pause(); setKidsLock(true); }
      }
      // Fin de episodio: cuenta regresiva para el siguiente.
      const d = eng.duration(), p = eng.position();
      if (item.kind === 'episode' && d > 0 && d - p < 20000 && upNext == null && nextEpisode()) setUpNext(10);
    }, 1000);
    return () => clearInterval(i);
  }, [item, upNext]);

  useEffect(() => {
    if (upNext == null) return;
    if (upNext <= 0) { const n = nextEpisode(); if (n) switchTo(n); return; }
    const t = setTimeout(() => setUpNext((u) => (u == null ? null : u - 1)), 1000); return () => clearTimeout(t);
  }, [upNext]);
  useEffect(() => { if (state === 'ended' && item.kind === 'episode' && nextEpisode()) setUpNext(0); }, [state]);

  const saveProgress = () => {
    if (item.kind === 'live') return;
    const p = eng.position(), d = eng.duration(); if (d <= 0 || p < 5000) return;
    store.saveProgress(app.profile.id, { kind: item.kind, id: item.id, seriesId: item.seriesId, title: item.kind === 'episode' ? `${item.title} · T${item.season}E${item.episode}` : item.title,
      image: item.image, pos: p, dur: d, at: Date.now(), ext: item.ext, season: item.season, episode: item.episode });
  };

  const nextEpisode = () => {
    if (item.kind !== 'episode' || !item.list) return null;
    const i = item.list.findIndex((x) => x.id === item.id); const n = item.list[i + 1];
    return n ? { ...item, id: n.id, ext: n.ext, season: n.season, episode: n.episode, subtitle: `T${n.season} · E${n.episode} · ${n.title}`, image: n.image ?? item.image, startMs: undefined } : null;
  };

  const switchTo = (it: PlayRequest) => { saveProgress(); if (item.kind === 'live' && it.kind === 'live' && it.id !== item.id) prevLive.current = item; setItem(it); start(it); };

  const zap = (d: number) => {
    const l = item.list; if (!live || !l || !l.length) return;
    const curId = (zapT.current && zapName ? l.find((x) => x.title === zapName)?.id : null) ?? item.id;
    const i = l.findIndex((x) => x.id === curId); const n = l[(i + d + l.length) % l.length];
    setZapName(n.title); showUi();
    clearTimeout(zapT.current);
    zapT.current = setTimeout(() => { zapT.current = null; setZapName(''); switchTo({ ...item, id: n.id, title: n.title, image: n.image }); }, 450);
  };

  const typeDigit = (n: number) => {
    const v = (digits + n).slice(-4); setDigits(v); clearTimeout(digT.current);
    digT.current = setTimeout(() => {
      setDigits(''); const num = parseInt(v, 10); const l = item.list ?? [];
      const ch = l.find((x) => x.num === num) ?? l[num - 1];
      if (ch) switchTo({ ...item, id: ch.id, title: ch.title, image: ch.image });
    }, 1500);
  };

  const introVisible = item.kind === 'episode' && pos > 8000 && pos < 300000 && dur > 600000;

  useEffect(() => pushKeys((e) => {
    const k = e.keyCode;
    if (kidsLock) return false;
    if (panel) { if (isKey(k, KEY.BACK)) { setPanel(null); return true; } return false; }
    showUi();
    if (isKey(k, KEY.BACK)) { onClose(); return true; }
    if (k >= 48 && k <= 57 && live) { typeDigit(k - 48); return true; }
    if (isKey(k, KEY.PREV_CH) && prevLive.current) { switchTo(prevLive.current); return true; }
    if (live && (k === KEY.UP || isKey(k, KEY.CH_UP))) { zap(-1); return true; }
    if (live && (k === KEY.DOWN || isKey(k, KEY.CH_DOWN))) { zap(1); return true; }
    if (live && k === KEY.LEFT) { setPanel('channels'); setTimeout(() => focusFirst(), 50); return true; }
    if (k === KEY.ENTER) {
      if (upNext != null) { const n = nextEpisode(); if (n) switchTo(n); return true; }
      if (introVisible) { eng.seek(pos + 85000); return true; }
      if (live || ui) { setPanel('options'); setTimeout(() => focusFirst(), 50); return true; }
    }
    if (!live) {
      if (k === KEY.LEFT || isKey(k, KEY.RW)) { eng.seek(eng.position() - 10000); return true; }
      if (k === KEY.RIGHT || isKey(k, KEY.FF)) { eng.seek(eng.position() + 30000); return true; }
      if (isKey(k, KEY.PLAY_PAUSE) || k === KEY.ENTER) { eng.paused() ? eng.play() : eng.pause(); return true; }
      if (k === KEY.UP || k === KEY.DOWN) { setPanel('options'); setTimeout(() => focusFirst(), 50); return true; }
    }
    if (isKey(k, KEY.PLAY)) { eng.play(); return true; }
    if (isKey(k, KEY.PAUSE)) { eng.pause(); return true; }
    if (isKey(k, KEY.STOP)) { onClose(); return true; }
    return true; // el reproductor se queda con las demás teclas
  }), [item, panel, ui, digits, zapName, upNext, introVisible, kidsLock, live, pos]);

  const now = Date.now();
  const cur = epg.find((e) => e.start <= now && e.end > now); const nxt = epg.find((e) => e.start > now);
  const fmtT = (ms: number) => { const s = Math.floor(ms / 1000); const h = Math.floor(s / 3600); const m = Math.floor((s % 3600) / 60); return `${h ? h + ':' : ''}${String(m).padStart(h ? 2 : 1, '0')}:${String(s % 60).padStart(2, '0')}`; };
  const fmt = (ms: number) => new Date(ms).toLocaleTimeString('es-MX', { hour: 'numeric', minute: '2-digit' });
  const subCls = `subs-size-${prefs.subSize} subs-bg-${prefs.subBg}${prefs.subYellow ? ' subs-yellow' : ''}`;

  return (
    <div className={`player ${subCls}`}>
      {(state === 'loading' || state === 'buffering') && <div className="player-spin"><div className="spinner" /></div>}
      {state === 'error' && <div className="player-error" data-fx-layer=""><h2>{live ? 'Este canal no está disponible ahora' : 'No se pudo reproducir este contenido'}</h2>
        <p>{navigator.onLine === false ? 'No hay conexión a internet. Revisa tu Wi-Fi o cable.' : live ? 'Puede ser algo momentáneo del canal. Prueba otra vez o pasa al siguiente.' : 'El video no respondió. Prueba otra vez en un momento.'}</p><small>{err}</small>
        <div className="row-btns"><Fx className="btn primary" def onClick={() => start(item)}>Reintentar</Fx>
          {live ? <Fx className="btn" onClick={() => zap(1)}>Siguiente canal ⏭</Fx> : <Fx className="btn" onClick={onClose}>Salir</Fx>}</div></div>}
      {(digits || zapName) && <div className="zap-pill">{digits ? digits.padEnd(3, '_').split('').join(' ') : zapName}</div>}
      {ui && !panel && <div className="player-top"><b>{item.title}</b>{item.subtitle && <span>{item.subtitle}</span>}<div className="grow" />{quality && <span className={`q ${quality === '4K' ? 'k4' : ''}`}>{quality}</span>}<Clock /></div>}
      {ui && !panel && live && cur && <div className="mini-guide"><div><small>AHORA</small> <b>{cur.title}</b> <span>{fmt(cur.start)}–{fmt(cur.end)}</span></div>
        <div className="bar"><div style={{ width: `${Math.min(100, ((now - cur.start) / (cur.end - cur.start)) * 100)}%` }} /></div>
        {nxt && <div className="muted"><small>SIGUE</small> {fmt(nxt.start)} {nxt.title}</div>}</div>}
      {ui && !panel && !live && dur > 0 && <div className="player-bottom"><span>{fmtT(pos)}</span><div className="bar big"><div style={{ width: `${(pos / dur) * 100}%` }} /></div><span>{fmtT(dur)}</span>
        <small className="muted">◀ −10 s · ▶ +30 s · OK pausa · ▲ opciones</small></div>}
      {introVisible && !panel && <div className="skip-intro">Saltar intro ⏭ <small>(OK)</small></div>}
      {upNext != null && <div className="up-next">Siguiente episodio en {upNext}… <small>(OK para verlo ya)</small></div>}
      {panel === 'options' && <div className="side-panel" data-fx-layer="">
        <h3>Opciones</h3>
        {tracks.filter((t) => t.kind === 'audio').length > 1 && <><h4>Audio</h4>{tracks.filter((t) => t.kind === 'audio').map((t, i) => <Fx key={'a' + t.index} def={i === 0} className="opt" onClick={() => { eng.selectTrack(t, 'audio'); setPanel(null); }}>{t.label}</Fx>)}</>}
        <h4>Subtítulos</h4>
        <Fx className="opt" def={tracks.filter((t) => t.kind === 'audio').length <= 1} onClick={() => { eng.selectTrack(null, 'text'); setPanel(null); }}>Apagados</Fx>
        {tracks.filter((t) => t.kind === 'text').map((t) => <Fx key={'t' + t.index} className="opt" onClick={() => { eng.selectTrack(t, 'text'); setPanel(null); }}>{t.label}</Fx>)}
        <h4>Más</h4>
        <Fx className="opt" onClick={() => { const n = (aspect + 1) % 5; setAspect(n); eng.setAspect(n); store.setPrefs({ aspect: n }); }}>Pantalla: {['Ajustar', 'Llenar', 'Zoom', '16:9', '4:3'][aspect]}</Fx>
        <Fx className="opt" onClick={() => setFav(store.toggleFav(app.profile.id, { kind: live ? 'live' : item.kind === 'movie' ? 'movie' : 'series', id: item.kind === 'episode' ? item.seriesId ?? item.id : item.id, title: item.title, image: item.image }))}>{fav ? '★ En favoritos (quitar)' : '★ Agregar a favoritos'}</Fx>
        {live && prevLive.current && <Fx className="opt" onClick={() => { setPanel(null); switchTo(prevLive.current!); }}>↺ Canal anterior</Fx>}
        {live && <Fx className="opt" onClick={() => { setPanel('channels'); setTimeout(() => focusFirst(), 50); }}>☰ Lista de canales</Fx>}
      </div>}
      {panel === 'channels' && item.list && <div className="side-panel left" data-fx-layer="">
        <h3>Canales</h3>
        <div className="ch-scroll">{item.list.map((c, i) => <Fx key={c.id} className={`opt ch ${c.id === item.id ? 'sel' : ''}`} def={c.id === item.id} onClick={() => { setPanel(null); switchTo({ ...item, id: c.id, title: c.title, image: c.image }); }}>
          <span className="ch-num">{c.num ?? i + 1}</span><Img src={c.image} alt={c.title} className="ch-logo" fit="contain" /><span>{c.title}</span></Fx>)}</div>
      </div>}
      {kidsLock && <PinPad title="Se acabó el tiempo de hoy. PIN de un adulto para seguir" onCancel={onClose} onDone={(v) => { if (v === app.profile.pin) { setKidsLock(false); eng.play(); } else app.toast('PIN incorrecto'); }} />}
    </div>
  );
}
